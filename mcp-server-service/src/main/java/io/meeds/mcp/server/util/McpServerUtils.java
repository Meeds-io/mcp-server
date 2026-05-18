/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package io.meeds.mcp.server.util;

import static org.springframework.ai.mcp.McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.MimeType;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Role;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class McpServerUtils {

  private static final Map<String, String> TOOL_RESPONSE_MIME_TYPE = new ConcurrentHashMap<>();

  private McpServerUtils() {
    // Static Methods
  }

  public static Map<String, String> getToolResponseMimeType() {
    return TOOL_RESPONSE_MIME_TYPE;
  }

  public static List<McpServerFeatures.SyncToolSpecification> toSyncToolSpecifications(List<ToolCallback> tools) {

    return tools.stream()
                .collect(Collectors.toMap(tc -> tc.getToolDefinition().name(),
                                          tc -> tc,
                                          (existing, replacement) -> existing))
                // remove duplication
                .values()
                .stream()
                .map(tc -> {
                  String toolName = tc.getToolDefinition().name();
                  MimeType mimeType = getMimeType(toolName);
                  return toSyncToolSpecification(tc, mimeType);
                })
                .toList();
  }

  public static List<McpServerFeatures.AsyncToolSpecification> toAsyncToolSpecification(List<ToolCallback> tools) {
    return tools.stream()
                .collect(Collectors.toMap(tool -> tool.getToolDefinition().name(),
                                          tool -> tool,
                                          (existing, replacement) -> existing))
                // remove duplication
                .values()
                .stream()
                .map(tool -> {
                  String toolName = tool.getToolDefinition().name();
                  MimeType mimeType = getMimeType(toolName);
                  return toAsyncToolSpecification(tool, mimeType);
                })
                .toList();
  }

  public static List<ToolCallback> aggregateToolCallbacks(ObjectProvider<List<ToolCallback>> toolCalls,
                                                          List<ToolCallback> toolCallbacksList,
                                                          List<ToolCallbackProvider> toolCallbackProvider) {

    List<ToolCallback> tools = new ArrayList<>(toolCalls.stream().flatMap(List::stream).toList());
    if (!CollectionUtils.isEmpty(toolCallbacksList)) {
      tools.addAll(toolCallbacksList);
    }
    List<ToolCallback> providerToolCallbacks = toolCallbackProvider.stream()
                                                                   .map(pr -> List.of(pr.getToolCallbacks()))
                                                                   .flatMap(List::stream)
                                                                   .toList();
    tools.addAll(providerToolCallbacks);
    return tools;
  }

  public static McpServerFeatures.AsyncToolSpecification toAsyncToolSpecification(ToolCallback toolCallback,
                                                                                  MimeType mimeType) {

    McpServerFeatures.SyncToolSpecification syncToolSpecification = toSyncToolSpecification(toolCallback, mimeType);

    return McpServerFeatures.AsyncToolSpecification.builder()
                                                   .tool(syncToolSpecification.tool())
                                                   .callHandler((exchange, request) -> Mono
                                                                                           .fromCallable(
                                                                                                         () -> syncToolSpecification.callHandler()
                                                                                                                                    .apply(new McpSyncServerExchange(exchange),
                                                                                                                                           request))
                                                                                           .subscribeOn(Schedulers.boundedElastic()))
                                                   .build();
  }

  public static McpServerFeatures.SyncToolSpecification toSyncToolSpecification(ToolCallback toolCallback,
                                                                                MimeType mimeType) {
    var sharedSpec = toSharedSyncToolSpecification(toolCallback, mimeType);
    return McpServerFeatures.SyncToolSpecification.builder()
                                                  .tool(sharedSpec.tool())
                                                  .callHandler((exchange, request) -> sharedSpec.sharedHandler()
                                                                                                .apply(exchange,
                                                                                                       request))
                                                  .build();
  }

  public static MimeType getMimeType(String toolName) {
    if (TOOL_RESPONSE_MIME_TYPE.containsKey(toolName)) {
      return MimeType.valueOf(TOOL_RESPONSE_MIME_TYPE.get(toolName));
    } else {
      return null;
    }
  }

  private static SharedSyncToolSpecification toSharedSyncToolSpecification(ToolCallback toolCallback,
                                                                           MimeType mimeType) {

    var tool = McpSchema.Tool.builder()
                             .name(toolCallback.getToolDefinition().name())
                             .description(toolCallback.getToolDefinition().description())
                             .inputSchema(ModelOptionsUtils.jsonToObject(toolCallback.getToolDefinition().inputSchema(),
                                                                         McpSchema.JsonSchema.class))
                             .build();

    return new SharedSyncToolSpecification(tool, (exchangeOrContext, request) -> {
      try {
        String callResult = toolCallback.call(ModelOptionsUtils.toJsonString(request.arguments()),
                                              new ToolContext(Map.of(TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchangeOrContext)));
        if (mimeType != null && mimeType.toString().startsWith("image")) {
          McpSchema.Annotations annotations = new McpSchema.Annotations(List.of(Role.ASSISTANT), null);
          return McpSchema.CallToolResult.builder()
                                         .content(List.of(new McpSchema.ImageContent(annotations,
                                                                                     callResult,
                                                                                     mimeType.toString())))
                                         .isError(false)
                                         .build();
        }
        return McpSchema.CallToolResult.builder()
                                       .content(List.of(new McpSchema.TextContent(callResult)))
                                       .isError(false)
                                       .build();
      } catch (Exception e) {
        return McpSchema.CallToolResult.builder()
                                       .content(List.of(new McpSchema.TextContent(e.getMessage())))
                                       .isError(true)
                                       .build();
      }
    });
  }

  private record SharedSyncToolSpecification(McpSchema.Tool tool,
                                             BiFunction<Object, CallToolRequest, McpSchema.CallToolResult> sharedHandler) {
  }
}

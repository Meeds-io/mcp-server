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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.MimeType;

import io.meeds.mcp.server.model.SimpleToolDefinition;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class McpServerUtils {

  private static final Map<String, String> TOOL_RESPONSE_MIME_TYPE = new ConcurrentHashMap<>();

  private static final JsonHelper          JSON_HELPER             = new JsonHelper();

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

    ToolDefinition toolDefinition = toolCallback.getToolDefinition();
    McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder(toolDefinition.name(),
                                                                JSON_HELPER.fromJson(toolDefinition.inputSchema(),
                                                                                     new ParameterizedTypeReference<Map<String, Object>>() {
                                                                                     }))
                                                       .description(toolDefinition.description());
    if (toolDefinition instanceof SimpleToolDefinition simpleToolDefinition) {
      // Title and annotations (readOnlyHint, destructiveHint...) drive the MCP
      // clients display and approval UX: keep exposing them on tools/list
      toolBuilder.title(simpleToolDefinition.getTitle());
      ToolAnnotations annotations = simpleToolDefinition.getAnnotations();
      if (annotations != null) {
        toolBuilder.annotations(new ToolAnnotations(Objects.toString(annotations.title(), simpleToolDefinition.getTitle()),
                                                    annotations.readOnlyHint(),
                                                    annotations.destructiveHint(),
                                                    annotations.idempotentHint(),
                                                    annotations.openWorldHint(),
                                                    annotations.returnDirect()));
      }
    } else {
      log.warn("Tool Definition '{}' seems not having associated annotations", toolDefinition.name());
    }
    McpSchema.Tool tool = toolBuilder.build();

    return new SharedSyncToolSpecification(tool, (exchangeOrContext, request) -> {
      try {
        String callResult = toolCallback.call(JSON_HELPER.toJson(request.arguments()),
                                              new ToolContext(Map.of(TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchangeOrContext)));
        if (mimeType != null && mimeType.toString().startsWith("image")) {
          McpSchema.Annotations annotations = McpSchema.Annotations.builder()
                                                                   .audience(List.of(Role.ASSISTANT))
                                                                   .build();
          return McpSchema.CallToolResult.builder()
                                         .content(List.of(McpSchema.ImageContent.builder(callResult, mimeType.toString())
                                                                                .annotations(annotations)
                                                                                .build()))
                                         .isError(false)
                                         .build();
        }
        return McpSchema.CallToolResult.builder()
                                       .content(List.of(McpSchema.TextContent.builder(callResult)
                                                                             .build()))
                                       .isError(false)
                                       .build();
      } catch (Exception e) {
        return McpSchema.CallToolResult.builder()
                                       .content(List.of(McpSchema.TextContent.builder(e.getMessage())
                                                                             .build()))
                                       .isError(true)
                                       .build();
      }
    });
  }

  private record SharedSyncToolSpecification(McpSchema.Tool tool,
                                             BiFunction<Object, CallToolRequest, McpSchema.CallToolResult> sharedHandler) {
  }
}

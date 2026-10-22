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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
public class McpServerUtils {

  private static final Map<String, String> TOOL_RESPONSE_MIME_TYPE = new ConcurrentHashMap<>();

  /**
   * The optional argument every tool takes, unless it declares one of its own
   * with that name: the comma-separated top-level keys to keep in each item of
   * a list result.
   */
  public static final String               FIELDS_ARGUMENT         = "fields";

  /**
   * The description of the {@link #FIELDS_ARGUMENT} argument, as clients list
   * it.
   */
  public static final String               FIELDS_DESCRIPTION      =
                                                              "Comma-separated top-level keys to keep in each item of a list result, "
                                                                  + "to get a shorter answer; the list's envelope keys (count, has_more...) are always kept, "
                                                                  + "and a single item is returned whole. Omit it to get every key.";

  /**
   * The keys of which at least one marks a JSON object as a list envelope,
   * whose arrays hold the list's items: the paging keys every list collection
   * of the tools carries.
   */
  private static final Set<String>         LIST_ENVELOPE_KEYS      = Set.of("count",
                                                                            "used_offset",
                                                                            "used_limit",
                                                                            "has_more",
                                                                            "next_offset");

  private static final String              PROPERTIES_KEY          = "properties";

  private static final JsonHelper          JSON_HELPER             = new JsonHelper();

  private static final JsonMapper          JSON_MAPPER             = JsonMapper.builder()
                                                                               .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                                                               .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                                                               .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                                                                               .build();

  /**
   * Static methods only, not instantiated.
   */
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

  /**
   * Builds the MCP tool of a callback, with the title, annotations and icon of
   * its definition, and the call handler that runs it.
   *
   * @param toolCallback the tool callback
   * @param mimeType     the mime type of the tool's result, or null for text
   * @return the MCP tool specification
   */
  private static SharedSyncToolSpecification toSharedSyncToolSpecification(ToolCallback toolCallback,
                                                                           MimeType mimeType) {

    ToolDefinition toolDefinition = toolCallback.getToolDefinition();
    Map<String, Object> inputSchema = JSON_HELPER.fromJson(toolDefinition.inputSchema(),
                                                           new ParameterizedTypeReference<Map<String, Object>>() {
                                                           });
    // A tool declaring its own 'fields' argument receives it as is, and its
    // result is not projected
    boolean projectable = inputSchema != null && !declaresProperty(inputSchema, FIELDS_ARGUMENT);
    McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder(toolDefinition.name(),
                                                                projectable ? withFieldsProperty(inputSchema) : inputSchema)
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
      // The add-on's Font Awesome icon, as a standard MCP icon for external
      // clients
      List<McpSchema.Icon> icons = McpToolIconUtils.toMcpIcons(simpleToolDefinition.getIcon());
      if (!icons.isEmpty()) {
        toolBuilder.icons(icons);
      }
    } else {
      log.warn("Tool Definition '{}' seems not having associated annotations", toolDefinition.name());
    }
    McpSchema.Tool tool = toolBuilder.build();

    return new SharedSyncToolSpecification(tool, (exchangeOrContext, request) -> {
      try {
        Map<String, Object> arguments = request.arguments();
        Set<String> fields = Set.of();
        if (projectable && arguments != null) {
          fields = parseFields(arguments.get(FIELDS_ARGUMENT));
        }
        // The tool, its approval, grant and space scope checks and its trace
        // see the arguments without 'fields', which only shapes the answer
        String callResult = toolCallback.call(JSON_HELPER.toJson(projectable ? withoutFieldsArgument(arguments) : arguments),
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
                                       .content(List.of(McpSchema.TextContent.builder(projectFields(callResult, fields))
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

  /**
   * Adds the optional {@link #FIELDS_ARGUMENT} string property to a tool's
   * input schema, without changing the schema it was given nor its required
   * properties.
   *
   * @param inputSchema the tool's input schema
   * @return a copy of the schema declaring the property
   */
  static Map<String, Object> withFieldsProperty(Map<String, Object> inputSchema) {
    Map<String, Object> schema = new LinkedHashMap<>(inputSchema);
    Map<String, Object> properties = new LinkedHashMap<>();
    if (schema.get(PROPERTIES_KEY) instanceof Map<?, ?> declaredProperties) {
      declaredProperties.forEach((name, value) -> properties.put(String.valueOf(name), value));
    }
    Map<String, Object> fieldsProperty = new LinkedHashMap<>();
    fieldsProperty.put("type", "string");
    fieldsProperty.put("description", FIELDS_DESCRIPTION);
    properties.put(FIELDS_ARGUMENT, fieldsProperty);
    schema.put(PROPERTIES_KEY, properties);
    return schema;
  }

  /**
   * @param inputSchema  a tool's input schema
   * @param propertyName a property name
   * @return true when the schema declares that property
   */
  static boolean declaresProperty(Map<String, Object> inputSchema, String propertyName) {
    return inputSchema.get(PROPERTIES_KEY) instanceof Map<?, ?> properties && properties.containsKey(propertyName);
  }

  /**
   * @param arguments the call arguments, may be {@code null}
   * @return the arguments without {@link #FIELDS_ARGUMENT}, the same instance
   *         when they don't carry it
   */
  static Map<String, Object> withoutFieldsArgument(Map<String, Object> arguments) {
    if (arguments == null || !arguments.containsKey(FIELDS_ARGUMENT)) {
      return arguments;
    }
    Map<String, Object> toolArguments = new LinkedHashMap<>(arguments);
    toolArguments.remove(FIELDS_ARGUMENT);
    return toolArguments;
  }

  /**
   * Reads the keys a caller asked to keep: a comma-separated string, or a list
   * of strings, each one trimmed, the blank ones ignored.
   *
   * @param value the {@link #FIELDS_ARGUMENT} argument, may be {@code null}
   * @return the keys to keep, empty when every key is to be kept
   */
  static Set<String> parseFields(Object value) {
    Collection<?> names = switch (value) {
    case String text -> Arrays.asList(text.split(","));
    case Collection<?> collection -> collection;
    case null, default -> List.of();
    };
    Set<String> fields = new LinkedHashSet<>();
    names.stream()
         .filter(Objects::nonNull)
         .map(name -> StringUtils.trim(String.valueOf(name)))
         .filter(StringUtils::isNotBlank)
         .forEach(fields::add);
    return fields;
  }

  /**
   * Keeps only the requested keys in each item of a JSON list result. In a
   * top-level JSON array, its elements are projected. In a list envelope, a
   * JSON object carrying at least one of {@link #LIST_ENVELOPE_KEYS}, each
   * element of every top-level array is projected and every other top-level
   * key is kept. A requested key an item lacks stays absent. Any other JSON
   * object is a single item and is returned whole, its arrays included, as is
   * any other result, a non-JSON text or one that doesn't parse included.
   *
   * @param result the tool's result
   * @param fields the keys to keep, empty to keep every key
   * @return the projected result, the same instance when nothing was projected
   */
  static String projectFields(String result, Set<String> fields) {
    if (fields == null || fields.isEmpty() || StringUtils.isBlank(result)) {
      return result;
    }
    JsonNode node;
    try {
      node = JSON_MAPPER.readTree(result);
    } catch (JacksonException e) {
      return result;
    }
    boolean projected = false;
    if (node instanceof ArrayNode items) {
      projected = projectItems(items, fields);
    } else if (node instanceof ObjectNode envelope && isListEnvelope(envelope)) {
      for (JsonNode value : envelope.values()) {
        if (value instanceof ArrayNode items) {
          projected = projectItems(items, fields) || projected;
        }
      }
    }
    return projected ? JSON_MAPPER.writeValueAsString(node) : result;
  }

  /**
   * @param object a top-level JSON object of a tool's result
   * @return true when the object carries at least one of
   *         {@link #LIST_ENVELOPE_KEYS}, false for a single item
   */
  private static boolean isListEnvelope(ObjectNode object) {
    return LIST_ENVELOPE_KEYS.stream().anyMatch(object::has);
  }

  /**
   * @param items  the items of a list
   * @param fields the keys to keep in each item that is a JSON object
   * @return true when at least one item is a JSON object
   */
  private static boolean projectItems(ArrayNode items, Set<String> fields) {
    boolean projected = false;
    for (JsonNode item : items) {
      if (item instanceof ObjectNode objectItem) {
        objectItem.retain(fields);
        projected = true;
      }
    }
    return projected;
  }

  private record SharedSyncToolSpecification(McpSchema.Tool tool,
                                             BiFunction<Object, CallToolRequest, McpSchema.CallToolResult> sharedHandler) {
  }
}

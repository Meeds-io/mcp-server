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

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.BaseStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.util.JsonHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.meeds.mcp.server.model.ListItemKeys;
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
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
public class McpServerUtils {

  private static final Map<String, String> TOOL_RESPONSE_MIME_TYPE = new ConcurrentHashMap<>();

  /**
   * The optional argument every tool answering a list whose item keys are
   * known takes ({@link #itemKeys(ToolCallback)}), unless it declares one of
   * its own with that name: the comma-separated top-level keys to keep in each
   * item of a list result. A key the items don't have is refused
   * ({@link #FIELDS_UNKNOWN_MESSAGE}).
   */
  public static final String               FIELDS_ARGUMENT         = "fields";

  /**
   * The description of the {@link #FIELDS_ARGUMENT} argument, as clients list
   * it, followed by the keys of the tool's items
   * ({@link #FIELDS_KEYS_DESCRIPTION}).
   */
  public static final String               FIELDS_DESCRIPTION      = "Comma-separated top-level keys to keep in each item; omit for all.";

  /**
   * The part of the {@link #FIELDS_ARGUMENT} description that names the keys
   * of the tool's items, comma-separated, in the order they are serialized.
   */
  public static final String               FIELDS_KEYS_DESCRIPTION = " Keys: %s.";

  /**
   * The error a tool call answers when its {@link #FIELDS_ARGUMENT} names a
   * key the tool's items don't have: the unknown names, then the valid keys,
   * each list comma-separated. The tool doesn't run.
   */
  public static final String               FIELDS_UNKNOWN_MESSAGE  =
                                                          "Unknown fields: %s. Valid keys: %s. Call again with valid keys only, or without fields to get every key.";

  /**
   * The keys of which at least one marks a JSON object as a list envelope,
   * whose arrays hold the list's items, both in a tool's result and in its
   * result type, for the {@link #FIELDS_ARGUMENT} argument to be offered: the
   * ones every list collection of the tools carries and a single item never
   * does, unlike {@code count} or {@code next_offset}, which a note read part
   * by part carries.
   */
  private static final Set<String>         LIST_ENVELOPE_KEYS      = Set.of("used_limit", "has_more");

  /**
   * The private field holding the tool method in a tool callback, as both
   * Spring AI's {@code MethodToolCallback} and the server's wrapper of it name
   * it.
   */
  private static final String              TOOL_METHOD_FIELD       = "toolMethod";

  /**
   * Whether a failure to read the tool method of a callback was already
   * logged at warn: it is once, the next ones at debug, since every tool of
   * the server meets the same failure.
   */
  static final AtomicBoolean               TOOL_METHOD_READ_FAILURE_LOGGED = new AtomicBoolean();

  private static final String              PROPERTIES_KEY          = "properties";

  private static final JsonHelper          JSON_HELPER             = new JsonHelper();

  /**
   * The mapper Spring AI's default tool result converter serializes the tool
   * results with, through {@link JsonHelper}: the keys of a tool's items are
   * read with it, so that they are the ones its results carry.
   */
  private static final JsonMapper          RESULT_MAPPER           = JacksonUtils.getDefaultJsonMapper();

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
    // Only a tool answering a list whose item keys are known is offered
    // 'fields'. A tool declaring its own 'fields' argument receives it as is,
    // and its result is not projected
    ListItemKeys listItemKeys = inputSchema == null || declaresProperty(inputSchema, FIELDS_ARGUMENT) ? ListItemKeys.NONE
                                                                                                      : itemKeys(toolCallback);
    boolean projectable = !listItemKeys.isEmpty();
    List<String> itemKeys = listItemKeys.keys();
    Set<String> validKeys = Set.copyOf(itemKeys);
    McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder(toolDefinition.name(),
                                                                projectable ? withFieldsProperty(inputSchema, itemKeys)
                                                                            : inputSchema)
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
          List<String> unknownFields = fields.stream().filter(field -> !validKeys.contains(field)).toList();
          if (!unknownFields.isEmpty()) {
            // Refused before the tool runs, as an input schema refusal is: a
            // list shortened to the known keys would be read as the whole
            // answer. The tool's trace never sees the refused call
            log.debug("Tool '{}' called with unknown fields {}", toolDefinition.name(), unknownFields);
            return McpSchema.CallToolResult.builder()
                                           .content(List.of(McpSchema.TextContent.builder(FIELDS_UNKNOWN_MESSAGE.formatted(String.join(", ",
                                                                                                                                       unknownFields),
                                                                                                                           String.join(", ",
                                                                                                                                       itemKeys)))
                                                                                 .build()))
                                           .isError(true)
                                           .build();
          }
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
                                       .content(List.of(McpSchema.TextContent.builder(projectFields(callResult,
                                                                                                         fields,
                                                                                                         listItemKeys.listProperty()))
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
   * input schema, described with the keys of the tool's items, without
   * changing the schema it was given nor its required properties.
   *
   * @param inputSchema the tool's input schema
   * @param itemKeys    the keys of the tool's items
   *                      ({@link #itemKeys(Type)})
   * @return a copy of the schema declaring the property
   */
  static Map<String, Object> withFieldsProperty(Map<String, Object> inputSchema, List<String> itemKeys) {
    Map<String, Object> schema = new LinkedHashMap<>(inputSchema);
    Map<String, Object> properties = new LinkedHashMap<>();
    if (schema.get(PROPERTIES_KEY) instanceof Map<?, ?> declaredProperties) {
      declaredProperties.forEach((name, value) -> properties.put(String.valueOf(name), value));
    }
    Map<String, Object> fieldsProperty = new LinkedHashMap<>();
    fieldsProperty.put("type", "string");
    fieldsProperty.put("description", FIELDS_DESCRIPTION + FIELDS_KEYS_DESCRIPTION.formatted(String.join(", ", itemKeys)));
    properties.put(FIELDS_ARGUMENT, fieldsProperty);
    schema.put(PROPERTIES_KEY, properties);
    return schema;
  }

  /**
   * Reads the keys of the items of the lists a tool answers, from the return
   * type of its method ({@link #itemKeys(Type)}). A tool whose method is not
   * known has none.
   *
   * @param toolCallback the tool callback
   * @return the keys of its items and where its results hold them,
   *         {@link ListItemKeys#NONE} when it answers no list or when they are
   *         not known
   */
  static ListItemKeys itemKeys(ToolCallback toolCallback) {
    Method toolMethod = getToolMethod(toolCallback);
    return toolMethod == null ? ListItemKeys.NONE : itemKeys(toolMethod.getGenericReturnType());
  }

  /**
   * Reads the keys of the items of a list type ({@link #isListType(Type)}), as
   * {@link #RESULT_MAPPER} serializes them, in that order: those of the
   * element type of an array or an {@link Iterable}, a top-level JSON array of
   * the results, or, for a list envelope, those of the element type of its one
   * array or collection property holding objects, its arrays of plain values
   * ({@link #isSimpleValueType}) left out, along with the property's name: only
   * that property's items are projected ({@link #projectFields}). The keys are
   * not known when an item type is not a concrete class with a fixed set of
   * properties (a map, a raw or a wildcard type, {@link Object}, an abstract
   * class or an interface, a JSON node, a type serialized by a custom
   * serializer or adding keys through a {@link JsonAnyGetter}), or when an
   * envelope holds any other property that may be written as an array of
   * objects ({@link #mayBeObjectArray}), since the items to project are then
   * not known. The keys are read from the declared item type: a concrete item
   * type whose subclasses add properties is not told apart, a key only a
   * subclass carries being refused.
   *
   * @param listType a tool method's return type
   * @return the keys of the type's items and where its results hold them,
   *         {@link ListItemKeys#NONE} when it is not a list type or when they
   *         are not known
   */
  static ListItemKeys itemKeys(Type listType) {
    if (!isListType(listType)) {
      return ListItemKeys.NONE;
    }
    Class<?> type = rawClass(listType);
    if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
      Type elementType = elementType(listType);
      return elementType == null ? ListItemKeys.NONE
                                 : new ListItemKeys(null, objectKeys(RESULT_MAPPER.constructType(elementType)));
    }
    Map<String, JavaType> envelopeProperties = objectProperties(RESULT_MAPPER.constructType(listType));
    if (envelopeProperties == null) {
      return ListItemKeys.NONE;
    }
    List<Map.Entry<String, JavaType>> objectArrays = envelopeProperties.entrySet()
                                                                       .stream()
                                                                       .filter(property -> mayBeObjectArray(property.getValue()))
                                                                       .toList();
    if (objectArrays.size() != 1) {
      return ListItemKeys.NONE;
    }
    JavaType arrayType = objectArrays.get(0).getValue();
    if (!arrayType.isArrayType() && !arrayType.isCollectionLikeType()) {
      return ListItemKeys.NONE;
    }
    List<String> keys = objectKeys(arrayType.getContentType());
    return keys.isEmpty() ? ListItemKeys.NONE : new ListItemKeys(objectArrays.get(0).getKey(), keys);
  }

  /**
   * @param type the type of a list envelope's property
   * @return true when {@link #RESULT_MAPPER} may write the property as a JSON
   *         array whose elements are not plain values
   *         ({@link #isSimpleValueType}): an array or a collection of such
   *         elements or of unknown ones, and, whatever their elements, an
   *         {@link Iterable}, a JSON node included, an {@link Iterator}, a
   *         {@link BaseStream} or an {@link Object}, also when an
   *         {@link java.util.Optional} or another reference type holds it
   */
  private static boolean mayBeObjectArray(JavaType type) {
    if (type.isReferenceType()) {
      return mayBeObjectArray(type.getReferencedType());
    }
    if (type.isArrayType() || type.isCollectionLikeType()) {
      JavaType contentType = type.getContentType();
      return contentType == null || !ClassUtils.isSimpleValueType(contentType.getRawClass());
    }
    Class<?> rawType = type.getRawClass();
    return rawType == Object.class
        || Iterable.class.isAssignableFrom(rawType)
        || Iterator.class.isAssignableFrom(rawType)
        || BaseStream.class.isAssignableFrom(rawType);
  }

  /**
   * @param type an item type, may be null
   * @return the keys {@link #RESULT_MAPPER} serializes the type with, empty
   *         when it is not a concrete class with a fixed set of properties
   *         ({@link #itemKeys(Type)})
   */
  private static List<String> objectKeys(JavaType type) {
    if (type == null
        || type.getRawClass().isInterface()
        || Modifier.isAbstract(type.getRawClass().getModifiers())
        || hasAnyGetter(type.getRawClass())) {
      return List.of();
    }
    Map<String, JavaType> properties = objectProperties(type);
    return properties == null ? List.of() : List.copyOf(properties.keySet());
  }

  /**
   * Lists the properties {@link #RESULT_MAPPER} serializes a type with,
   * through its JSON format visitor, which honours the JSON names, the ignored
   * and the included properties as the serialization does.
   *
   * @param type a type
   * @return by JSON name, in the order they are serialized, the type of each
   *         property, null when the type isn't serialized as a JSON object
   *         with properties, as a map, a collection, {@link Object}, a JSON
   *         node or a custom serializer are not
   */
  private static Map<String, JavaType> objectProperties(JavaType type) {
    Map<String, JavaType> properties = new LinkedHashMap<>();
    AtomicBoolean isObject = new AtomicBoolean();
    try {
      RESULT_MAPPER.acceptJsonFormatVisitor(type, new JsonFormatVisitorWrapper.Base() {
        /**
         * Marks the type as written as a JSON object and collects its
         * properties.
         *
         * @param objectType the type visited
         * @return the visitor of its properties
         */
        @Override
        public JsonObjectFormatVisitor expectObjectFormat(JavaType objectType) {
          isObject.set(true);
          return new JsonObjectFormatVisitor.Base(getContext()) {
            /**
             * Collects a required property.
             *
             * @param property the property
             */
            @Override
            public void property(BeanProperty property) {
              properties.put(property.getName(), property.getType());
            }

            /**
             * Collects an optional property.
             *
             * @param property the property
             */
            @Override
            public void optionalProperty(BeanProperty property) {
              properties.put(property.getName(), property.getType());
            }
          };
        }
      });
    } catch (RuntimeException e) {
      log.debug("Can't read the properties of type {}: the tools answering it are offered no '{}' argument",
                type,
                FIELDS_ARGUMENT,
                e);
      return null; // NOSONAR null tells a type that is no object
    }
    return isObject.get() && !properties.isEmpty() ? properties : null; // NOSONAR
  }

  /**
   * @param type a class, may be null
   * @return true when the class or a superclass declares a field or a method
   *         annotated with {@link JsonAnyGetter}, whose keys vary per instance
   */
  private static boolean hasAnyGetter(Class<?> type) {
    for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
      if (Stream.<AnnotatedElement>concat(Stream.of(current.getDeclaredFields()), Stream.of(current.getDeclaredMethods()))
                .anyMatch(member -> member.isAnnotationPresent(JsonAnyGetter.class))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads the tool method of a callback from its {@link #TOOL_METHOD_FIELD}
   * field, declared by its class or a superclass, as
   * {@link McpToolUtils#getMethodToolFieldValue} reads Spring AI's callback,
   * which exposes no accessor for it.
   *
   * A field of that name that can't be read as a method is a failure, logged
   * at warn the first time only ({@link #TOOL_METHOD_READ_FAILURE_LOGGED}): the
   * tool is then listed without {@link #FIELDS_ARGUMENT}.
   *
   * @param toolCallback the tool callback
   * @return the method the callback runs, or null when it holds none
   */
  static Method getToolMethod(ToolCallback toolCallback) {
    for (Class<?> type = toolCallback.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(TOOL_METHOD_FIELD);
        if (!Method.class.isAssignableFrom(field.getType())) {
          logToolMethodReadFailure(toolCallback, new IllegalStateException("Field '%s' of %s is a %s".formatted(TOOL_METHOD_FIELD,
                                                                                                               type.getName(),
                                                                                                               field.getType()
                                                                                                                    .getName())));
          return null;
        }
        field.setAccessible(true); // NOSONAR
        return (Method) field.get(toolCallback);
      } catch (NoSuchFieldException e) {
        // Declared by a superclass, if any
      } catch (IllegalAccessException | RuntimeException e) {
        logToolMethodReadFailure(toolCallback, e);
        return null;
      }
    }
    return null;
  }

  /**
   * Logs a failure to read the tool method of a callback: at warn the first
   * time, at debug afterwards.
   *
   * @param toolCallback the tool callback
   * @param e            the failure
   */
  private static void logToolMethodReadFailure(ToolCallback toolCallback, Exception e) {
    if (TOOL_METHOD_READ_FAILURE_LOGGED.compareAndSet(false, true)) {
      log.warn("Can't read the tool method of tool callback {}: the list tools are offered no '{}' argument. Next failures are logged at debug",
               toolCallback.getClass(),
               FIELDS_ARGUMENT,
               e);
    } else {
      log.debug("Can't read the tool method of tool callback {}", toolCallback.getClass(), e);
    }
  }

  /**
   * @param genericType a tool method's return type
   * @return true when the type is an array or an {@link Iterable} whose
   *         elements are not simple values ({@link #isSimpleValueType}), or
   *         whose element type is not known, or declares, in itself or a
   *         superclass, a field whose JSON name, its {@link JsonProperty}
   *         value or else its name, is one of {@link #LIST_ENVELOPE_KEYS}
   */
  static boolean isListType(Type genericType) {
    Class<?> type = rawClass(genericType);
    if (type == null) {
      return false;
    }
    if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
      return !isSimpleValueType(elementType(genericType));
    }
    for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);
        String name = jsonProperty == null || StringUtils.isBlank(jsonProperty.value()) ? field.getName() : jsonProperty.value();
        if (LIST_ENVELOPE_KEYS.contains(name)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * @param genericType a type
   * @return its class, or null when it is a type variable or a wildcard
   */
  private static Class<?> rawClass(Type genericType) {
    if (genericType instanceof Class<?> type) {
      return type;
    } else if (genericType instanceof ParameterizedType parameterizedType
        && parameterizedType.getRawType() instanceof Class<?> type) {
      return type;
    } else if (genericType instanceof GenericArrayType arrayType) {
      Class<?> componentType = rawClass(arrayType.getGenericComponentType());
      return componentType == null ? Object[].class : componentType.arrayType();
    } else {
      return null;
    }
  }

  /**
   * @param listType an array or an {@link Iterable} type
   * @return the type of its elements: the component type of an array, the
   *         only type argument of a parameterized {@link Iterable}, null when
   *         it is not known, as for a raw type
   */
  private static Type elementType(Type listType) {
    if (listType instanceof Class<?> type && type.isArray()) {
      return type.getComponentType();
    } else if (listType instanceof GenericArrayType arrayType) {
      return arrayType.getGenericComponentType();
    } else if (listType instanceof ParameterizedType parameterizedType
        && parameterizedType.getActualTypeArguments().length == 1) {
      return parameterizedType.getActualTypeArguments()[0];
    } else {
      return null;
    }
  }

  /**
   * @param elementType the type of a list's elements, may be null
   * @return true when the elements are plain values that hold no keys to keep:
   *         primitives, their wrappers, strings, enums, dates and the other
   *         types {@link ClassUtils#isSimpleValueType} names; false when the
   *         type is not known
   */
  private static boolean isSimpleValueType(Type elementType) {
    return elementType instanceof Class<?> type && ClassUtils.isSimpleValueType(type);
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
   * Keeps only the requested keys in each item of a JSON list result. Without
   * a list property, the elements of a top-level JSON array are projected.
   * With one, in a list envelope, a JSON object carrying at least one of
   * {@link #LIST_ENVELOPE_KEYS}, each element of the array that property holds
   * is projected and every other top-level key is kept as it is, other arrays
   * included. A requested key an item lacks stays absent. Any other JSON
   * object is a single item and is returned whole, its arrays included, as is
   * any other result, a non-JSON text or one that doesn't parse included.
   *
   * @param result       the tool's result
   * @param fields       the keys to keep, empty to keep every key
   * @param listProperty the envelope property holding the items, null when
   *                       they are a top-level JSON array
   *                       ({@link ListItemKeys#listProperty()})
   * @return the projected result, the same instance when nothing was projected
   */
  static String projectFields(String result, Set<String> fields, String listProperty) {
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
    if (listProperty == null) {
      projected = node instanceof ArrayNode items && projectItems(items, fields);
    } else if (node instanceof ObjectNode envelope
        && isListEnvelope(envelope)
        && envelope.get(listProperty) instanceof ArrayNode items) {
      projected = projectItems(items, fields);
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

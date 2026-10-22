/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2026 Meeds Association contact@meeds.io
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.meeds.mcp.server.service.McpToolCallbackProviderService.MethodToolCallbackWrapper;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpServerUtilsTest {

  private static final String     TOOL_NAME     = "list_things";

  private static final String     SCHEMA        = """
      {"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}""";

  private static final String     ENVELOPE      = """
      {"things":[{"id":1,"title":"a","description":"long"},{"id":2,"title":"b","description":"longer"}],      "count":2,"used_offset":0,"used_limit":2,"has_more":true,"next_offset":2,"score":1.10}""";

  private static final JsonMapper JSON_MAPPER   = JsonMapper.builder().build();

  /**
   * A tool that declares no {@code fields} argument is listed with an optional
   * string one, and keeps its own properties and required ones.
   */
  @Test
  void listsAToolWithAnOptionalFieldsArgument() {
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback(SCHEMA, "[]"), null);

    Map<String, Object> inputSchema = specification.tool().inputSchema();
    Map<?, ?> properties = (Map<?, ?>) inputSchema.get("properties");
    assertEquals(Set.of("query", "limit", "fields"), properties.keySet());
    Map<?, ?> fields = (Map<?, ?>) properties.get("fields");
    assertEquals("string", fields.get("type"));
    assertEquals(McpServerUtils.FIELDS_DESCRIPTION, fields.get("description"));
    assertEquals(List.of("query"), inputSchema.get("required"));
  }

  /**
   * A tool without any property gets the {@code fields} one too.
   */
  @Test
  void listsAToolWithoutPropertiesWithAFieldsArgument() {
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback("{\"type\":\"object\"}", "[]"),
                                                                                  null);

    Map<?, ?> properties = (Map<?, ?>) specification.tool().inputSchema().get("properties");
    assertEquals(Set.of("fields"), properties.keySet());
  }

  /**
   * A tool that declares its own {@code fields} argument keeps its
   * declaration, receives the argument and has its result untouched.
   */
  @Test
  void keepsTheFieldsArgumentOfAToolThatDeclaresOne() {
    String ownSchema = """
        {"type":"object","properties":{"fields":{"type":"array","description":"the tool's own"}}}""";
    ToolCallback toolCallback = toolCallback(ownSchema, ENVELOPE);
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);

    Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) specification.tool().inputSchema().get("properties")).get("fields");
    assertEquals("the tool's own", fields.get("description"));

    CallToolResult result = call(specification, Map.of("fields", "id"));

    assertTrue(captureToolInput(toolCallback).contains("\"fields\""));
    assertEquals(ENVELOPE, ((TextContent) result.content().get(0)).text());
  }

  /**
   * The tool runs with the arguments it was called with, less {@code fields}:
   * its approval, grant and space scope checks never see it.
   */
  @Test
  void removesTheFieldsArgumentBeforeTheToolRuns() {
    ToolCallback toolCallback = toolCallback(SCHEMA, ENVELOPE);
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("query", "q");
    arguments.put("limit", 5);
    arguments.put("fields", "id,title");

    call(specification, arguments);

    JsonNode toolInput = JSON_MAPPER.readTree(captureToolInput(toolCallback));
    assertEquals(Set.of("query", "limit"), Set.copyOf(toolInput.propertyNames()));
    assertEquals("q", toolInput.get("query").asString());
    assertEquals(5, toolInput.get("limit").asInt());
  }

  /**
   * In a JSON object result, each item of every top-level list keeps only the
   * requested keys, and every other top-level key is kept.
   */
  @Test
  void projectsTheItemsAndKeepsTheEnvelope() {
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback(SCHEMA, ENVELOPE), null);

    CallToolResult result = call(specification, Map.of("query", "q", "fields", " id , title,unknown"));

    assertFalse(result.isError());
    String text = ((TextContent) result.content().get(0)).text();
    assertTrue(text.contains("\"score\":1.10"), text);
    JsonNode projected = JSON_MAPPER.readTree(text);
    assertEquals(Set.of("things", "count", "used_offset", "used_limit", "has_more", "next_offset", "score"),
                 Set.copyOf(projected.propertyNames()));
    assertEquals(2, projected.get("count").asInt());
    assertTrue(projected.get("has_more").asBoolean());
    assertEquals(2, projected.get("next_offset").asInt());
    assertEquals(2, projected.get("things").size());
    for (JsonNode item : projected.get("things")) {
      assertEquals(Set.of("id", "title"), Set.copyOf(item.propertyNames()));
    }
  }

  /**
   * A single item, a JSON object carrying none of the list envelope keys, is
   * returned whole, its lists included: get_project_by_id keeps its statuses
   * and labels when called with {@code fields}.
   */
  @Test
  void leavesTheListsOfASingleItemUntouched() {
    String project = """
        {"id":2,"name":"Board","allowed_status_list":[{"id":1,"name":"ToDo"},{"id":2,"name":"Done"}],\
        "allowed_label_list":[{"id":3,"color":"red"}],"managers":["root"]}""";
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback(SCHEMA, project), null);

    CallToolResult result = call(specification, Map.of("query", "q", "fields", "name"));

    assertEquals(project, ((TextContent) result.content().get(0)).text());
    assertSame(project, McpServerUtils.projectFields(project, Set.of("name")));
  }

  /**
   * One envelope key is enough to make a JSON object a list: a collection
   * carrying only {@code used_limit} has its items projected.
   */
  @Test
  void projectsTheItemsOfAnEnvelopeCarryingOneEnvelopeKey() {
    String projected = McpServerUtils.projectFields("{\"tasks\":[{\"id\":1,\"title\":\"a\"}],\"used_limit\":20}",
                                                    Set.of("id"));

    assertEquals("{\"tasks\":[{\"id\":1}],\"used_limit\":20}", projected);
  }

  /**
   * A top-level JSON array has its items projected.
   */
  @Test
  void projectsTheItemsOfATopLevelArray() {
    String projected = McpServerUtils.projectFields("[{\"id\":1,\"title\":\"a\"},{\"id\":2,\"title\":\"b\"},\"text\"]",
                                                    Set.of("id"));

    assertEquals("[{\"id\":1},{\"id\":2},\"text\"]", projected);
  }

  /**
   * Without {@code fields}, the result is returned as the tool wrote it.
   */
  @Test
  void returnsTheResultAsIsWithoutFields() {
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback(SCHEMA, ENVELOPE), null);

    CallToolResult result = call(specification, Map.of("query", "q"));

    assertEquals(ENVELOPE, ((TextContent) result.content().get(0)).text());
  }

  /**
   * A result that is not a JSON object or array, or doesn't parse, or holds
   * no list, is returned as is.
   */
  @Test
  void leavesAnyOtherResultUntouched() {
    Set<String> fields = Set.of("id");
    String text = "Activity deleted";
    String quoted = "\"a JSON string\"";
    String truncated = "{\"things\":[{\"id\":1,\"title\":\"a\"}";
    String trailing = "{\"things\":[{\"id\":1,\"title\":\"a\"}]} and more";
    String single = "{\"id\":1,\"title\":\"a\"}";

    assertSame(text, McpServerUtils.projectFields(text, fields));
    assertSame(quoted, McpServerUtils.projectFields(quoted, fields));
    assertSame(truncated, McpServerUtils.projectFields(truncated, fields));
    assertSame(trailing, McpServerUtils.projectFields(trailing, fields));
    assertSame(single, McpServerUtils.projectFields(single, fields));
  }

  /**
   * An image result is returned as the tool wrote it, whatever the requested
   * fields.
   */
  @Test
  void leavesAnImageResultUntouched() {
    String base64 = "iVBORw0KGgo=";
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback(SCHEMA, base64),
                                                                                  MimeType.valueOf("image/png"));

    CallToolResult result = call(specification, Map.of("query", "q", "fields", "id"));

    assertEquals(base64, ((ImageContent) result.content().get(0)).data());
  }

  /**
   * A tool answering a single item is listed without {@code fields}, runs with
   * the arguments it was called with and has its result untouched.
   */
  @Test
  void listsAToolAnsweringASingleItemWithoutFieldsArgument() {
    ToolCallback toolCallback = toolCallback(SCHEMA, ENVELOPE, "getThing");
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);

    Map<?, ?> properties = (Map<?, ?>) specification.tool().inputSchema().get("properties");
    assertEquals(Set.of("query", "limit"), properties.keySet());

    CallToolResult result = call(specification, Map.of("query", "q", "fields", "id"));

    assertTrue(captureToolInput(toolCallback).contains("\"fields\""));
    assertEquals(ENVELOPE, ((TextContent) result.content().get(0)).text());
  }

  /**
   * A tool whose method is not known is listed without {@code fields}.
   */
  @Test
  void listsAToolOfAnUnknownMethodWithoutFieldsArgument() {
    ToolCallback toolCallback = mock(ToolCallback.class);
    when(toolCallback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                                                                            .name(TOOL_NAME)
                                                                            .description("Lists things")
                                                                            .inputSchema(SCHEMA)
                                                                            .build());

    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);

    Map<?, ?> properties = (Map<?, ?>) specification.tool().inputSchema().get("properties");
    assertEquals(Set.of("query", "limit"), properties.keySet());
    assertFalse(McpServerUtils.returnsList(toolCallback));
  }

  /**
   * A list is an array, an {@link Iterable}, or a type declaring
   * {@code used_limit} or {@code has_more}, by its JSON name, in itself or a
   * superclass; a single item paging its own content with
   * {@code next_offset}, or carrying a {@code count}, is not.
   */
  @Test
  void tellsAListTypeFromASingleItemType() {
    assertTrue(McpServerUtils.isListType(Thing[].class));
    assertTrue(McpServerUtils.isListType(List.class));
    assertTrue(McpServerUtils.isListType(ThingCollection.class));
    assertTrue(McpServerUtils.isListType(ThingPage.class));
    assertTrue(McpServerUtils.isListType(SubThingPage.class));
    assertTrue(McpServerUtils.isListType(UnannotatedPage.class));
    assertFalse(McpServerUtils.isListType(Thing.class));
    assertFalse(McpServerUtils.isListType(String.class));
    assertFalse(McpServerUtils.isListType(void.class));
  }

  /**
   * The requested keys are read from a comma-separated string or a list, the
   * blank ones ignored.
   */
  @Test
  void parsesTheRequestedFields() {
    assertEquals(Set.of("id", "title"), McpServerUtils.parseFields(" id,, title ,"));
    assertEquals(Set.of("id", "title"), McpServerUtils.parseFields(List.of("id", " title")));
    assertTrue(McpServerUtils.parseFields(null).isEmpty());
    assertTrue(McpServerUtils.parseFields(" , ").isEmpty());
    assertTrue(McpServerUtils.parseFields(12).isEmpty());
  }

  /**
   * @param inputSchema the tool's input schema
   * @param result      what the tool returns
   * @return a tool callback answering that result to any call
   */
  private static ToolCallback toolCallback(String inputSchema, String result) {
    return toolCallback(inputSchema, result, "listThings");
  }

  /**
   * @param inputSchema the tool's input schema
   * @param result      what the tool returns
   * @param methodName  the {@link Tools} method the tool runs
   * @return a tool callback of that method answering that result to any call
   */
  private static ToolCallback toolCallback(String inputSchema, String result, String methodName) {
    MethodToolCallbackWrapper toolCallback = mock(MethodToolCallbackWrapper.class);
    when(toolCallback.getToolMethod()).thenReturn(toolMethod(methodName));
    when(toolCallback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                                                                            .name(TOOL_NAME)
                                                                            .description("Lists things")
                                                                            .inputSchema(inputSchema)
                                                                            .build());
    when(toolCallback.call(anyString(), any(ToolContext.class))).thenReturn(result);
    return toolCallback;
  }

  /**
   * @param specification the tool specification
   * @param arguments     the call arguments
   * @return the result of calling the tool through its MCP call handler
   */
  private static CallToolResult call(SyncToolSpecification specification, Map<String, Object> arguments) {
    return specification.callHandler().apply(mock(McpSyncServerExchange.class), new CallToolRequest(TOOL_NAME, arguments));
  }

  /**
   * @param toolCallback the tool callback
   * @return the arguments, as JSON, the tool was called with
   */
  private static String captureToolInput(ToolCallback toolCallback) {
    ArgumentCaptor<String> toolInput = ArgumentCaptor.forClass(String.class);
    verify(toolCallback).call(toolInput.capture(), any(ToolContext.class));
    return toolInput.getValue();
  }

  /**
   * @param methodName a {@link Tools} method name
   * @return that method
   */
  private static Method toolMethod(String methodName) {
    return Stream.of(Tools.class.getDeclaredMethods())
                 .filter(method -> method.getName().equals(methodName))
                 .findFirst()
                 .orElseThrow();
  }

  /**
   * The tool methods the tests' callbacks run.
   */
  @SuppressWarnings("unused")
  private static class Tools {

    /**
     * @return a list envelope
     */
    ThingCollection listThings() {
      return null;
    }

    /**
     * @return a single item
     */
    Thing getThing() {
      return null;
    }
  }

  /**
   * A single item: a note read part by part carries {@code next_offset}, a
   * statistic a {@code count}.
   *
   * @param id         the item id
   * @param count      a count of the item
   * @param nextOffset where its next part starts
   * @param parts      its parts
   */
  private record Thing(long id, long count, @JsonProperty("next_offset") Integer nextOffset, List<String> parts) {
  }

  /**
   * A list envelope declaring {@code used_limit}.
   *
   * @param things    the items
   * @param usedLimit the limit applied
   */
  private record ThingCollection(List<Thing> things, @JsonProperty("used_limit") int usedLimit) {
  }

  /**
   * A list envelope declaring {@code has_more}.
   */
  @SuppressWarnings("unused")
  private static class ThingPage {

    private List<Thing> things;

    @JsonProperty("has_more")
    private boolean     hasMore;
  }

  /**
   * A list envelope through its superclass.
   */
  private static class SubThingPage extends ThingPage {
  }

  /**
   * A list envelope whose field bears the JSON name without annotation.
   */
  @SuppressWarnings("unused")
  private static class UnannotatedPage {

    private boolean has_more; // NOSONAR the JSON name, as the rule reads it
  }
}

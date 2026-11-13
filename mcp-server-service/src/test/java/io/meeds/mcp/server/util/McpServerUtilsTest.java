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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.meeds.mcp.server.service.McpServerToolService;
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
      {"things":[{"id":1,"count":3,"parts":["long"]},{"id":2,"count":4,"parts":["longer"]}],      "count":2,"used_offset":0,"used_limit":2,"has_more":true,"next_offset":2,"score":1.10}""";

  private static final JsonMapper JSON_MAPPER   = JsonMapper.builder().build();

  /**
   * A tool that declares no {@code fields} argument is listed with an optional
   * string one, described with the keys of its items in the order its results
   * carry them, and keeps its own properties and required ones.
   */
  @Test
  void listsAToolWithAnOptionalFieldsArgument() {
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback(SCHEMA, "[]"), null);

    Map<String, Object> inputSchema = specification.tool().inputSchema();
    Map<?, ?> properties = (Map<?, ?>) inputSchema.get("properties");
    assertEquals(Set.of("query", "limit", "fields"), properties.keySet());
    Map<?, ?> fields = (Map<?, ?>) properties.get("fields");
    assertEquals("string", fields.get("type"));
    assertEquals(McpServerUtils.FIELDS_DESCRIPTION + " Keys: " + String.join(", ", serializedThingKeys()) + ".",
                 fields.get("description"));
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
    arguments.put("fields", "id,count");

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

    CallToolResult result = call(specification, Map.of("query", "q", "fields", " id , count"));

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
      assertEquals(Set.of("id", "count"), Set.copyOf(item.propertyNames()));
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

    CallToolResult result = call(specification, Map.of("query", "q", "fields", "id"));

    assertEquals(project, ((TextContent) result.content().get(0)).text());
    assertSame(project, McpServerUtils.projectFields(project, Set.of("name"), "allowed_status_list"));
  }

  /**
   * One envelope key is enough to make a JSON object a list: a collection
   * carrying only {@code used_limit} has its items projected.
   */
  @Test
  void projectsTheItemsOfAnEnvelopeCarryingOneEnvelopeKey() {
    String projected = McpServerUtils.projectFields("{\"tasks\":[{\"id\":1,\"title\":\"a\"}],\"used_limit\":20}",
                                                    Set.of("id"),
                                                    "tasks");

    assertEquals("{\"tasks\":[{\"id\":1}],\"used_limit\":20}", projected);
  }

  /**
   * A JSON object carrying only {@code count} or {@code next_offset}, such as
   * a note read part by part, is a single item: it is returned whole, its
   * arrays included.
   */
  @Test
  void leavesAnObjectWithoutListEnvelopeKeyUntouched() {
    String notePart = "{\"note_id\":1,\"breadcrumb\":[{\"id\":1,\"title\":\"a\"}],\"next_offset\":40000}";
    String counted = "{\"labels\":[{\"id\":1,\"title\":\"a\"}],\"count\":1,\"used_offset\":0}";

    assertSame(notePart, McpServerUtils.projectFields(notePart, Set.of("id"), "breadcrumb"));
    assertSame(counted, McpServerUtils.projectFields(counted, Set.of("id"), "labels"));
  }

  /**
   * The tool method is read from the server's wrapper and from Spring AI's
   * callback; a callback holding none has no method.
   */
  @Test
  void readsTheToolMethodOfACallback() {
    MethodToolCallbackWrapper toolCallback = (MethodToolCallbackWrapper) toolCallback(SCHEMA, "[]", "getThing");

    assertEquals(toolMethod("getThing"), McpServerUtils.getToolMethod(toolCallback));
    assertEquals(toolMethod("getThing"),
                 McpServerUtils.getToolMethod(MethodToolCallback.builder()
                                                                .toolDefinition(toolCallback.getToolDefinition())
                                                                .toolMethod(toolMethod("getThing"))
                                                                .toolObject(new Tools())
                                                                .build()));
    assertEquals(null, McpServerUtils.getToolMethod(mock(ToolCallback.class)));
  }

  /**
   * A tool method that can't be read is logged at warn the first time, so
   * that the list tools losing {@code fields} is seen, and at debug after.
   */
  @Test
  void warnsOnceWhenTheToolMethodCanNotBeRead() {
    Logger logger = (Logger) LoggerFactory.getLogger(McpServerUtils.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    McpServerUtils.TOOL_METHOD_READ_FAILURE_LOGGED.set(false);
    try {
      assertEquals(null, McpServerUtils.getToolMethod(new UnreadableToolCallback()));
      assertEquals(null, McpServerUtils.getToolMethod(new UnreadableToolCallback()));

      assertEquals(1, appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count());
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * A tool answering a list of plain values, which hold no keys to keep, or a
   * raw list, whose keys are not known, is listed without {@code fields}; one
   * answering a list of objects is listed with it.
   */
  @Test
  void offersFieldsOnAListOfObjectsOnly() {
    assertEquals(Set.of("query", "limit"), listedProperties(toolCallback(SCHEMA, "[]", "listNames")));
    assertEquals(Set.of("query", "limit"), listedProperties(toolCallback(SCHEMA, "[]", "listCodes")));
    assertEquals(Set.of("query", "limit", "fields"), listedProperties(toolCallback(SCHEMA, "[]", "listThingItems")));
    assertEquals(Set.of("query", "limit"), listedProperties(toolCallback(SCHEMA, "[]", "listRawItems")));
    assertFalse(McpServerUtils.isListType(String[].class));
    assertFalse(McpServerUtils.isListType(int[].class));
    assertTrue(McpServerUtils.isListType(toolMethod("listThingItems").getGenericReturnType()));
  }

  /**
   * A top-level JSON array has its items projected.
   */
  @Test
  void projectsTheItemsOfATopLevelArray() {
    String array = "[{\"id\":1,\"title\":\"a\"},{\"id\":2,\"title\":\"b\"},\"text\"]";

    assertEquals("[{\"id\":1},{\"id\":2},\"text\"]", McpServerUtils.projectFields(array, Set.of("id"), null));
    assertSame(array, McpServerUtils.projectFields(array, Set.of("id"), "things"));
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

    assertSame(text, McpServerUtils.projectFields(text, fields, "things"));
    assertSame(quoted, McpServerUtils.projectFields(quoted, fields, "things"));
    assertSame(truncated, McpServerUtils.projectFields(truncated, fields, "things"));
    assertSame(trailing, McpServerUtils.projectFields(trailing, fields, "things"));
    assertSame(single, McpServerUtils.projectFields(single, fields, "things"));
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
    assertTrue(McpServerUtils.itemKeys(toolCallback).isEmpty());
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
   * A call naming a key the items don't have is refused before the tool runs,
   * alone or with known keys, with a message naming the unknown keys and the
   * valid ones, instead of answering a list shortened to the known keys.
   */
  @Test
  void refusesUnknownFieldsBeforeTheToolRuns() {
    ToolCallback toolCallback = toolCallback(SCHEMA, ENVELOPE);
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);
    String validKeys = String.join(", ", serializedThingKeys());

    CallToolResult mixed = call(specification, Map.of("query", "q", "fields", "id,title, posted_time"));
    CallToolResult alone = call(specification, Map.of("query", "q", "fields", "title"));

    assertTrue(mixed.isError());
    assertEquals(McpServerUtils.FIELDS_UNKNOWN_MESSAGE.formatted("title, posted_time", validKeys),
                 ((TextContent) mixed.content().get(0)).text());
    assertTrue(alone.isError());
    assertEquals(McpServerUtils.FIELDS_UNKNOWN_MESSAGE.formatted("title", validKeys),
                 ((TextContent) alone.content().get(0)).text());
    verify(toolCallback, never()).call(anyString(), any(ToolContext.class));
  }

  /**
   * The keys of an envelope's items are their JSON names, those of its list of
   * objects, its lists of plain values left out. A key holding a nested object
   * keeps it whole, and a dotted key inside it is refused.
   */
  @Test
  void projectsTheTopLevelKeysOfTheItemsByTheirJsonNames() {
    String posts = """
        {"posts":[{"activity_id":1,"content":"c","author":{"name":"root","url":"u"}}],"tags":["a"],"has_more":false}""";
    ToolCallback toolCallback = toolCallback(SCHEMA, posts, "listPosts");
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);

    assertEquals("posts", McpServerUtils.itemKeys(toolCallback).listProperty());
    List<String> itemKeys = McpServerUtils.itemKeys(toolCallback).keys();
    assertEquals(Set.of("activity_id", "content", "author"), Set.copyOf(itemKeys));
    assertEquals(3, itemKeys.size());
    Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) specification.tool().inputSchema().get("properties")).get("fields");
    assertEquals(McpServerUtils.FIELDS_DESCRIPTION + " Keys: " + String.join(", ", itemKeys) + ".", fields.get("description"));

    CallToolResult projected = call(specification, Map.of("query", "q", "fields", "activity_id,author"));
    JsonNode post = JSON_MAPPER.readTree(((TextContent) projected.content().get(0)).text()).get("posts").get(0);
    assertEquals(Set.of("activity_id", "author"), Set.copyOf(post.propertyNames()));
    assertEquals("root", post.get("author").get("name").asString());
    assertEquals("u", post.get("author").get("url").asString());

    CallToolResult nested = call(specification, Map.of("query", "q", "fields", "activity_id,author.name"));
    assertTrue(nested.isError());
    assertEquals(McpServerUtils.FIELDS_UNKNOWN_MESSAGE.formatted("author.name", String.join(", ", itemKeys)),
                 ((TextContent) nested.content().get(0)).text());
  }

  /**
   * Only the envelope's list the item keys were read from is projected: any
   * other array of the envelope keeps its items whole.
   */
  @Test
  void projectsOnlyTheListOfTheItemKeys() {
    String posts = """
        {"posts":[{"activity_id":1,"content":"c"}],"authors":[{"name":"root","url":"u"}],"has_more":false}""";

    JsonNode projected = JSON_MAPPER.readTree(McpServerUtils.projectFields(posts, Set.of("activity_id"), "posts"));

    assertEquals(Set.of("activity_id"), Set.copyOf(projected.get("posts").get(0).propertyNames()));
    assertEquals(Set.of("name", "url"), Set.copyOf(projected.get("authors").get(0).propertyNames()));
  }

  /**
   * A tool whose items have no fixed set of keys is listed without
   * {@code fields}: items that are maps, objects, JSON nodes or carry keys of
   * their own through a {@link JsonAnyGetter}, an envelope holding such
   * items, an abstract or an interface item type, or an envelope holding
   * another property that may be written as an array of objects, a list, an
   * {@link Iterable}, an {@link Iterator}, a {@link Stream}, an optional list
   * or an {@link Object}, or whose only such property is not a list or an
   * array, as an {@link Iterable} or an {@link Iterator} is, since the items to
   * project are then not known.
   */
  @Test
  void listsAToolOfItemsWithoutKnownKeysWithoutFieldsArgument() {
    for (String methodName : List.of("listMaps",
                                     "listObjects",
                                     "listNodes",
                                     "listDynamicThings",
                                     "listMapPage",
                                     "listPostsAndAuthors",
                                     "listPostsAndIterableAuthors",
                                     "listPostsAndOptionalAuthors",
                                     "listPostsAndObjectAuthors",
                                     "listPostsAndIteratorAuthors",
                                     "listPostsAndStreamAuthors",
                                     "listIterablePosts",
                                     "listIteratorPosts",
                                     "listAbstractThings",
                                     "listInterfaceThings")) {
      ToolCallback toolCallback = toolCallback(SCHEMA, "[]", methodName);

      assertEquals(Set.of("query", "limit"), listedProperties(toolCallback), methodName);
      assertTrue(McpServerUtils.itemKeys(toolCallback).isEmpty(), methodName);
    }
  }

  /**
   * The items of a generic envelope have the keys of its type argument; with
   * a wildcard, they are not known and the tool is listed without
   * {@code fields}.
   */
  @Test
  void readsTheItemKeysOfAGenericEnvelopeFromItsTypeArgument() {
    assertEquals(serializedThingKeys(), McpServerUtils.itemKeys(toolMethod("listGenericPage").getGenericReturnType()).keys());
    assertEquals("items", McpServerUtils.itemKeys(toolMethod("listGenericPage").getGenericReturnType()).listProperty());
    assertTrue(McpServerUtils.itemKeys(toolMethod("listWildcardPage").getGenericReturnType()).isEmpty());
    assertEquals(Set.of("query", "limit"), listedProperties(toolCallback(SCHEMA, "[]", "listWildcardPage")));
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
    ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                                                                .name(TOOL_NAME)
                                                                .description("Lists things")
                                                                .inputSchema(inputSchema)
                                                                .build();
    MethodToolCallback methodToolCallback = MethodToolCallback.builder()
                                                              .toolDefinition(toolDefinition)
                                                              .toolMethod(toolMethod(methodName))
                                                              .toolObject(new Tools())
                                                              .build();
    // The server's own wrapper, as the MCP server lists it
    MethodToolCallbackWrapper toolCallback = spy(new MethodToolCallbackWrapper(mock(McpServerToolService.class),
                                                                               null,
                                                                               null,
                                                                               null,
                                                                               null,
                                                                               null,
                                                                               methodToolCallback));
    doReturn(toolDefinition).when(toolCallback).getToolDefinition();
    doReturn(result).when(toolCallback).call(anyString(), any(ToolContext.class));
    return toolCallback;
  }


  /**
   * @return the keys of a {@link Thing}, in the order Spring AI's default tool
   *         result converter writes them
   */
  private static List<String> serializedThingKeys() {
    String thing = JacksonUtils.getDefaultJsonMapper().writeValueAsString(new Thing(1, 2, 3, List.of("p")));
    return List.copyOf(JSON_MAPPER.readTree(thing).propertyNames());
  }

  /**
   * @param toolCallback a tool callback
   * @return the argument names the MCP server lists the tool with
   */
  private static Set<?> listedProperties(ToolCallback toolCallback) {
    SyncToolSpecification specification = McpServerUtils.toSyncToolSpecification(toolCallback, null);
    return ((Map<?, ?>) specification.tool().inputSchema().get("properties")).keySet();
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

    /**
     * @return a list of plain values
     */
    List<String> listNames() {
      return null;
    }

    /**
     * @return an array of plain values
     */
    Long[] listCodes() {
      return null;
    }

    /**
     * @return a list of objects
     */
    List<Thing> listThingItems() {
      return null;
    }

    /**
     * @return a list whose elements are not known
     */
    @SuppressWarnings("rawtypes")
    List listRawItems() {
      return null;
    }

    /**
     * @return a list envelope of posts
     */
    PostCollection listPosts() {
      return null;
    }

    /**
     * @return a list envelope of posts and of their authors
     */
    PostAndAuthorCollection listPostsAndAuthors() {
      return null;
    }

    /**
     * @return a list envelope of posts and of an iterable of their authors
     */
    PostsAndIterableAuthors listPostsAndIterableAuthors() {
      return null;
    }

    /**
     * @return a list envelope of posts and of an optional list of their
     *         authors
     */
    PostsAndOptionalAuthors listPostsAndOptionalAuthors() {
      return null;
    }

    /**
     * @return a list envelope of posts and of their authors as an object
     */
    PostsAndObjectAuthors listPostsAndObjectAuthors() {
      return null;
    }

    /**
     * @return a list envelope of posts and of an iterator of their authors
     */
    PostsAndIteratorAuthors listPostsAndIteratorAuthors() {
      return null;
    }

    /**
     * @return a list envelope of posts and of a stream of their authors
     */
    PostsAndStreamAuthors listPostsAndStreamAuthors() {
      return null;
    }

    /**
     * @return a list envelope of an iterator of posts
     */
    IteratorPosts listIteratorPosts() {
      return null;
    }

    /**
     * @return a list envelope of an iterable of posts
     */
    IterablePosts listIterablePosts() {
      return null;
    }

    /**
     * @return a list of an abstract item type
     */
    List<AbstractThing> listAbstractThings() {
      return null;
    }

    /**
     * @return a list of an interface item type
     */
    List<InterfaceThing> listInterfaceThings() {
      return null;
    }

    /**
     * @return a list of maps
     */
    List<Map<String, Object>> listMaps() {
      return null;
    }

    /**
     * @return a list of objects of any type
     */
    List<Object> listObjects() {
      return null;
    }

    /**
     * @return a list of JSON nodes
     */
    List<JsonNode> listNodes() {
      return null;
    }

    /**
     * @return a list of items adding keys of their own
     */
    List<DynamicThing> listDynamicThings() {
      return null;
    }

    /**
     * @return a list envelope of maps
     */
    MapPage listMapPage() {
      return null;
    }

    /**
     * @return a generic list envelope of things
     */
    GenericPage<Thing> listGenericPage() {
      return null;
    }

    /**
     * @return a generic list envelope of unknown items
     */
    GenericPage<?> listWildcardPage() {
      return null;
    }
  }

  /**
   * A callback whose {@code toolMethod} field holds no method.
   */
  @SuppressWarnings("unused")
  private static class UnreadableToolCallback implements ToolCallback {

    private String toolMethod = "listThings";

    /**
     * @return no definition
     */
    @Override
    public ToolDefinition getToolDefinition() {
      return null;
    }

    /**
     * @param toolInput the call arguments
     * @return no result
     */
    @Override
    public String call(String toolInput) {
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

  /**
   * A post, whose id is serialized as {@code activity_id}.
   *
   * @param id      the post id
   * @param content its content
   * @param author  its author, a nested object
   */
  private record Post(@JsonProperty("activity_id") long id, String content, Author author) {
  }

  /**
   * A post author.
   *
   * @param name the author name
   * @param url  the author profile URL
   */
  private record Author(String name, String url) {
  }

  /**
   * A list envelope of posts, also listing plain tags.
   *
   * @param posts   the posts
   * @param tags    tags, plain values
   * @param hasMore whether more posts match
   */
  private record PostCollection(List<Post> posts, List<String> tags, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope of posts, also listing their authors.
   *
   * @param posts   the posts
   * @param authors their authors
   * @param hasMore whether more posts match
   */
  private record PostAndAuthorCollection(List<Post> posts, List<Author> authors, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope of posts, also writing their authors from an
   * {@link Iterable}.
   *
   * @param posts   the posts
   * @param authors their authors
   * @param hasMore whether more posts match
   */
  private record PostsAndIterableAuthors(List<Post> posts, Iterable<Author> authors, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope of posts, also writing their authors from an optional
   * list.
   *
   * @param posts   the posts
   * @param authors their authors
   * @param hasMore whether more posts match
   */
  private record PostsAndOptionalAuthors(List<Post> posts, Optional<List<Author>> authors,
                                         @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope of posts, also writing their authors from a property
   * declared {@link Object}.
   *
   * @param posts   the posts
   * @param authors their authors
   * @param hasMore whether more posts match
   */
  private record PostsAndObjectAuthors(List<Post> posts, Object authors, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope of posts, also writing their authors from an
   * {@link Iterator}.
   *
   * @param posts   the posts
   * @param authors their authors
   * @param hasMore whether more posts match
   */
  private record PostsAndIteratorAuthors(List<Post> posts, Iterator<Author> authors, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope of posts, also writing their authors from a
   * {@link Stream}.
   *
   * @param posts   the posts
   * @param authors their authors
   * @param hasMore whether more posts match
   */
  private record PostsAndStreamAuthors(List<Post> posts, Stream<Author> authors, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope writing its posts from an {@link Iterator}, whose element
   * type Jackson knows.
   *
   * @param posts   the posts
   * @param hasMore whether more posts match
   */
  private record IteratorPosts(Iterator<Post> posts, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A list envelope writing its posts from an {@link Iterable}.
   *
   * @param posts   the posts
   * @param tags    tags, plain values
   * @param hasMore whether more posts match
   */
  private record IterablePosts(Iterable<Post> posts, List<String> tags, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * An abstract item type, whose subclasses may add keys.
   */
  @SuppressWarnings("unused")
  private abstract static class AbstractThing {

    private long id;

    /**
     * @return the item id
     */
    public long getId() {
      return id;
    }
  }

  /**
   * An interface item type, whose implementations may add keys.
   */
  private interface InterfaceThing {

    /**
     * @return the item id
     */
    long getId();
  }

  /**
   * A list envelope of maps.
   *
   * @param items   the items
   * @param hasMore whether more items match
   */
  private record MapPage(List<Map<String, Object>> items, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * A generic list envelope.
   *
   * @param <T>     the item type
   * @param items   the items
   * @param hasMore whether more items match
   */
  private record GenericPage<T>(List<T> items, @JsonProperty("has_more") boolean hasMore) {
  }

  /**
   * An item adding keys of its own to its id.
   */
  @SuppressWarnings("unused")
  private static class DynamicThing {

    private long id;

    /**
     * @return the item id
     */
    public long getId() {
      return id;
    }

    /**
     * @return the keys the item adds
     */
    @JsonAnyGetter
    public Map<String, Object> getOther() {
      return Map.of();
    }
  }
}

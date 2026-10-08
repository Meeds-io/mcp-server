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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class McpToolArgumentUtilsTest {

  private static final String              TOOL_NAME    = "create_agenda_event";

  private static final String              SPACE_ID     = "space_id";

  private static final String              SUMMARY      = "summary";

  private static final String              UNKNOWN      = "unknown";

  private static final Map<String, Object> INPUT_SCHEMA = Map.of("type",
                                                                 "object",
                                                                 "properties",
                                                                 Map.of(SUMMARY,
                                                                        Map.of("type", "string"),
                                                                        SPACE_ID,
                                                                        Map.of("type", "integer")),
                                                                 "required",
                                                                 List.of(SUMMARY));

  /**
   * A {@code null} optional argument is dropped, the others are kept.
   */
  @Test
  void nullOptionalArgumentIsDropped() {
    Map<String, Object> arguments = arguments(SUMMARY, "Meeting", SPACE_ID, null);

    Map<String, Object> cleaned = McpToolArgumentUtils.removeNullOptionalArguments(INPUT_SCHEMA, arguments);

    assertEquals(Map.of(SUMMARY, "Meeting"), cleaned);
    assertTrue(arguments.containsKey(SPACE_ID), "The caller's map must not be modified");
  }

  /**
   * A {@code null} required argument is kept, so the validation still refuses
   * it.
   */
  @Test
  void nullRequiredArgumentIsKept() {
    Map<String, Object> arguments = arguments(SUMMARY, null, SPACE_ID, null);

    Map<String, Object> cleaned = McpToolArgumentUtils.removeNullOptionalArguments(INPUT_SCHEMA, arguments);

    assertEquals(1, cleaned.size());
    assertTrue(cleaned.containsKey(SUMMARY));
    assertNull(cleaned.get(SUMMARY));
  }

  /**
   * A non-null optional argument is kept, and the same map is returned when
   * nothing is dropped.
   */
  @Test
  void nonNullOptionalArgumentIsKept() {
    Map<String, Object> arguments = arguments(SUMMARY, "Meeting", SPACE_ID, 42);

    assertSame(arguments, McpToolArgumentUtils.removeNullOptionalArguments(INPUT_SCHEMA, arguments));
  }

  /**
   * An argument the schema does not declare is left untouched, even
   * {@code null}.
   */
  @Test
  void undeclaredNullArgumentIsKept() {
    Map<String, Object> arguments = arguments(SUMMARY, "Meeting", UNKNOWN, null);

    assertSame(arguments, McpToolArgumentUtils.removeNullOptionalArguments(INPUT_SCHEMA, arguments));
  }

  /**
   * Without a {@code required} list, every declared property is optional.
   */
  @Test
  void schemaWithoutRequiredListDropsEveryDeclaredNull() {
    Map<String, Object> schema = Map.of("type",
                                        "object",
                                        "properties",
                                        Map.of(SUMMARY, Map.of("type", "string"), SPACE_ID, Map.of("type", "integer")));
    Map<String, Object> arguments = arguments(SUMMARY, null, SPACE_ID, null);

    assertEquals(Map.of(), McpToolArgumentUtils.removeNullOptionalArguments(schema, arguments));
  }

  /**
   * Without a schema, or without declared properties, nothing is dropped.
   */
  @Test
  void schemaWithoutPropertiesDropsNothing() {
    Map<String, Object> arguments = arguments(SUMMARY, null, SPACE_ID, null);

    assertSame(arguments, McpToolArgumentUtils.removeNullOptionalArguments(null, arguments));
    assertSame(arguments, McpToolArgumentUtils.removeNullOptionalArguments(Map.of("type", "object"), arguments));
    assertNull(McpToolArgumentUtils.removeNullOptionalArguments(INPUT_SCHEMA, (Map<String, Object>) null));
  }

  /**
   * Raw JSON-RPC parameters get a cleaned copy of their arguments, the other
   * parameters being kept.
   */
  @Test
  @SuppressWarnings("unchecked")
  void rawParametersAreCleanedIntoACopy() {
    Map<String, Object> params = new HashMap<>();
    params.put("name", TOOL_NAME);
    params.put("arguments", arguments(SUMMARY, "Meeting", SPACE_ID, null));
    params.put("_meta", Map.of("progressToken", "1"));

    Map<String, Object> cleaned = (Map<String, Object>) McpToolArgumentUtils.removeNullOptionalParameters(INPUT_SCHEMA, params);

    assertEquals(Map.of(SUMMARY, "Meeting"), cleaned.get("arguments"));
    assertEquals(TOOL_NAME, cleaned.get("name"));
    assertEquals(Map.of("progressToken", "1"), cleaned.get("_meta"));
    assertTrue(((Map<String, Object>) params.get("arguments")).containsKey(SPACE_ID));
  }

  /**
   * A typed {@link CallToolRequest} is rebuilt with the cleaned arguments.
   */
  @Test
  void typedRequestIsRebuilt() {
    CallToolRequest request = new CallToolRequest(TOOL_NAME, arguments(SUMMARY, "Meeting", SPACE_ID, null), null);

    CallToolRequest cleaned = (CallToolRequest) McpToolArgumentUtils.removeNullOptionalParameters(INPUT_SCHEMA, request);

    assertEquals(TOOL_NAME, cleaned.name());
    assertEquals(Map.of(SUMMARY, "Meeting"), cleaned.arguments());
  }

  /**
   * The wrapped handler receives the parameters without the {@code null}
   * optional argument: the key is absent, not {@code null}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void wrappedHandlerReceivesNullOptionalArgumentAsAbsent() {
    AtomicReference<Object> received = new AtomicReference<>();
    McpRequestHandler<String> handler = McpToolArgumentUtils.withoutNullOptionalArguments(recording(received),
                                                                                          name -> Mono.just(tool(name)));

    String result = handler.handle(null, params(arguments(SUMMARY, "Meeting", SPACE_ID, null))).block();

    assertEquals("handled", result);
    Map<String, Object> arguments = (Map<String, Object>) ((Map<String, Object>) received.get()).get("arguments");
    assertFalse(arguments.containsKey(SPACE_ID));
    assertEquals("Meeting", arguments.get(SUMMARY));
  }

  /**
   * Without any {@code null} argument, the tool is not resolved and the
   * parameters are handed over as is.
   */
  @Test
  void wrappedHandlerSkipsResolutionWithoutNullArgument() {
    AtomicReference<Object> received = new AtomicReference<>();
    AtomicInteger resolutions = new AtomicInteger();
    McpRequestHandler<String> handler = McpToolArgumentUtils.withoutNullOptionalArguments(recording(received), name -> {
      resolutions.incrementAndGet();
      return Mono.just(tool(name));
    });
    Map<String, Object> params = params(arguments(SUMMARY, "Meeting", SPACE_ID, 42));

    handler.handle(null, params).block();

    assertSame(params, received.get());
    assertEquals(0, resolutions.get());
  }

  /**
   * An unknown tool leaves the parameters as they are, for the SDK to refuse.
   */
  @Test
  void wrappedHandlerPassesUnknownToolThrough() {
    AtomicReference<Object> received = new AtomicReference<>();
    McpRequestHandler<String> handler = McpToolArgumentUtils.withoutNullOptionalArguments(recording(received),
                                                                                          name -> Mono.empty());
    Map<String, Object> params = params(arguments(SUMMARY, "Meeting", SPACE_ID, null));

    handler.handle(null, params).block();

    assertSame(params, received.get());
  }

  /**
   * The session's {@code tools/call} handler is replaced by one that drops
   * the {@code null} optional arguments, resolving the tool on the server
   * obtained once, and the other handlers are kept.
   */
  @Test
  @SuppressWarnings("unchecked")
  void toolsCallHandlerIsWrappedInPlace() {
    AtomicReference<Object> received = new AtomicReference<>();
    McpRequestHandler<String> toolsList = (exchange, params) -> Mono.just("list");
    Map<String, McpRequestHandler<?>> handlers = new HashMap<>();
    handlers.put(McpSchema.METHOD_TOOLS_CALL, recording(received));
    handlers.put(McpSchema.METHOD_TOOLS_LIST, toolsList);
    McpAsyncServer server = mock(McpAsyncServer.class);
    when(server.listTools()).thenAnswer(invocation -> Flux.just(tool("other_tool"), tool(TOOL_NAME)));
    AtomicInteger serverLookups = new AtomicInteger();

    McpToolArgumentUtils.wrapToolsCallHandler(handlers, () -> {
      serverLookups.incrementAndGet();
      return server;
    });
    McpRequestHandler<String> toolsCall = (McpRequestHandler<String>) handlers.get(McpSchema.METHOD_TOOLS_CALL);
    toolsCall.handle(null, params(arguments(SUMMARY, "Meeting", SPACE_ID, null))).block();
    Map<String, Object> firstArguments = (Map<String, Object>) ((Map<String, Object>) received.get()).get("arguments");
    toolsCall.handle(null, params(arguments(SUMMARY, "Other", SPACE_ID, null))).block();

    assertSame(toolsList, handlers.get(McpSchema.METHOD_TOOLS_LIST));
    assertFalse(firstArguments.containsKey(SPACE_ID));
    assertEquals(1, serverLookups.get(), "The server must be looked up once, then kept");
  }

  /**
   * Without a {@code tools/call} handler, nothing is added.
   */
  @Test
  void handlersWithoutToolsCallAreLeftUnchanged() {
    Map<String, McpRequestHandler<?>> handlers = new HashMap<>();

    McpToolArgumentUtils.wrapToolsCallHandler(handlers, () -> mock(McpAsyncServer.class));

    assertTrue(handlers.isEmpty());
  }

  /**
   * No delegate, no wrapper.
   */
  @Test
  void missingDelegateIsNotWrapped() {
    assertNull(McpToolArgumentUtils.withoutNullOptionalArguments(null, name -> Mono.empty()));
  }

  /**
   * @param received where the handler records the parameters it receives
   * @return a handler recording its parameters and answering
   *         {@code "handled"}
   */
  private McpRequestHandler<String> recording(AtomicReference<Object> received) {
    return (exchange, params) -> {
      received.set(params);
      return Mono.just("handled");
    };
  }

  /**
   * @param name the tool name
   * @return a tool carrying {@link #INPUT_SCHEMA}
   */
  private McpSchema.Tool tool(String name) {
    return McpSchema.Tool.builder(name, INPUT_SCHEMA).build();
  }

  /**
   * @param arguments the tool arguments
   * @return raw {@code tools/call} parameters for {@link #TOOL_NAME}
   */
  private Map<String, Object> params(Map<String, Object> arguments) {
    Map<String, Object> params = new HashMap<>();
    params.put("name", TOOL_NAME);
    params.put("arguments", arguments);
    return params;
  }

  /**
   * @param name1  the first argument name
   * @param value1 the first argument value, may be {@code null}
   * @param name2  the second argument name
   * @param value2 the second argument value, may be {@code null}
   * @return a mutable map accepting {@code null} values
   */
  private Map<String, Object> arguments(String name1, Object value1, String name2, Object value2) {
    Map<String, Object> arguments = new HashMap<>();
    arguments.put(name1, value1);
    arguments.put(name2, value2);
    return arguments;
  }

}

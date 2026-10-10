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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Removes, from a {@code tools/call} request, every argument a model sent as
 * {@code null} although the tool's input schema declares it optional.
 * <p>
 * The MCP SDK validates the arguments against the tool's input schema
 * ({@code io.modelcontextprotocol.util.ToolInputValidator}, called by
 * {@code McpAsyncServer}'s {@code tools/call} handler) before the tool runs.
 * An optional {@code integer} property may be absent, but {@code null} is not
 * an integer, so a call carrying {@code "space_id": null} is refused before
 * reaching the tool, although models routinely send {@code null} for an
 * optional argument they do not use. Dropping that key ahead of the SDK's
 * handler makes the call equivalent to one that omitted the argument, both
 * for the validation and for the tool, whose Java binding already handles an
 * absent argument.
 * <p>
 * Only the top-level arguments are considered, and only those the schema
 * declares in {@code properties} without listing them in {@code required}: a
 * {@code null} for a required argument is left in place, so the validation
 * still refuses it, and an argument the schema does not declare is left
 * untouched, whatever its value.
 */
@Slf4j
public final class McpToolArgumentUtils {

  /** Key of the tool name in the {@code tools/call} parameters. */
  public static final String NAME_PARAM       = "name";

  /** Key of the tool arguments in the {@code tools/call} parameters. */
  public static final String ARGUMENTS_PARAM  = "arguments";

  /** Key of the declared properties in a JSON schema. */
  public static final String PROPERTIES_PARAM = "properties";

  /** Key of the required property names in a JSON schema. */
  public static final String REQUIRED_PARAM   = "required";

  /**
   * Utility class, not instantiated.
   */
  private McpToolArgumentUtils() {
  }

  /**
   * Replaces, in a session's request handlers, the SDK's {@code tools/call}
   * handler by {@link #withoutNullOptionalArguments(McpRequestHandler, Function)},
   * resolving each tool among the tools the MCP server exposes. The server is
   * obtained from {@code asyncServerSupplier} on the first call that needs a
   * tool, then kept. Handlers without a {@code tools/call} entry (no tool
   * capability) are left unchanged.
   *
   * @param handlers            the session's request handlers, by method,
   *                            modified in place
   * @param asyncServerSupplier supplies the MCP server whose tools are
   *                            resolved, called once, lazily, since the
   *                            server bean does not exist yet when the
   *                            session factory is built
   */
  public static void wrapToolsCallHandler(Map<String, McpRequestHandler<?>> handlers,
                                          Supplier<McpAsyncServer> asyncServerSupplier) {
    McpRequestHandler<?> toolsCallHandler = handlers.get(McpSchema.METHOD_TOOLS_CALL);
    if (toolsCallHandler == null) {
      return;
    }
    AtomicReference<McpAsyncServer> asyncServer = new AtomicReference<>();
    Function<String, Mono<McpSchema.Tool>> toolResolver = toolName -> {
      McpAsyncServer server = asyncServer.updateAndGet(cached -> cached == null ? asyncServerSupplier.get() : cached);
      return server.listTools().filter(tool -> toolName.equals(tool.name())).next();
    };
    handlers.put(McpSchema.METHOD_TOOLS_CALL, withoutNullOptionalArguments(toolsCallHandler, toolResolver));
  }

  /**
   * Wraps the SDK's {@code tools/call} request handler so that the parameters
   * it receives no longer carry a {@code null} for an optional argument. The
   * tool is resolved only when the arguments hold a {@code null}, so the
   * common call goes straight to the delegate.
   *
   * @param <T>          the result type of the wrapped handler
   * @param delegate     the SDK's {@code tools/call} handler, which validates
   *                     the arguments and then runs the tool
   * @param toolResolver resolves a tool name to the tool the server exposes,
   *                     or to an empty {@link Mono} for an unknown name
   * @return the wrapping handler, or {@code null} when {@code delegate} is
   *         {@code null} (no tool capability)
   */
  public static <T> McpRequestHandler<T> withoutNullOptionalArguments(McpRequestHandler<T> delegate,
                                                                      Function<String, Mono<McpSchema.Tool>> toolResolver) {
    if (delegate == null) {
      return null;
    }
    return (exchange, params) -> {
      String toolName = getToolName(params);
      if (toolName == null || !hasNullArgument(getArguments(params))) {
        return delegate.handle(exchange, params);
      }
      return toolResolver.apply(toolName)
                         .map(tool -> removeNullOptionalParameters(tool.inputSchema(), params))
                         .defaultIfEmpty(params)
                         .flatMap(cleanedParams -> delegate.handle(exchange, cleanedParams));
    };
  }

  /**
   * Removes the {@code null} optional arguments from raw {@code tools/call}
   * parameters, as the transport hands them to the request handler: a
   * {@link Map} deserialized from the JSON-RPC request, or an already typed
   * {@link CallToolRequest}. Any other shape is returned as is.
   *
   * @param inputSchema the tool's input schema
   * @param params      the {@code tools/call} parameters
   * @return the parameters without the {@code null} optional arguments, the
   *         same instance when there was nothing to remove
   */
  public static Object removeNullOptionalParameters(Map<String, Object> inputSchema, Object params) {
    if (params instanceof CallToolRequest callToolRequest) {
      Map<String, Object> arguments = removeNullOptionalArguments(inputSchema, callToolRequest.arguments());
      if (arguments == callToolRequest.arguments()) {
        return params;
      }
      return new CallToolRequest(callToolRequest.name(), arguments, callToolRequest.meta());
    } else if (params instanceof Map<?, ?> paramsMap) {
      Map<String, Object> arguments = getArguments(params);
      Map<String, Object> cleanedArguments = removeNullOptionalArguments(inputSchema, arguments);
      if (cleanedArguments == arguments) {
        return params;
      }
      Map<Object, Object> cleanedParams = new LinkedHashMap<>(paramsMap);
      cleanedParams.put(ARGUMENTS_PARAM, cleanedArguments);
      return cleanedParams;
    } else {
      return params;
    }
  }

  /**
   * Removes, from tool arguments, every entry whose value is {@code null} and
   * whose name the input schema declares in {@code properties} without
   * listing it in {@code required}.
   *
   * @param inputSchema the tool's input schema, may be {@code null}
   * @param arguments   the tool arguments, may be {@code null}
   * @return a copy without those entries, or the same instance when there was
   *         nothing to remove
   */
  public static Map<String, Object> removeNullOptionalArguments(Map<String, Object> inputSchema,
                                                                Map<String, Object> arguments) {
    if (inputSchema == null || !hasNullArgument(arguments)
        || !(inputSchema.get(PROPERTIES_PARAM) instanceof Map<?, ?> declaredProperties)) {
      return arguments;
    }
    Collection<?> required = inputSchema.get(REQUIRED_PARAM) instanceof Collection<?> requiredNames ? requiredNames : List.of();
    Map<String, Object> cleanedArguments = new LinkedHashMap<>();
    arguments.forEach((name, value) -> {
      if (value != null || !declaredProperties.containsKey(name) || required.contains(name)) {
        cleanedArguments.put(name, value);
      }
    });
    if (cleanedArguments.size() == arguments.size()) {
      return arguments;
    }
    if (log.isDebugEnabled()) {
      log.debug("Removed the null optional tool arguments {}",
                arguments.keySet().stream().filter(name -> !cleanedArguments.containsKey(name)).toList());
    }
    return cleanedArguments;
  }

  /**
   * @param params the {@code tools/call} parameters
   * @return the tool name they carry, or {@code null} when it can't be read
   */
  private static String getToolName(Object params) {
    if (params instanceof CallToolRequest callToolRequest) {
      return callToolRequest.name();
    } else if (params instanceof Map<?, ?> paramsMap && paramsMap.get(NAME_PARAM) instanceof String name) {
      return name;
    } else {
      return null;
    }
  }

  /**
   * @param params the {@code tools/call} parameters
   * @return the arguments they carry, or {@code null} when there are none or
   *         they can't be read
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> getArguments(Object params) {
    if (params instanceof CallToolRequest callToolRequest) {
      return callToolRequest.arguments();
    } else if (params instanceof Map<?, ?> paramsMap && paramsMap.get(ARGUMENTS_PARAM) instanceof Map<?, ?> arguments) {
      return (Map<String, Object>) arguments;
    } else {
      return null; // NOSONAR
    }
  }

  /**
   * @param arguments the tool arguments, may be {@code null}
   * @return whether one of them is {@code null}
   */
  private static boolean hasNullArgument(Map<String, Object> arguments) {
    return arguments != null && arguments.values().stream().anyMatch(Objects::isNull);
  }

}

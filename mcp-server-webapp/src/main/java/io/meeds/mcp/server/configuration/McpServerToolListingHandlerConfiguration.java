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
package io.meeds.mcp.server.configuration;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.Assert;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.meeds.mcp.server.service.McpServerToolService;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession.Factory;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Custom configuration to allow overriding MCP Tools Listing Handler. This
 * overrides
 * io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider
 * in order to be able to override the MCP Tools Listing Handler. This will
 * allow to list the Tools switch the elected scope.
 */
@Configuration
@EnableConfigurationProperties({ McpServerProperties.class, McpServerStreamableHttpProperties.class })
@Slf4j
public class McpServerToolListingHandlerConfiguration {

  /**
   * Wraps the Spring AI WebMvc streamable transport so that the session
   * factory it receives can be substituted by {@link McpStreamableServerSessionFactory}.
   *
   * @param applicationContext the application context, resolved lazily for
   *                           the MCP server and tool service beans
   * @param jsonMapper         the JSON mapper the transport serialises with
   * @param serverProperties   the streamable HTTP transport properties
   * @return the wrapping transport provider
   */
  @Bean
  public CustomMcpStreamableServerTransportProvider mcpStreamableServerTransportProvider(ApplicationContext applicationContext,
                                                                                         JsonMapper jsonMapper,
                                                                                         McpServerStreamableHttpProperties serverProperties) {
    return new CustomMcpStreamableServerTransportProvider(applicationContext,
                                                          WebMvcStreamableServerTransportProvider.builder()
                                                                                                 .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                                                                                                 .mcpEndpoint(serverProperties.getMcpEndpoint())
                                                                                                 .keepAliveInterval(serverProperties.getKeepAliveInterval())
                                                                                                 .disallowDelete(serverProperties.isDisallowDelete())
                                                                                                 .build());
  }

  /**
   * @param webMvcProvider the wrapping transport provider
   * @return the router function serving the MCP endpoint
   */
  @Bean
  public RouterFunction<ServerResponse> mcpStreamableServerRouterFunction(CustomMcpStreamableServerTransportProvider webMvcProvider) {
    return webMvcProvider.getRouterFunction();
  }

  public static class CustomMcpStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

    private ApplicationContext                      applicationContext;

    private WebMvcStreamableServerTransportProvider serverTransportProvider;

    /**
     * @param applicationContext      the application context handed to the
     *                                substituted session factory
     * @param serverTransportProvider the real transport every call is
     *                                delegated to
     */
    public CustomMcpStreamableServerTransportProvider(ApplicationContext applicationContext,
                                                      WebMvcStreamableServerTransportProvider serverTransportProvider) {
      this.serverTransportProvider = serverTransportProvider;
      this.applicationContext = applicationContext;
    }

    /**
     * @return the protocol versions the wrapped transport supports
     */
    @Override
    public List<String> protocolVersions() {
      return serverTransportProvider.protocolVersions();
    }

    /**
     * Installs the session factory, substituting the SDK's default one with
     * {@link McpStreamableServerSessionFactory} so that {@code tools/list} is
     * answered per scope.
     *
     * @param sessionFactory the factory the SDK built, expected to be its
     *                       {@link DefaultMcpStreamableServerSessionFactory}
     * @throws UnsupportedOperationException for any other factory, whose
     *                                       handlers this class cannot read
     */
    @Override
    public void setSessionFactory(Factory sessionFactory) {
      if (sessionFactory instanceof DefaultMcpStreamableServerSessionFactory defaultMcpStreamableServerSessionFactory) {
        serverTransportProvider.setSessionFactory(new McpStreamableServerSessionFactory(defaultMcpStreamableServerSessionFactory,
                                                                                        applicationContext));
      } else {
        throw new UnsupportedOperationException();
      }
    }

    /**
     * @param method the notification method
     * @param params the notification parameters
     * @return completion of the broadcast by the wrapped transport
     */
    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      return serverTransportProvider.notifyClients(method, params);
    }

    /**
     * @return completion of the wrapped transport's graceful shutdown
     */
    @Override
    public Mono<Void> closeGracefully() {
      return serverTransportProvider.closeGracefully();
    }

    /**
     * Closes the wrapped transport.
     */
    @Override
    public void close() {
      serverTransportProvider.close();
    }

    /**
     * @return the wrapped transport's router function, serving the MCP
     *         endpoint
     */
    public RouterFunction<ServerResponse> getRouterFunction() {
      return serverTransportProvider.getRouterFunction();
    }

  }

  public static class McpStreamableServerSessionFactory implements McpStreamableServerSession.Factory {

    private ApplicationContext                            applicationContext;

    private McpSyncServer                                 mcpSyncServer;

    private McpAsyncServer                                mcpAsyncServer;

    private McpServerToolService                          mcpServerToolService;

    private McpStreamableServerSession.InitRequestHandler initRequestHandler;

    private Map<String, McpRequestHandler<?>>             requestHandlers;

    private Map<String, McpNotificationHandler>           notificationHandlers;

    private Map<ToolsCacheKey, Mono<McpSchema.ListToolsResult>> toolsCache;

    private Duration                                      requestTimeout;

    /**
     * Copies the SDK factory's handlers, replacing the {@code tools/list} one
     * with {@link #toolsListRequestHandler()}.
     *
     * @param sessionFactory the SDK factory whose handlers are read
     * @param appContext     the application context the MCP server and tool
     *                       service beans are resolved from, lazily
     */
    public McpStreamableServerSessionFactory(DefaultMcpStreamableServerSessionFactory sessionFactory,
                                             ApplicationContext appContext) {
      this.requestTimeout = requestTimeout(sessionFactory);
      this.initRequestHandler = initRequestHandler(sessionFactory);
      this.requestHandlers = retrieveRequestHandlers(sessionFactory);
      this.notificationHandlers = notificationHandlers(sessionFactory);
      this.applicationContext = appContext;
      this.toolsCache = new ConcurrentHashMap<>();
    }

    /**
     * @param initializeRequest the client's {@code initialize} request
     * @return a new session wired to the copied handlers, with the SDK's own
     *         answer to the initialize request
     */
    @Override
    public McpStreamableServerSession.McpStreamableServerSessionInit startSession(McpSchema.InitializeRequest initializeRequest) {
      return new McpStreamableServerSession.McpStreamableServerSessionInit(new McpStreamableServerSession(UUID.randomUUID()
                                                                                                              .toString(),
                                                                                                          initializeRequest.capabilities(),
                                                                                                          initializeRequest.clientInfo(),
                                                                                                          requestTimeout,
                                                                                                          requestHandlers,
                                                                                                          notificationHandlers),
                                                                           this.initRequestHandler.handle(initializeRequest));
    }

    /**
     * Drops every cached {@code tools/list} answer. Called through the tool
     * update listener registered in {@link #getMcpServerToolService()}, so a
     * tool definition change — a tool disabled, its approval requirement
     * flipped — is visible on the very next listing rather than at restart.
     * Not called when the MCP audience changes: the cache is keyed on scopes
     * only, on the invariant documented in {@link #toolsListRequestHandler()},
     * so an audience change alters who reaches the handler, never what it
     * answers.
     */
    public void clearToolsCache() {
      toolsCache.clear();
    }

    /**
     * Identity of a cached {@code tools/list} answer: the scopes the caller's
     * token carries, the one input {@code isToolEligible} reads besides the
     * tool itself. Kept as a record of compared fields rather than a folded
     * {@code int} hash of the list, so that two scope lists that happen to
     * hash alike cannot share an entry.
     *
     * @param scopes the caller's {@code SCOPE_*} authorities, sorted and
     *               deduplicated so that two equivalent tokens share an entry
     */
    private record ToolsCacheKey(List<String> scopes) {
    }

    /**
     * @param sessionFactory the SDK factory
     * @return its notification handlers, read by reflection
     */
    private Map<String, McpNotificationHandler> notificationHandlers(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      return getField(sessionFactory, "notificationHandlers");
    }

    /**
     * @param sessionFactory the SDK factory
     * @return its initialize-request handler, read by reflection
     */
    private McpStreamableServerSession.InitRequestHandler initRequestHandler(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      return getField(sessionFactory, "initRequestHandler");
    }

    /**
     * @param sessionFactory the SDK factory
     * @return its request timeout, read by reflection
     */
    private Duration requestTimeout(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      return getField(sessionFactory, "requestTimeout");
    }

    /**
     * @param sessionFactory the SDK factory
     * @return a copy of its request handlers with {@code tools/list} replaced
     *         by {@link #toolsListRequestHandler()}
     */
    private Map<String, McpRequestHandler<?>> retrieveRequestHandlers(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      Map<String, McpRequestHandler<?>> handlers = new HashMap<>(getField(sessionFactory, "requestHandlers"));
      handlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
      return handlers;
    }

    /**
     * Reads a private field of the SDK factory, which exposes none of its
     * handlers through an accessor.
     *
     * @param <T>            the field type
     * @param sessionFactory the SDK factory
     * @param fieldName      the declared field name
     * @return the field value
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    private <T> T getField(DefaultMcpStreamableServerSessionFactory sessionFactory,
                           String fieldName) {
      Field field = DefaultMcpStreamableServerSessionFactory.class.getDeclaredField(fieldName);
      field.setAccessible(true); // NOSONAR
      return (T) field.get(sessionFactory);
    }

    /**
     * Builds the {@code tools/list} handler that replaces the SDK's: the
     * server's tools filtered through {@link #isToolEligible} for the caller's
     * scopes, memoised per distinct scope set in {@code toolsCache}. The cache
     * key holds the caller's {@code SCOPE_*} authorities and not the caller,
     * because — as the in-body comment states — eligibility is decided on the
     * global flag and the scopes alone once the door has admitted the caller.
     *
     * @return the handler, answering from the cache when the caller's scope
     *         set has been seen since the last {@link #clearToolsCache()}
     */
    private McpRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
      return (exchange, params) -> {
        Assert.notNull(getMcpServerToolService(), "Mcp Server Tool Service shouldn't be null");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // The key holds the scopes and not the caller, on an invariant this
        // cache depends on: the door (McpServerOauthOpaqueTokenIntrospector)
        // has already refused any caller outside the MCP audience before this
        // handler runs, and isAllowedTool answers on the global flag and the
        // scopes alone - all-or-nothing per user, never per tool - so every
        // caller reaching this line with the same scopes sees the same list.
        // Should the audience ever become per-tool or per-group, the caller
        // goes back into the key, and the map then also needs an eviction
        // policy: it only ever grows until a tool definition changes
        ToolsCacheKey key = new ToolsCacheKey(authentication.getAuthorities()
                                                            .stream()
                                                            .map(GrantedAuthority::getAuthority)
                                                            .filter(a -> a.startsWith("SCOPE_"))
                                                            .distinct()
                                                            .sorted()
                                                            .toList());
        return toolsCache.computeIfAbsent(key, k -> {
          List<Tool> listTools = listTools();
          List<Tool> tools = listTools.stream()
                                      .filter(t -> isToolEligible(t.name(), authentication))
                                      .toList();
          return Mono.just(McpSchema.ListToolsResult.builder(tools)
                                                    .build());
        });
      };
    }

    /**
     * Tells whether a tool is listed to a caller. Delegates to
     * {@code McpServerToolService.isAllowedTool}, the same answer the
     * {@code tools/call} path gives, so that a tool is never listed to a caller
     * who could not call it. The per-user MCP audience is not asked here: the
     * door has already refused any caller outside it before a handler runs.
     *
     * @param toolName       the tool name
     * @param authentication the caller's authentication, carrying the token's
     *                       scopes as {@code SCOPE_*} authorities
     * @return true when the caller may call the tool
     */
    private boolean isToolEligible(String toolName, Authentication authentication) {
      return getMcpServerToolService().isAllowedTool(toolName, authentication);
    }

    /**
     * @return every tool the MCP server exposes, read from the async server
     *         when one is configured and from the sync server otherwise
     */
    private List<Tool> listTools() {
      McpAsyncServer asyncServer = getMcpAsyncServer();
      return asyncServer == null ? getMcpSyncServer().listTools() : // NOSONAR
                                 asyncServer.listTools()
                                            .collectList()
                                            .block();
    }

    /**
     * @return the async MCP server bean, resolved once, or null when the
     *         application is configured with a sync server instead
     */
    private McpAsyncServer getMcpAsyncServer() {
      try {
        if (mcpAsyncServer == null) {
          mcpAsyncServer = applicationContext.getBean(McpAsyncServer.class);
        }
        return mcpAsyncServer;
      } catch (NoSuchBeanDefinitionException e) {
        return null;
      }
    }

    /**
     * @return the sync MCP server bean, resolved once, or null when the
     *         application is configured with an async server instead
     */
    private McpSyncServer getMcpSyncServer() {
      try {
        if (mcpSyncServer == null) {
          mcpSyncServer = applicationContext.getBean(McpSyncServer.class);
        }
        return mcpSyncServer;
      } catch (NoSuchBeanDefinitionException e) {
        return null;
      }
    }

    /**
     * Resolves the tool service once, and on that first resolution registers
     * {@link #clearToolsCache()} as its tool update listener, so that the cache
     * follows the tool definitions from then on.
     *
     * @return the tool service
     */
    private McpServerToolService getMcpServerToolService() {
      if (this.mcpServerToolService == null) {
        this.mcpServerToolService = applicationContext.getBean(McpServerToolService.class);
        this.mcpServerToolService.addToolUpdateListener(n -> this.clearToolsCache());
      }
      return this.mcpServerToolService;
    }
  }
}

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

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.Assert;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.mcp.server.service.McpToolListingCache;
import io.meeds.mcp.server.util.McpToolArgumentUtils;

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
 * allow to list the Tools switch the elected scope. It also wraps the
 * {@code tools/call} handler so that a {@code null} sent for an optional
 * argument is dropped before the SDK validates the arguments.
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

    private McpToolListingCache                          listingCache;

    private Duration                                      requestTimeout;

    /**
     * Copies the SDK factory's handlers, replacing the {@code tools/list} one
     * with {@link #toolsListRequestHandler()} and wrapping the
     * {@code tools/call} one so that it never sees a {@code null} optional
     * argument.
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
     * tool definition change that affects the scope check — its approval
     * requirement flipped — is visible on the very next listing rather than at
     * restart; a switched-off tool is filtered at read time whatever the cache
     * holds. Not called when the MCP audience changes: the cache is keyed on
     * scopes only, on the invariant documented in {@link McpToolListingCache},
     * so an audience change alters who reaches the handler, never what it
     * answers.
     */
    public void clearToolsCache() {
      if (listingCache != null) {
        listingCache.clear();
      }
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
     *         by {@link #toolsListRequestHandler()}, and {@code tools/call}
     *         wrapped by
     *         {@link McpToolArgumentUtils#wrapToolsCallHandler} so that
     *         the SDK's input validation, and then the tool, receive no
     *         {@code null} for an argument the tool's schema declares optional
     */
    private Map<String, McpRequestHandler<?>> retrieveRequestHandlers(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      Map<String, McpRequestHandler<?>> handlers = new HashMap<>(getField(sessionFactory, "requestHandlers"));
      handlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
      McpToolArgumentUtils.wrapToolsCallHandler(handlers, this::getAsyncServer);
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
     * Builds the {@code tools/list} handler that replaces the SDK's, answering
     * from {@link McpToolListingCache}.
     *
     * @return the handler
     */
    private McpRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
      return (exchange, params) -> {
        Assert.notNull(getMcpServerToolService(), "Mcp Server Tool Service shouldn't be null");
        return listingCache.list(SecurityContextHolder.getContext().getAuthentication());
      };
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
     * @return the async MCP server: the async bean when the application is
     *         configured with one, otherwise the one the sync server wraps
     */
    private McpAsyncServer getAsyncServer() {
      McpAsyncServer asyncServer = getMcpAsyncServer();
      return asyncServer == null ? getMcpSyncServer().getAsyncServer() : asyncServer;
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
    private synchronized McpServerToolService getMcpServerToolService() {
      if (this.mcpServerToolService == null) {
        McpServerToolService toolService = applicationContext.getBean(McpServerToolService.class);
        this.listingCache = new McpToolListingCache(toolService, this::listTools);
        toolService.addToolUpdateListenerFirst(n -> this.clearToolsCache());
        this.mcpServerToolService = toolService;
      }
      return this.mcpServerToolService;
    }
  }
}

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

  @Bean
  public RouterFunction<ServerResponse> mcpStreamableServerRouterFunction(CustomMcpStreamableServerTransportProvider webMvcProvider) {
    return webMvcProvider.getRouterFunction();
  }

  public static class CustomMcpStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

    private ApplicationContext                      applicationContext;

    private WebMvcStreamableServerTransportProvider serverTransportProvider;

    public CustomMcpStreamableServerTransportProvider(ApplicationContext applicationContext,
                                                      WebMvcStreamableServerTransportProvider serverTransportProvider) {
      this.serverTransportProvider = serverTransportProvider;
      this.applicationContext = applicationContext;
    }

    @Override
    public List<String> protocolVersions() {
      return serverTransportProvider.protocolVersions();
    }

    @Override
    public void setSessionFactory(Factory sessionFactory) {
      if (sessionFactory instanceof DefaultMcpStreamableServerSessionFactory defaultMcpStreamableServerSessionFactory) {
        serverTransportProvider.setSessionFactory(new McpStreamableServerSessionFactory(defaultMcpStreamableServerSessionFactory,
                                                                                        applicationContext));
      } else {
        throw new UnsupportedOperationException();
      }
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      return serverTransportProvider.notifyClients(method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return serverTransportProvider.closeGracefully();
    }

    @Override
    public void close() {
      serverTransportProvider.close();
    }

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

    private Map<Integer, Mono<McpSchema.ListToolsResult>> toolsCache;

    private Duration                                      requestTimeout;

    public McpStreamableServerSessionFactory(DefaultMcpStreamableServerSessionFactory sessionFactory,
                                             ApplicationContext appContext) {
      this.requestTimeout = requestTimeout(sessionFactory);
      this.initRequestHandler = initRequestHandler(sessionFactory);
      this.requestHandlers = retrieveRequestHandlers(sessionFactory);
      this.notificationHandlers = notificationHandlers(sessionFactory);
      this.applicationContext = appContext;
      this.toolsCache = new ConcurrentHashMap<>();
    }

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

    public void clearToolsCache() {
      toolsCache.clear();
    }

    private Map<String, McpNotificationHandler> notificationHandlers(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      return getField(sessionFactory, "notificationHandlers");
    }

    private McpStreamableServerSession.InitRequestHandler initRequestHandler(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      return getField(sessionFactory, "initRequestHandler");
    }

    private Duration requestTimeout(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      return getField(sessionFactory, "requestTimeout");
    }

    private Map<String, McpRequestHandler<?>> retrieveRequestHandlers(DefaultMcpStreamableServerSessionFactory sessionFactory) {
      Map<String, McpRequestHandler<?>> handlers = new HashMap<>(getField(sessionFactory, "requestHandlers"));
      handlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
      return handlers;
    }

    @SuppressWarnings("unchecked")
    @SneakyThrows
    private <T> T getField(DefaultMcpStreamableServerSessionFactory sessionFactory,
                           String fieldName) {
      Field field = DefaultMcpStreamableServerSessionFactory.class.getDeclaredField(fieldName);
      field.setAccessible(true); // NOSONAR
      return (T) field.get(sessionFactory);
    }

    private McpRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
      return (exchange, params) -> {
        Assert.notNull(getMcpServerToolService(), "Mcp Server Tool Service shouldn't be null");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        int key = authentication.getAuthorities()
                                .stream()
                                .map(GrantedAuthority::getAuthority)
                                .filter(a -> a.startsWith("SCOPE_"))
                                .distinct()
                                .sorted()
                                .collect(Collectors.toCollection(ArrayList::new))
                                .hashCode();
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

    private boolean isToolEligible(String toolName, Authentication authentication) {
      return getMcpServerToolService().isAllowedTool(toolName, authentication);
    }

    private List<Tool> listTools() {
      McpAsyncServer asyncServer = getMcpAsyncServer();
      return asyncServer == null ? getMcpSyncServer().listTools() : // NOSONAR
                                 asyncServer.listTools()
                                            .collectList()
                                            .block();
    }

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

    private McpServerToolService getMcpServerToolService() {
      if (this.mcpServerToolService == null) {
        this.mcpServerToolService = applicationContext.getBean(McpServerToolService.class);
        this.mcpServerToolService.addToolUpdateListener(n -> this.clearToolsCache());
      }
      return this.mcpServerToolService;
    }
  }
}

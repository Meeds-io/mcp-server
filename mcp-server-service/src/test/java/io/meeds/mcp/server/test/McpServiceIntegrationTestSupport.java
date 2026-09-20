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
package io.meeds.mcp.server.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.meeds.kernel.test.AbstractSpringTest;
import io.meeds.kernel.test.KernelExtension;
import io.meeds.mcp.server.plugin.McpServerOauthOpaqueTokenIntrospector;
import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.service.McpToolApprovalService;
import io.meeds.mcp.server.test.McpServiceIntegrationTestSupport.Config;
import io.meeds.mcp.server.test.McpServiceIntegrationTestSupport.TestMcpToolConfiguration;
import io.meeds.oauth2.server.test.IntegrationTestBaseTestApplication;
import io.meeds.spring.AvailableIntegration;

/**
 * Base class of every integration test of this module, carrying the <b>whole</b>
 * Spring context configuration — imports and bean overrides alike — rather than
 * letting each suite declare its own.
 * <p>
 * That is not a tidiness choice: a module gets <b>one</b> kernel-bridged Spring
 * context per JVM. {@code SpringBeanFactoryInterceptor} hands each refreshing
 * context to {@code KernelContainerLifecyclePlugin.addSpringContext}, which
 * returns early once its static {@code kernelAlreadyBooted} latch is set — and
 * nothing ever resets it. So the second context a suite causes to be built gets
 * no Kernel component at all: every {@code UserACL}, {@code SettingService} or
 * {@code IdentityManager} injection in it fails, with
 * {@code "Adding Spring context 'test' happened too late in Server startup"}
 * as the only clue, and only when the suites run together.
 * <p>
 * Spring's context cache keys on the merged configuration, {@code @Import}s and
 * {@code @MockitoBean} overrides included, so a suite that declares one of its
 * own silently asks for a second context. Declaring them here instead keeps
 * every suite on the single cached one. A new suite adds what it needs
 * <em>here</em>, for every suite, or it does without.
 */
@AutoConfigureMockMvc
@Import({ IntegrationTestBaseTestApplication.class, TestMcpToolConfiguration.class })
@SpringBootTest(classes = Config.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ExtendWith({ SpringExtension.class, KernelExtension.class })
public abstract class McpServiceIntegrationTestSupport extends AbstractSpringTest {

  /**
   * Substituted for every suite: the real bean needs a
   * {@code ContinuationService} that a service-module kernel does not start.
   */
  @MockitoBean
  protected McpToolApprovalService                mcpToolApprovalService;

  /**
   * Substituted for every suite so that a suite driving MCP protocol traffic
   * can choose the principal a token resolves to. A suite that needs the real
   * door instead builds its own instance from the context's real collaborators
   * — see {@code McpServerAudienceGateIntegrationTest}.
   */
  @MockitoBean
  protected McpServerOauthOpaqueTokenIntrospector opaqueTokenIntrospector;

  @BeforeEach
  public void beginRequest() {
    getContainer();
    begin();
    setUp();
  }

  @AfterEach
  public void endRequest() {
    tearDown();
    end();
  }

  protected void setUp() {
  }

  protected void tearDown() {
  }

  @SpringBootApplication(scanBasePackages = {
    "io.meeds.mcp.server",
    AvailableIntegration.KERNEL_TEST_MODULE,
  })
  @EnableAutoConfiguration(exclude = {
    SecurityAutoConfiguration.class,
    ElasticsearchClientAutoConfiguration.class,
    ElasticsearchRestClientAutoConfiguration.class,
    StatelessServerSpecificationFactoryAutoConfiguration.class,
    OAuth2AuthorizationServerAutoConfiguration.class,
    McpServerStreamableHttpWebMvcAutoConfiguration.class,
  })
  @EnableWebSecurity
  @EnableMethodSecurity
  @PropertySource("classpath:application.properties")
  @PropertySource("classpath:application-common.properties")
  @PropertySource("classpath:auth-server.properties")
  @PropertySource("classpath:mcp-server.properties")
  public static class Config {
  }

  @TestConfiguration
  public static class TestMcpToolConfiguration {

    /**
     * @return the tool every MCP protocol suite calls, contributed as a plugin
     *         so that the real tool registry discovers it
     */
    @Bean
    public McpToolPlugin testMcpTool() {
      return new TestMcpTool();
    }

  }

  public static class TestMcpTool implements McpToolPlugin {

    /**
     * @param message the message to echo
     * @return the message, prefixed to show which tool answered
     */
    public String testReadTool(String message) {
      return "read:" + message;
    }

    /**
     * @param message the message to echo
     * @return the message, prefixed to show which tool answered
     */
    public String testWriteTool(String message) {
      return "write:" + message;
    }

    /**
     * @param message the message to echo
     * @return the message, prefixed to show which tool answered
     */
    public String testApprovalTool(String message) {
      return "approval:" + message;
    }

  }

}

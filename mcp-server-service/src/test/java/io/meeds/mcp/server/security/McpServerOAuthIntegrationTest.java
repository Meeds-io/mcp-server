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
package io.meeds.mcp.server.security;

import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_APPROVE_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.plugin.McpServerOauthOpaqueTokenIntrospector;
import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.mcp.server.service.McpToolApprovalService;
import io.meeds.mcp.server.test.McpServiceIntegrationTestSupport;
import io.meeds.oauth2.server.test.IntegrationTestBaseTestApplication;

import lombok.extern.slf4j.Slf4j;

@AutoConfigureMockMvc
@Import({
  McpServerOAuthIntegrationTest.TestMcpToolConfiguration.class,
  IntegrationTestBaseTestApplication.class
})
@DisplayName("MCP server OAuth integration suite")
@Slf4j
class McpServerOAuthIntegrationTest extends McpServiceIntegrationTestSupport {

  private static final String                   INPUT_SCHEMA            = """
      {
        "type": "object",
        "properties": {
          "message": {
            "type": "string"
          }
        },
        "required": ["message"]
      }
      """;

  private static final String                   MESSAGE                 = "hello";

  private static final String                   WRITE_MESSAGE           = "write:hello";

  private static final String                   USERNAME                = "root";

  private static final String                   SUB_PARAM               = "sub";

  private static final String                   ACTIVE_PARAM            = "active";

  private static final String                   ACCEPT_HEADER_VALUE     = "application/json, text/event-stream";

  private static final String                   TEST_READ_TOOL_NAME     = "test_read_tool";

  private static final String                   TEST_APPROVAL_TOOL_NAME = "test_approval_tool";

  private static final String                   TEST_WRITE_TOOL_NAME    = "test_write_tool";

  private static final String                   IS_ERROR_FALSE_MESSAGE  = "\"isError\":false";

  private static final String                   MCP_SESSION_ID_HEADER   = "Mcp-Session-Id";

  private static final String                   TOKEN_ENDPOINT          = "/oauth2/token";

  private static final String                   MCP_ENDPOINT            = "/mcp";

  private static final String                   CLIENT_TEST_SECRET      = "test_secret";

  private static final String                   GRANT_TYPE_PARAM        = "grant_type";

  private static final String                   SCOPE_PARAM             = "scope";

  private static final String                   ACCESS_TOKEN_PATH       = "access_token";

  private static final String                   INITIALIZE_REQUEST      = """
      {
        "jsonrpc": "2.0",
        "id": "init",
        "method": "initialize",
        "params": {
          "protocolVersion": "2025-06-18",
          "capabilities": {
            "tools": {}
          },
          "clientInfo": {
            "name": "mcp-oauth-test-client",
            "version": "1.0.0"
          }
        }
      }
      """;

  private final ObjectMapper                    objectMapper            = new ObjectMapper();

  @Autowired
  private MockMvc                               mvc;

  @Autowired
  private RegisteredClientRepository            registeredClientRepository;

  @Autowired
  private PasswordEncoder                       passwordEncoder;

  @Autowired
  private McpServerToolService                  mcpServerToolService;

  @MockitoBean
  private McpToolApprovalService                mcpToolApprovalService;

  @MockitoBean
  private McpServerOauthOpaqueTokenIntrospector opaqueTokenIntrospector;

  private String                                currentScopes;

  private String                                currentToken;

  @BeforeEach
  @Override
  protected void setUp() {
    begin();
    mcpServerToolService.enableMcpServer();
    this.currentScopes = null;
    this.currentToken = null;

    doNothing().when(mcpToolApprovalService).traceToolExecution(any(UserToolExecution.class));
    when(mcpToolApprovalService.requestApproval(anyString(),
                                                anyString(),
                                                anyString(),
                                                anyString(),
                                                anyString())).thenReturn(true);
    doAnswer(invocation -> {
      assertNotNull(currentScopes);
      assertNotNull(invocation.getArgument(0));
      assertEquals(currentToken, invocation.getArgument(0), "Tokens doesn't match");
      return new DefaultOAuth2AuthenticatedPrincipal(USERNAME,
                                                     Map.of(
                                                            SUB_PARAM,
                                                            USERNAME,
                                                            ACTIVE_PARAM,
                                                            true,
                                                            SCOPE_PARAM,
                                                            currentScopes),
                                                     Arrays.stream(currentScopes.split(" "))
                                                           .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_%s".formatted(scope)))
                                                           .toList());
    }).when(opaqueTokenIntrospector)
      .introspect(anyString());
  }

  @AfterEach
  @Override
  protected void tearDown() {
    mcpServerToolService.setForceReimport(true);
    mcpServerToolService.setToolDefinitions(null);
  }

  @Test
  @DisplayName("Anonymous MCP request is rejected")
  void anonymousMcpRequestIsRejected() throws Exception {
    mvc.perform(post(MCP_ENDPOINT)
                                  .header(HttpHeaders.ACCEPT, ACCEPT_HEADER_VALUE)
                                  .contentType(APPLICATION_JSON)
                                  .content(initializeRequest()))
       .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Read scope can initialize and list read-only MCP tools")
  void readScopeCanListReadOnlyTools() throws Exception {
    String token = issueToken(clientWithScopes("mcp-read-" + UUID.randomUUID(), TOOL_READ_SCOPE));

    String sessionId = initializeSession(token);

    MvcResult result = mvc.perform(post(MCP_ENDPOINT)
                                                     .header(AUTHORIZATION, bearer(token))
                                                     .header(MCP_SESSION_ID_HEADER, sessionId)
                                                     .header(HttpHeaders.ACCEPT, ACCEPT_HEADER_VALUE)
                                                     .contentType(APPLICATION_JSON)
                                                     .content("""
                                                         {
                                                           "jsonrpc": "2.0",
                                                           "id": "tools-list-read",
                                                           "method": "tools/list",
                                                           "params": {}
                                                         }
                                                         """))
                          .andExpect(status().isOk())
                          .andReturn();

    String body = result.getResponse().getContentAsString();

    assertThat(body).contains(TEST_READ_TOOL_NAME);
    assertThat(body).doesNotContain(TEST_WRITE_TOOL_NAME);
    assertThat(body).doesNotContain(TEST_APPROVAL_TOOL_NAME);
  }

  @Test
  @DisplayName("Read scope can call read-only MCP tool")
  void readScopeCanCallReadOnlyTool() throws Exception {
    String token = issueToken(clientWithScopes("mcp-read-call-" + UUID.randomUUID(), TOOL_READ_SCOPE));

    String sessionId = initializeSession(token);

    MvcResult result = callTool(token, sessionId, TEST_READ_TOOL_NAME, MESSAGE);

    assertThat(result.getResponse().getContentAsString())
                                                         .contains("read:hello")
                                                         .contains(IS_ERROR_FALSE_MESSAGE);
  }

  @Test
  @DisplayName("Read scope cannot call write MCP tool")
  void readScopeCannotCallWriteTool() throws Exception {
    String token = issueToken(clientWithScopes("mcp-read-denied-" + UUID.randomUUID(), TOOL_READ_SCOPE));

    String sessionId = initializeSession(token);

    MvcResult result = callTool(token, sessionId, TEST_WRITE_TOOL_NAME, "blocked");

    assertThat(result.getResponse().getContentAsString())
                                                         .contains("\"isError\":true")
                                                         .contains("execution isn't allowed");
  }

  @Test
  @DisplayName("Write scope can call write MCP tool")
  void writeScopeCanCallWriteTool() throws Exception {
    String token = issueToken(clientWithScopes("mcp-write-" + UUID.randomUUID(), TOOL_WRITE_SCOPE));

    String sessionId = initializeSession(token);

    MvcResult result = callTool(token, sessionId, TEST_WRITE_TOOL_NAME, MESSAGE);

    assertThat(result.getResponse().getContentAsString()).contains(WRITE_MESSAGE)
                                                         .contains(IS_ERROR_FALSE_MESSAGE);
  }

  @Test
  @DisplayName("Write with approval scope calls approval service and executes tool")
  void writeWithApprovalScopeCallsApprovalServiceAndExecutesTool() throws Exception {
    String token = issueToken(clientWithScopes("mcp-approval-" + UUID.randomUUID(), TOOL_WRITE_APPROVE_SCOPE));

    String sessionId = initializeSession(token);

    MvcResult result = callTool(token, sessionId, TEST_APPROVAL_TOOL_NAME, "approve-me");

    assertThat(result.getResponse().getContentAsString()).contains("approval:approve-me")
                                                         .contains(IS_ERROR_FALSE_MESSAGE);
  }

  @Test
  @DisplayName("Tool definition update enables approval requirement")
  void updateToolDefinitionEnablesApprovalRequirement() throws Exception {
    String token = issueToken(clientWithScopes("mcp-approval-update-" + UUID.randomUUID(),
                                               TOOL_WRITE_APPROVE_SCOPE));

    mcpServerToolService.updateToolDefinition(TEST_WRITE_TOOL_NAME,
                                              "Test Write Tool",
                                              "Write MCP integration test tool",
                                              INPUT_SCHEMA,
                                              true,
                                              false);

    String sessionId = initializeSession(token);

    MvcResult result = callTool(token, sessionId, TEST_WRITE_TOOL_NAME, MESSAGE);

    assertThat(result.getResponse().getContentAsString())
                                                         .contains(WRITE_MESSAGE)
                                                         .contains(IS_ERROR_FALSE_MESSAGE);

    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   anyString(),
                                                   eq("testWriteTool"),
                                                   anyString(),
                                                   eq(USERNAME));
  }

  @Test
  @DisplayName("Write scope bypasses approval even if tool requires approval")
  void writeScopeBypassesApprovalEvenIfToolRequiresApproval() throws Exception {
    String token = issueToken(clientWithScopes("mcp-write-no-approval-" + UUID.randomUUID(),
                                               TOOL_WRITE_SCOPE));

    mcpServerToolService.updateToolDefinition(TEST_WRITE_TOOL_NAME,
                                              "Test Write Tool",
                                              "Write MCP integration test tool",
                                              INPUT_SCHEMA,
                                              true, // require approval
                                              false);

    String sessionId = initializeSession(token);

    MvcResult result = callTool(token, sessionId, TEST_WRITE_TOOL_NAME, MESSAGE);

    assertThat(result.getResponse().getContentAsString()).contains(WRITE_MESSAGE)
                                                         .contains(IS_ERROR_FALSE_MESSAGE);

    verify(mcpToolApprovalService, never()).requestApproval(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void updatedToolStillVisibleAfterDefinitionChange() throws Exception {
    mcpServerToolService.updateToolDefinition(TEST_WRITE_TOOL_NAME,
                                              "Updated Title",
                                              "Updated desc",
                                              INPUT_SCHEMA,
                                              true,
                                              false);

    String token = issueToken(clientWithScopes("mcp-read-" + UUID.randomUUID(),
                                               TOOL_WRITE_SCOPE));

    String sessionId = initializeSession(token);

    MvcResult result = mvc.perform(post(MCP_ENDPOINT).header(AUTHORIZATION, bearer(token))
                                                     .header(MCP_SESSION_ID_HEADER, sessionId)
                                                     .header(HttpHeaders.ACCEPT, ACCEPT_HEADER_VALUE)
                                                     .contentType(APPLICATION_JSON)
                                                     .content("""
                                                             { "jsonrpc": "2.0", "id": "1", "method": "tools/list", "params": {} }
                                                         """))
                          .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("\"name\":\"test_write_tool\"")
                    .contains("\"title\":\"Updated Title\"")
                    .contains("\"description\":\"Updated desc\"");
  }

  private MvcResult callTool(String token, String sessionId, String toolName, String message) throws Exception {
    return mvc.perform(post(MCP_ENDPOINT)
                                         .header(AUTHORIZATION, bearer(token))
                                         .header(MCP_SESSION_ID_HEADER, sessionId)
                                         .header("conversationId", "test-conversation-" + UUID.randomUUID())
                                         .header(HttpHeaders.ACCEPT, ACCEPT_HEADER_VALUE)
                                         .contentType(APPLICATION_JSON)
                                         .content("""
                                             {
                                               "jsonrpc": "2.0",
                                               "id": "tool-call",
                                               "method": "tools/call",
                                               "params": {
                                                 "name": "%s",
                                                 "arguments": {
                                                   "message": "%s"
                                                 }
                                               }
                                             }
                                             """
                                                .formatted(toolName, message)))
              .andExpect(status().isOk())
              .andReturn();
  }

  private String initializeSession(String token) throws Exception {
    MvcResult result = mvc.perform(post(MCP_ENDPOINT)
                                                     .header(AUTHORIZATION, bearer(token))
                                                     .header(HttpHeaders.ACCEPT, ACCEPT_HEADER_VALUE)
                                                     .contentType(APPLICATION_JSON)
                                                     .content(initializeRequest()))
                          .andExpect(status().isOk())
                          .andReturn();

    String sessionId = result.getResponse().getHeader(MCP_SESSION_ID_HEADER);
    assertThat(sessionId).isNotBlank();
    return sessionId;
  }

  private String initializeRequest() {
    return INITIALIZE_REQUEST;
  }

  private RegisteredClient clientWithScopes(String clientId, String... scopes) {
    RegisteredClient.Builder builder = RegisteredClient.withId(clientId)
                                                       .clientId(clientId)
                                                       .clientSecret(passwordEncoder.encode(CLIENT_TEST_SECRET)) // NOSONAR
                                                       .clientName("MCP test client")
                                                       .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                                                       .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                                                       .scope(OidcScopes.OPENID)
                                                       .clientSettings(ClientSettings.builder().build())
                                                       .tokenSettings(TokenSettings.builder()
                                                                                   .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                                                                                   .accessTokenTimeToLive(Duration.ofMinutes(5))
                                                                                   .build());

    for (String scope : scopes) {
      builder.scope(scope);
    }

    RegisteredClient client = builder.build();
    registeredClientRepository.save(client);
    return client;
  }

  private String issueToken(RegisteredClient client) throws Exception {
    this.currentScopes = String.join(" ", client.getScopes());
    log.info(">> Setting Current Scopes: {}", currentScopes);

    MvcResult result = mvc.perform(post(TOKEN_ENDPOINT).contentType(APPLICATION_FORM_URLENCODED_VALUE)
                                                       .header(AUTHORIZATION, basic(client.getClientId(), CLIENT_TEST_SECRET))
                                                       .param(GRANT_TYPE_PARAM,
                                                              AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
                                                       .param(SCOPE_PARAM, currentScopes))
                          .andExpect(status().isOk())
                          .andReturn();

    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    this.currentToken = response.path(ACCESS_TOKEN_PATH).asText();
    log.info(">> Setting Current Token: {}", currentToken);
    return response.path(ACCESS_TOKEN_PATH).asText();
  }

  private String basic(String clientId, String clientSecret) {
    return "Basic " + Base64.getEncoder()
                            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  @TestConfiguration
  static class TestMcpToolConfiguration {

    @Bean
    McpToolPlugin testMcpTool() {
      return new TestMcpTool();
    }

  }

  public static class TestMcpTool implements McpToolPlugin {

    public String testReadTool(String message) {
      return "read:" + message;
    }

    public String testWriteTool(String message) {
      return "write:" + message;
    }

    public String testApprovalTool(String message) {
      return "approval:" + message;
    }

  }

}

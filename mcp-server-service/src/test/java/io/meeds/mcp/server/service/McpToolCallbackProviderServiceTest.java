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
package io.meeds.mcp.server.service;

import static io.meeds.mcp.server.util.McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_CONVERSATION_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ID;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ID_PARAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.plugin.McpToolPlugin;

/**
 * Pins how {@code MethodToolCallbackWrapper} resolves the chat conversation
 * of a Tool call: from the internal client's request header only, behind the
 * same contextId gate as the user name, and never from anything the MCP
 * client can choose. Approval-gated Tools fail fast without a conversation
 * instead of waiting for an answer nobody can give.
 */
@ExtendWith(MockitoExtension.class)
class McpToolCallbackProviderServiceTest {

  private static final String           USERNAME        = "john";

  private static final String           CONVERSATION_ID = "conv-1";

  private static final String           TOOL_METHOD     = "greet";

  private static final String           TOOL_INPUT      = "{\"name\":\"Bob\"}";

  @Mock
  private ApplicationContext            applicationContext;

  @Mock
  private McpServerToolService          mcpServerToolService;

  @Mock
  private McpToolApprovalService        mcpToolApprovalService;

  @Mock
  private UserACL                       userAcl;

  private McpToolCallbackProviderService service;

  private ToolCallback                  toolCallback;

  @BeforeEach
  void setUp() {
    when(mcpServerToolService.getToolDefinitionByMethodName(TOOL_METHOD)).thenReturn(new SimpleToolDefinition(TOOL_METHOD,
                                                                                                             "Greet",
                                                                                                             "Greets someone",
                                                                                                             "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}",
                                                                                                             true,
                                                                                                             false,
                                                                                                             null));
    service = new McpToolCallbackProviderService(applicationContext,
                                                 mcpServerToolService,
                                                 mcpToolApprovalService,
                                                 userAcl,
                                                 List.of(new GreetingToolPlugin()));
    toolCallback = service.getToolCallbacks()[0];

    // The internal call is a bearer token OWNED by the internal client: the
    // gate reads the introspected 'client_id', not the token subject
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_mcp.tools.writeWithApproval"));
    DefaultOAuth2AuthenticatedPrincipal principal =
                                                  new DefaultOAuth2AuthenticatedPrincipal(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                                                                                          Map.of(OAuth2TokenIntrospectionClaimNames.SUB,
                                                                                                 MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                                                                                                 OAuth2TokenIntrospectionClaimNames.CLIENT_ID,
                                                                                                 MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID),
                                                                                          authorities);
    OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                                          "internal-token",
                                                          Instant.now(),
                                                          Instant.now().plusSeconds(60));
    SecurityContextHolder.getContext().setAuthentication(new BearerTokenAuthentication(principal, accessToken, authorities));
    ConversationState.setCurrent(new ConversationState(new Identity(USERNAME)));
    lenient().when(userAcl.getUserIdentity(USERNAME)).thenReturn(new Identity(USERNAME));
    lenient().when(mcpServerToolService.isAllowedTool(eq(TOOL_METHOD), any())).thenReturn(true);
    lenient().when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
    ConversationState.setCurrent(null);
  }

  @Test
  void call_approvalGatedTool_takesConversationFromInternalClientHeader() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(anyString(),
                                                eq(CONVERSATION_ID),
                                                eq(TOOL_METHOD),
                                                anyString(),
                                                eq(USERNAME))).thenReturn(true);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService).requestApproval(anyString(), eq(CONVERSATION_ID), eq(TOOL_METHOD), anyString(), eq(USERNAME));
  }

  @Test
  void call_approvalGatedTool_ignoresConversationHeaderWhenContextIdIsNotTheInternalOne() {// NOSONAR
    bindRequest("forged-context-id", CONVERSATION_ID);

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("conversation"), e.getMessage());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any());
  }

  @Test
  void call_approvalGatedTool_failsFastWithoutConversation() {// NOSONAR
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains(TOOL_METHOD), e.getMessage());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any());
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.APPROVAL_REQUEST));
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
  }

  @Test
  void call_readTool_executesWithoutConversation() {// NOSONAR
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any());
  }

  @Test
  void getToolCallbacks_wrapsPublicPluginMethods() {// NOSONAR
    assertEquals(1, service.getToolCallbacks().length);
    assertEquals(TOOL_METHOD, toolCallback.getToolDefinition().name());
  }

  private static UserToolExecution argThatIs(UserToolRequestType type) {
    return org.mockito.ArgumentMatchers.argThat(execution -> execution != null && execution.getToolExecutionType() == type);
  }

  private static void bindRequest(String contextId, String conversationId) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, contextId);
    request.addHeader(TOOL_CONTEXT_CONVERSATION_ID_PARAM, conversationId);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  public static class GreetingToolPlugin implements McpToolPlugin {

    public String greet(String name) {
      return "Hello " + name;
    }

  }

}

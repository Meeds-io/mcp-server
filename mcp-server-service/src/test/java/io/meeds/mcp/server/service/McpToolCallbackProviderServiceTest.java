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
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ACTOR_AGENT_NAME_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ACTOR_KIND_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ACTOR_USER_NAME_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_AGENT_CHAIN_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_AGENT_NAME_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_CONVERSATION_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ID;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ON_BEHALF_OF_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ORIGIN_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_SPACE_SCOPE_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_RETRY_MESSAGE_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_TURN_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_USER_NAME_PARAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.mcp.server.constant.McpToolGrantOwnerType;
import io.meeds.mcp.server.constant.Origin;
import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.ActingIdentity;
import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.model.McpToolGrantRequest;
import io.meeds.mcp.server.model.SpaceScopeDeclaration;
import io.meeds.mcp.server.model.SpaceScopeRefusedException;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.model.UserToolRefusedException;
import io.meeds.mcp.server.model.UserToolDeniedException;
import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.plugin.SpaceScopeResolver;

import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

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

  @Mock
  private McpToolGrantService           mcpToolGrantService;

  @Mock
  private ObjectProvider<SpaceScopeResolver> spaceScopeResolvers;

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
                                                 List.of(new GreetingToolPlugin()),
                                                 mcpToolGrantService,
                                                 new McpToolSpaceScopeService(spaceScopeResolvers));
    lenient().when(spaceScopeResolvers.orderedStream()).thenAnswer(invocation -> Stream.empty());
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
                                                eq(CONVERSATION_ID), any(),
                                                eq(TOOL_METHOD),
                                                anyString(),
                                                eq(USERNAME),
                                                any(),
                                                anyBoolean(),
                                                any())).thenReturn(true);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID), any(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(),
                                                   anyBoolean(),
                                                   any());
  }

  @Test
  void call_approvalGatedTool_ignoresConversationHeaderWhenContextIdIsNotTheInternalOne() {// NOSONAR
    bindRequest("forged-context-id", CONVERSATION_ID);

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("conversation"), e.getMessage());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void call_approvalGatedTool_failsFastWithoutConversation() {// NOSONAR
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains(TOOL_METHOD), e.getMessage());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.APPROVAL_REQUEST));
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
  }

  @Test
  void call_readTool_executesWithoutConversation() {// NOSONAR
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * A standing approval covering the call lets it run without a card: the run
   * is traced as granted with the grant id and owner type, and its use is
   * recorded.
   */
  @Test
  void call_coveredByGrant_runsWithoutCardAndTracesTheGrant() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    McpToolGrant grant = McpToolGrant.builder().id(7L).ownerType(McpToolGrantOwnerType.PLATFORM).build();
    when(mcpToolGrantService.findApplicableGrant(any(), any())).thenReturn(grant);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(mcpToolGrantService).recordUse(eq(grant), any());
    verify(mcpToolApprovalService).traceToolExecution(org.mockito.ArgumentMatchers.argThat(execution -> execution != null
        && execution.getToolExecutionType() == UserToolRequestType.TOOL_EXECUTION_GRANTED
        && Long.valueOf(7L).equals(execution.getGrantId())
        && "PLATFORM".equals(execution.getGrantOwnerType())));
  }

  /**
   * The grant decision gets the call as the server resolved it: user, snake
   * case tool name, trusted agent and conversation headers, the client, the
   * retry flag, and the arguments as the tool receives them.
   */
  @Test
  void call_grantDecisionGetsTheServerResolvedCall() {// NOSONAR
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    request.addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "agent-1");
    request.addHeader(TOOL_CONTEXT_RETRY_MESSAGE_ID_PARAM, "12");
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call("{\"user_name\":\"Bob\"}");

    ArgumentCaptor<McpToolGrantRequest> grantRequest = ArgumentCaptor.forClass(McpToolGrantRequest.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
    verify(mcpToolGrantService).findApplicableGrant(grantRequest.capture(), arguments.capture());
    McpToolGrantRequest call = grantRequest.getValue();
    assertEquals(USERNAME, call.username());
    assertEquals(TOOL_METHOD, call.toolName());
    assertEquals("agent-1", call.agentNameId());
    assertEquals(CONVERSATION_ID, call.conversationId());
    assertEquals(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, call.clientId());
    assertTrue(call.retry());
    assertEquals(Map.of("userName", "Bob"), arguments.getValue());
  }

  /**
   * A call carrying a 2 MB image runs the tool, and is decided on, with the
   * full input: only what is published or logged is shortened. Mutant: the
   * success log written with the input the tool runs on.
   */
  @Test
  void call_imageArgumentReachesTheToolAndTheGrantDecisionWhole() {// NOSONAR
    String image = "data:image/png;base64," + "A".repeat(2_796_203);
    String input = "{\"image_base64\":\"" + image + "\"}";
    when(mcpServerToolService.getToolDefinitionByMethodName("upload")).thenReturn(new SimpleToolDefinition("upload",
                                                                                                          "Upload",
                                                                                                          "Uploads an image",
                                                                                                          "{\"type\":\"object\",\"properties\":{\"imageBase64\":{\"type\":\"string\"}}}",
                                                                                                          true,
                                                                                                          false,
                                                                                                          null));
    lenient().when(mcpServerToolService.isAllowedTool(eq("upload"), any())).thenReturn(true);
    lenient().when(mcpServerToolService.isRequireApproval(eq("upload"), any())).thenReturn(true);
    ToolCallback uploadCallback = new McpToolCallbackProviderService(applicationContext,
                                                                     mcpServerToolService,
                                                                     mcpToolApprovalService,
                                                                     userAcl,
                                                                     List.of(new UploadToolPlugin()),
                                                                     mcpToolGrantService,
                                                                     new McpToolSpaceScopeService(spaceScopeResolvers)).getToolCallbacks()[0];
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    ListAppender<ILoggingEvent> logged = captureLogs();
    String output;
    try {
      output = uploadCallback.call(input);
    } finally {
      releaseLogs(logged);
    }

    assertEquals("\"received " + image.length() + "\"", output);
    assertTrue(logged.list.stream().anyMatch(event -> event.getFormattedMessage().contains("[image, ")), logged.list::toString);
    assertTrue(logged.list.stream().noneMatch(event -> event.getFormattedMessage().contains("AAAA")), "image logged");
    ArgumentCaptor<McpToolGrantRequest> grantRequest = ArgumentCaptor.forClass(McpToolGrantRequest.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
    verify(mcpToolGrantService).findApplicableGrant(grantRequest.capture(), arguments.capture());
    assertEquals(input, grantRequest.getValue().toolInput());
    assertEquals(Map.of("imageBase64", image), arguments.getValue());
    verify(mcpToolApprovalService).requestApproval(any(), any(), any(), eq("upload"), eq(input), any(), any(), anyBoolean(), any());
  }

  /**
   * A call whose model set an argument to null runs, and the tool receives
   * the null. Mutant: the arguments merged with a collector that rejects a
   * null value, which fails the call.
   */
  @Test
  void call_argumentSetToNull_runsWithTheNull() {// NOSONAR
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);

    String output = toolCallback.call("{\"name\":null}");

    assertTrue(output.contains("Hello null"), output);
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
  }

  /**
   * The standing-approval decision of a call carrying a null argument gets
   * its arguments, the null kept, so an argument limit can still apply.
   * Mutant: the arguments lost (null), so no standing approval ever applies.
   */
  @Test
  void call_argumentSetToNull_grantDecisionGetsTheArguments() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call("{\"name\":\"Bob\",\"title\":null}");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
    verify(mcpToolGrantService).findApplicableGrant(any(), arguments.capture());
    Map<String, Object> expected = new HashMap<>();
    expected.put("name", "Bob");
    expected.put("title", null);
    assertEquals(expected, arguments.getValue());
  }

  /**
   * When a snake-case and a camel-case key name the same argument, a value
   * wins over a null whatever their order. Mutants: the later key always
   * wins; the first key always wins.
   */
  @Test
  void call_sameArgumentTwice_aValueWinsOverANull() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call("{\"user_name\":null,\"userName\":\"Bob\"}");
    ConversationState.setCurrent(new ConversationState(new Identity(USERNAME)));
    toolCallback.call("{\"userName\":\"Bob\",\"user_name\":null}");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
    verify(mcpToolGrantService, times(2)).findApplicableGrant(any(), arguments.capture());
    assertEquals(Map.of("userName", "Bob"), arguments.getAllValues().get(0));
    assertEquals(Map.of("userName", "Bob"), arguments.getAllValues().get(1));
  }

  /**
   * The agent and retry headers of a caller that isn't the internal client
   * are ignored.
   */
  @Test
  void call_agentAndRetryHeadersIgnoredOutsideTheInternalGate() {// NOSONAR
    MockHttpServletRequest request = bindRequest("forged-context-id", CONVERSATION_ID);
    request.addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "agent-1");
    request.addHeader(TOOL_CONTEXT_RETRY_MESSAGE_ID_PARAM, "12");

    assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    ArgumentCaptor<McpToolGrantRequest> grantRequest = ArgumentCaptor.forClass(McpToolGrantRequest.class);
    verify(mcpToolGrantService).findApplicableGrant(grantRequest.capture(), any());
    assertNull(grantRequest.getValue().agentNameId());
    assertNull(grantRequest.getValue().conversationId());
    assertFalse(grantRequest.getValue().retry());
  }

  /**
   * The chat turn of the internal client's request reaches the card; the call
   * runs the same way, the turn id deciding nothing.
   */
  @Test
  void call_approvalGatedTool_passesTheTurnIdFromTheInternalClientHeaderToTheCard() {// NOSONAR
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    request.addHeader(TOOL_CONTEXT_TURN_ID_PARAM, "turn-7");
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID),
                                                   eq("turn-7"),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(),
                                                   anyBoolean(),
                                                   any());
  }

  @Test
  void call_approvalGatedTool_withoutTurnIdHeader_asksWithNoTurnId() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID),
                                                   isNull(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(),
                                                   anyBoolean(),
                                                   any());
  }

  /**
   * Without a grant, the card offers "Always allow" when a grant store is
   * installed, with the limit the tool's evaluator proposes.
   */
  @Test
  void call_withoutGrant_cardOffersAlwaysAllow() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    McpToolGrantConstraint constraint = new McpToolGrantConstraint(McpToolGrantConstraint.EMAIL_DOMAIN_KIND, "example.com");
    when(mcpToolGrantService.isGrantStoreAvailable()).thenReturn(true);
    when(mcpToolGrantService.allowsStandingApproval(eq(TOOL_METHOD), any())).thenReturn(true);
    when(mcpToolGrantService.proposeConstraint(eq(TOOL_METHOD), any())).thenReturn(constraint);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call(TOOL_INPUT);

    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID), any(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(McpToolGrantRequest.class),
                                                   eq(true),
                                                   eq(constraint));
  }

  /**
   * A call the tool never lets run unasked shows a card without "Always
   * allow".
   */
  @Test
  void call_callTheToolForbidsGrantsFor_cardDoesntOfferAlwaysAllow() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolGrantService.isGrantStoreAvailable()).thenReturn(true);
    when(mcpToolGrantService.allowsStandingApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call(TOOL_INPUT);

    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID), any(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(),
                                                   eq(false),
                                                   eq(null));
  }

  /**
   * An "always ask" tool shows its card even when a grant would cover it, and
   * the card never offers "Always allow".
   */
  @Test
  void call_alwaysAskTool_neverUsesNorOffersAGrant() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpServerToolService.isAlwaysAsk(TOOL_METHOD)).thenReturn(true);
    when(mcpServerToolService.hasApprovalScope(any())).thenReturn(true);
    lenient().when(mcpToolGrantService.isGrantStoreAvailable()).thenReturn(true);
    lenient().when(mcpToolGrantService.findApplicableGrant(any(), any())).thenReturn(McpToolGrant.builder().id(1L).ownerType(McpToolGrantOwnerType.USER).build());
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call(TOOL_INPUT);

    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID), any(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(),
                                                   eq(false),
                                                   eq(null));
  }

  /**
   * An "always ask" tool is asked even when its definition doesn't require
   * approval.
   */
  @Test
  void call_alwaysAskTool_asksEvenWithoutRequireApproval() {// NOSONAR
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    lenient().when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    when(mcpServerToolService.isAlwaysAsk(TOOL_METHOD)).thenReturn(true);
    when(mcpServerToolService.hasApprovalScope(any())).thenReturn(true);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(false);

    assertThrows(io.meeds.mcp.server.model.UserToolDeniedException.class, () -> toolCallback.call(TOOL_INPUT));
  }

  /**
   * An "always ask" tool is refused to a caller that can't be asked (no
   * approval scope, as external MCP clients' plain write tokens), even though
   * such a caller runs other write tools without a card.
   */
  @Test
  void call_alwaysAskTool_refusedWithoutApprovalScope() {// NOSONAR
    when(mcpServerToolService.isAlwaysAsk(TOOL_METHOD)).thenReturn(true);
    when(mcpServerToolService.hasApprovalScope(any())).thenReturn(false);

    IllegalAccessException e = assertThrows(IllegalAccessException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("every time"), e.getMessage());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_START));
  }

  /**
   * A plain write caller of a tool that isn't "always ask" keeps running it
   * without a card, as before standing approvals.
   */
  @Test
  void call_plainWriteCaller_runsUnaskedWhenNotAlwaysAsk() {// NOSONAR
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
  }

  /**
   * R3 on the server: an internal client sending an agent account acting for
   * a person is refused before anything runs, and traced as an error.
   */
  @Test
  void call_agentAccountActingForAPerson_refusedAndTracedAsError() {// NOSONAR
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "AGENT", "agent-mail", "EMAIL_ASSISTANT", USERNAME, "[\"EMAIL_ASSISTANT\"]", "MENTION", null);

    assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_START));
  }

  /**
   * R1: the call runs as the acting identity's subject and nothing else; an
   * identity naming another account than the one the call runs as is
   * refused, traced as an error.
   */
  @Test
  void call_actingIdentityForAnotherAccount_refusedAndTracedAsError() {// NOSONAR
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, "mary", "USER", "mary", null, "mary", "[]", "CHAT", null);

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("mary"), e.getMessage());
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_START));
  }

  /**
   * The acting identity the server resolved reaches the grant decision (its
   * grant scope as the agent), the card (pushed to the person the call is
   * for) and every step of the trace.
   */
  @Test
  void call_actingIdentityReachesGrantCardAndTrace() {// NOSONAR
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "USER", USERNAME, null, USERNAME, "[\"COMPLETION\",\"EMAIL_ASSISTANT\"]", "CHAT", "EMAIL_ASSISTANT");
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

    toolCallback.call(TOOL_INPUT);

    ActingIdentity expected = ActingIdentity.person(USERNAME, Origin.CHAT).withHop("COMPLETION").withHop("EMAIL_ASSISTANT");
    ArgumentCaptor<McpToolGrantRequest> grantRequest = ArgumentCaptor.forClass(McpToolGrantRequest.class);
    verify(mcpToolGrantService).findApplicableGrant(grantRequest.capture(), any());
    assertEquals(expected, grantRequest.getValue().actingIdentity());
    assertEquals("EMAIL_ASSISTANT", grantRequest.getValue().agentNameId());
    assertEquals(USERNAME, grantRequest.getValue().username());
    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID), any(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   eq(grantRequest.getValue()),
                                                   anyBoolean(),
                                                   any());
    verify(mcpToolApprovalService).traceToolExecution(org.mockito.ArgumentMatchers.argThat(execution -> execution != null
        && execution.getToolExecutionType() == UserToolRequestType.TOOL_EXECUTION_FINISHED
        && USERNAME.equals(execution.getUsername())
        && USERNAME.equals(execution.getActorUserName())
        && "USER".equals(execution.getActorKind())
        && USERNAME.equals(execution.getOnBehalfOf())
        && List.of("COMPLETION", "EMAIL_ASSISTANT").equals(execution.getAgentChain())
        && "CHAT".equals(execution.getOrigin())));
  }

  /**
   * T3: an agent account acting as itself has nobody to approve its write;
   * the grant lookup comes first, then the call is refused before any card or
   * wait, traced as denied.
   */
  @Test
  void call_agentActingAsItselfWithoutGrant_refusedAsDeniedWithoutCard() {// NOSONAR
    bindAgentAccount();
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addAgentAccountHeaders(request);

    UserToolRefusedException e = assertThrows(UserToolRefusedException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("nobody"), e.getMessage());
    verify(mcpToolGrantService).findApplicableGrant(any(), any());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_DENIED));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
  }

  /**
   * An unattended agent-account run carries no conversation: the existing
   * no-conversation refusal fires first, and for an agent acting as itself it
   * is traced as denied.
   */
  @Test
  void call_agentActingAsItselfWithoutConversation_refusedAsDenied() {// NOSONAR
    bindAgentAccount();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, TOOL_CONTEXT_ID);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    addAgentAccountHeaders(request);

    UserToolRefusedException e = assertThrows(UserToolRefusedException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("conversation"), e.getMessage());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_DENIED));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
  }

  /**
   * An agent account acting as itself writes under its own grant: the grant
   * lookup decides before any refusal.
   */
  @Test
  void call_agentActingAsItselfUnderItsGrant_runs() {// NOSONAR
    bindAgentAccount();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, TOOL_CONTEXT_ID);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    addAgentAccountHeaders(request);
    McpToolGrant grant = McpToolGrant.builder().id(8L).ownerType(McpToolGrantOwnerType.USER).build();
    when(mcpToolGrantService.findApplicableGrant(any(), any())).thenReturn(grant);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    ArgumentCaptor<McpToolGrantRequest> grantRequest = ArgumentCaptor.forClass(McpToolGrantRequest.class);
    verify(mcpToolGrantService).findApplicableGrant(grantRequest.capture(), any());
    assertEquals("agent-user:EMAIL_ASSISTANT", grantRequest.getValue().agentNameId());
    assertEquals("agent-mail", grantRequest.getValue().username());
  }

  /**
   * An agent account's call to a require_approval tool needs its own grant
   * whatever the internal client's scope: under a plain write scope, which
   * skips the approval branch, an ungranted call is refused before it runs,
   * traced as denied, with the no-approver explanation. Mutant: the agent
   * check removed, which ran it ungranted.
   */
  @Test
  void call_agentGatedToolUnderPlainWriteScopeWithoutGrant_refusedAsDenied() {// NOSONAR
    bindAgentAccount();
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    when(mcpServerToolService.isApprovalGatedTool(TOOL_METHOD)).thenReturn(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, TOOL_CONTEXT_ID);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    addAgentAccountHeaders(request);

    UserToolRefusedException e = assertThrows(UserToolRefusedException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("nobody can approve it"), e.getMessage());
    ArgumentCaptor<McpToolGrantRequest> grantRequest = ArgumentCaptor.forClass(McpToolGrantRequest.class);
    verify(mcpToolGrantService).findApplicableGrant(grantRequest.capture(), any());
    assertEquals("agent-user:EMAIL_ASSISTANT", grantRequest.getValue().agentNameId());
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_DENIED));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_START));
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * An agent account's call to a require_approval tool under a plain write
   * scope runs under its own grant, traced as granted, its use recorded.
   * Mutant: the grant ignored, which refused every such call.
   */
  @Test
  void call_agentGatedToolUnderPlainWriteScopeWithGrant_runs() {// NOSONAR
    bindAgentAccount();
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    when(mcpServerToolService.isApprovalGatedTool(TOOL_METHOD)).thenReturn(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, TOOL_CONTEXT_ID);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    addAgentAccountHeaders(request);
    McpToolGrant grant = McpToolGrant.builder().id(9L).ownerType(McpToolGrantOwnerType.USER).build();
    when(mcpToolGrantService.findApplicableGrant(any(), any())).thenReturn(grant);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolGrantService).recordUse(eq(grant), any());
    verify(mcpToolApprovalService).traceToolExecution(org.mockito.ArgumentMatchers.argThat(execution -> execution != null
        && execution.getToolExecutionType() == UserToolRequestType.TOOL_EXECUTION_GRANTED
        && Long.valueOf(9L).equals(execution.getGrantId())));
  }

  /**
   * An agent account's call to a tool that asks for no approval, an ungated
   * write such as setting a category, runs as a person's would: no grant is
   * looked up. Mutant: every agent call gated.
   */
  @Test
  void call_agentUngatedWriteUnderPlainWriteScope_runsWithoutGrantLookup() {// NOSONAR
    bindAgentAccount();
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    when(mcpServerToolService.isApprovalGatedTool(TOOL_METHOD)).thenReturn(false);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, TOOL_CONTEXT_ID);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    addAgentAccountHeaders(request);

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
  }

  /**
   * A person's call to a require_approval tool under a plain write scope
   * keeps today's behaviour exactly: it runs unasked, with no grant lookup.
   * Mutant: the agent check applied to every actor.
   */
  @Test
  void call_personGatedToolUnderPlainWriteScope_unchanged() {// NOSONAR
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    lenient().when(mcpServerToolService.isApprovalGatedTool(TOOL_METHOD)).thenReturn(true);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "USER", USERNAME, null, USERNAME, "[\"COMPLETION\"]", "CHAT", "COMPLETION");

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_DENIED));
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_FINISHED));
  }

  /**
   * Binds the agent account as the user the call runs as.
   */
  private void bindAgentAccount() {
    ConversationState.setCurrent(new ConversationState(new Identity("agent-mail")));
    when(userAcl.getUserIdentity("agent-mail")).thenReturn(new Identity("agent-mail"));
  }

  /**
   * Adds the headers of the agent account acting as itself, running a
   * proposal its owner approved: a run that reads for a mention is limited to
   * a space, where no write runs.
   *
   * @param request the request
   */
  private static void addAgentAccountHeaders(MockHttpServletRequest request) {
    addIdentityHeaders(request,
                       "agent-mail",
                       "AGENT",
                       "agent-mail",
                       "EMAIL_ASSISTANT",
                       null,
                       "[\"EMAIL_ASSISTANT\"]",
                       "PROPOSAL",
                       "agent-user:EMAIL_ASSISTANT");
  }

  /**
   * Adds the internal client's user and acting-identity headers, each one
   * only when given.
   *
   * @param request       the request
   * @param userName      the user header
   * @param kind          the actor kind
   * @param actorUserName the actor login
   * @param actorAgent    the actor agent
   * @param onBehalfOf    the person
   * @param chain         the chain
   * @param origin        the origin
   * @param grantScope    the grant scope, sent as the agent header
   */
  private static void addIdentityHeaders(MockHttpServletRequest request, // NOSONAR the headers, each one its own
                                         String userName,
                                         String kind,
                                         String actorUserName,
                                         String actorAgent,
                                         String onBehalfOf,
                                         String chain,
                                         String origin,
                                         String grantScope) {
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put(TOOL_CONTEXT_USER_NAME_PARAM, userName);
    headers.put(TOOL_CONTEXT_ACTOR_KIND_PARAM, kind);
    headers.put(TOOL_CONTEXT_ACTOR_USER_NAME_PARAM, actorUserName);
    headers.put(TOOL_CONTEXT_ACTOR_AGENT_NAME_ID_PARAM, actorAgent);
    headers.put(TOOL_CONTEXT_ON_BEHALF_OF_PARAM, onBehalfOf);
    headers.put(TOOL_CONTEXT_AGENT_CHAIN_PARAM, chain);
    headers.put(TOOL_CONTEXT_ORIGIN_PARAM, origin);
    headers.put(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, grantScope);
    headers.forEach((name, value) -> {
      if (value != null) {
        request.addHeader(name, value);
      }
    });
  }

  /**
   * A denied call carrying a 2 MB image writes no image to the log, and the
   * user's denial isn't logged at WARN or above. Mutants: the input logged
   * as received; the denial logged at WARN.
   */
  @Test
  void call_deniedImageCall_logsNoImageAndNoWarning() {// NOSONAR
    String image = "B".repeat(2_796_203);
    ToolCallback uploadCallback = uploadCallback(new UploadToolPlugin());
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(false);
    ListAppender<ILoggingEvent> logged = captureLogs();

    try {
      assertThrows(UserToolDeniedException.class, () -> uploadCallback.call("{\"image_base64\":\"" + image + "\"}"));
    } finally {
      releaseLogs(logged);
    }

    assertFalse(logged.list.isEmpty());
    assertTrue(logged.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)), logged.list::toString);
    assertTrue(logged.list.stream().noneMatch(event -> event.getFormattedMessage().contains("BBBB")), "image logged");
    assertTrue(logged.list.stream().anyMatch(event -> event.getFormattedMessage().contains("[image, ")), logged.list::toString);
  }

  /**
   * A failing call carrying a 2 MB image logs no image at WARN nor ERROR, and
   * the explanation sent to the model carries the placeholder instead of it.
   * Mutants: the input unredacted in the explanation; in the WARN; in the
   * ERROR.
   */
  @Test
  void call_failingImageCall_explainsAndLogsWithoutTheImage() {// NOSONAR
    String image = "C".repeat(2_796_203);
    ToolCallback uploadCallback = uploadCallback(new FailingUploadToolPlugin());
    bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);
    ListAppender<ILoggingEvent> logged = captureLogs();

    IllegalStateException e;
    try {
      e = assertThrows(IllegalStateException.class, () -> uploadCallback.call("{\"image_base64\":\"" + image + "\"}"));
    } finally {
      releaseLogs(logged);
    }

    assertTrue(e.getMessage().contains("[image, "), e.getMessage());
    assertFalse(e.getMessage().contains("CCCC"), "image in the explanation");
    assertTrue(logged.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)));
    assertTrue(logged.list.stream().anyMatch(event -> event.getLevel() == Level.WARN));
    assertTrue(logged.list.stream().noneMatch(event -> event.getFormattedMessage().contains("CCCC")), "image logged");
  }

  /**
   * Builds the callback of an upload tool that requires approval.
   *
   * @param plugin the upload tool
   * @return its callback
   */
  private ToolCallback uploadCallback(McpToolPlugin plugin) {
    when(mcpServerToolService.getToolDefinitionByMethodName("upload")).thenReturn(new SimpleToolDefinition("upload",
                                                                                                          "Upload",
                                                                                                          "Uploads an image",
                                                                                                          "{\"type\":\"object\",\"properties\":{\"imageBase64\":{\"type\":\"string\"}}}",
                                                                                                          true,
                                                                                                          false,
                                                                                                          null));
    lenient().when(mcpServerToolService.isAllowedTool(eq("upload"), any())).thenReturn(true);
    lenient().when(mcpServerToolService.isRequireApproval(eq("upload"), any())).thenReturn(true);
    return new McpToolCallbackProviderService(applicationContext,
                                              mcpServerToolService,
                                              mcpToolApprovalService,
                                              userAcl,
                                              List.of(plugin),
                                              mcpToolGrantService,
                                              new McpToolSpaceScopeService(spaceScopeResolvers)).getToolCallbacks()[0];
  }

  /**
   * Starts capturing what the service logs, down to DEBUG.
   *
   * @return the capture
   */
  private ListAppender<ILoggingEvent> captureLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(McpToolCallbackProviderService.class);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.setLevel(Level.DEBUG);
    logger.addAppender(logged);
    return logged;
  }

  /**
   * Stops a capture started by {@link #captureLogs()}.
   *
   * @param logged the capture
   */
  private void releaseLogs(ListAppender<ILoggingEvent> logged) {
    Logger logger = (Logger) LoggerFactory.getLogger(McpToolCallbackProviderService.class);
    logger.detachAppender(logged);
    logger.setLevel(null);
  }

  /**
   * A call limited to a space, to a tool that declares no space scope, is
   * refused before any card or grant lookup and before the tool runs, traced
   * as out of scope (never as denied, which reads as the user's refusal) and
   * not logged as an incident, and the model reads which space it may reach.
   * Mutants: the space scope check not called from the wrapper; the refusal
   * logged at WARN or ERROR; the refusal traced as denied.
   */
  @Test
  void call_spaceScopedCallToAnUndeclaredTool_refusedAsOutOfScope() {// NOSONAR
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "USER", USERNAME, null, USERNAME, "[\"COMPLETION\"]", "MENTION", "COMPLETION");
    request.addHeader(TOOL_CONTEXT_SPACE_SCOPE_PARAM, "12");
    Logger logger = (Logger) LoggerFactory.getLogger(McpToolCallbackProviderService.class);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);

    SpaceScopeRefusedException e;
    try {
      e = assertThrows(SpaceScopeRefusedException.class, () -> toolCallback.call(TOOL_INPUT));
    } finally {
      logger.detachAppender(logged);
    }

    assertTrue(logged.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)), logged.list::toString);

    assertTrue(e.getMessage().contains("space with id 12"), e.getMessage());
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_OUT_OF_SCOPE));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_DENIED));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_ERROR));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_START));
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
  }

  /**
   * A call limited to a space, to a read tool whose space argument is absent,
   * runs with the call's space filled in, and the trace records the arguments
   * it ran with. Mutant: the wrapper keeps the arguments as sent.
   */
  @Test
  void call_spaceScopedCallWithoutItsSpace_runsWithTheSpaceFilledIn() {// NOSONAR
    SimpleToolDefinition read = new SimpleToolDefinition(TOOL_METHOD,
                                                         "Greet",
                                                         "Greets someone",
                                                         "{}",
                                                         false,
                                                         false,
                                                         new ToolAnnotations("Greet", true, false, true, false, null));
    read.setSpaceScope(new SpaceScopeDeclaration("name", "use_scope", null, null, false, null, null, false, null));
    when(mcpServerToolService.getToolDefinitionByMethodName(TOOL_METHOD)).thenReturn(read);
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "USER", USERNAME, null, USERNAME, "[\"COMPLETION\"]", "MENTION", "COMPLETION");
    request.addHeader(TOOL_CONTEXT_SPACE_SCOPE_PARAM, "12");

    String output = toolCallback.call("{}");

    assertTrue(output.contains("Hello 12"), output);
    verify(mcpToolApprovalService).traceToolExecution(org.mockito.ArgumentMatchers.argThat(execution -> execution != null
        && execution.getToolExecutionType() == UserToolRequestType.TOOL_EXECUTION_START
        && "{\"name\":12}".equals(execution.getToolInput())));
  }

  /**
   * An approved proposal limited to a space runs a write declared in that
   * space only after the space scope check, and still through the standing
   * approvals and the card; the same write in another space is refused
   * before either. Mutants: the space scope check moved after the approval;
   * every write refused under a space scope.
   */
  @Test
  void call_spaceScopedProposalWrite_checkedThenStillAsked() {// NOSONAR
    SimpleToolDefinition write = new SimpleToolDefinition(TOOL_METHOD,
                                                          "Greet",
                                                          "Greets someone",
                                                          "{}",
                                                          true,
                                                          false,
                                                          new ToolAnnotations("Greet", false, false, false, false, null));
    write.setSpaceScope(new SpaceScopeDeclaration("name", null, null, null, false, null, null, false, null));
    when(mcpServerToolService.getToolDefinitionByMethodName(TOOL_METHOD)).thenReturn(write);
    when(mcpToolApprovalService.requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "USER", USERNAME, null, USERNAME, "[]", "PROPOSAL", null);
    request.addHeader(TOOL_CONTEXT_SPACE_SCOPE_PARAM, "12");

    assertThrows(SpaceScopeRefusedException.class, () -> toolCallback.call("{\"name\":13}"));
    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
    verify(mcpToolApprovalService, never()).requestApproval(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

    String output = toolCallback.call("{\"name\":12}");

    assertTrue(output.contains("Hello 12"), output);
    verify(mcpToolGrantService).findApplicableGrant(any(), any());
    verify(mcpToolApprovalService).requestApproval(anyString(),
                                                   eq(CONVERSATION_ID), any(),
                                                   eq(TOOL_METHOD),
                                                   anyString(),
                                                   eq(USERNAME),
                                                   any(),
                                                   anyBoolean(),
                                                   any());
  }

  /**
   * An agent account's approved proposal, limited to a space, to a write that
   * declares no space scope is refused although a grant covers it: the space
   * scope check runs before any grant lookup, the tool never runs, and
   * nothing is traced as started. Mutants: the space scope check moved after
   * the grant lookup; a covering grant letting an undeclared write run.
   */
  @Test
  void call_spaceScopedAgentWriteUndeclaredWithCoveringGrant_refusedBeforeTheGrant() {// NOSONAR
    bindAgentAccount();
    // lenient: the refusal comes before the approval and grant steps these set up, which is what is pinned
    lenient().when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    lenient().when(mcpServerToolService.isApprovalGatedTool(TOOL_METHOD)).thenReturn(true);
    McpToolGrant grant = McpToolGrant.builder().id(9L).ownerType(McpToolGrantOwnerType.USER).build();
    lenient().when(mcpToolGrantService.findApplicableGrant(any(), any())).thenReturn(grant);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addAgentAccountHeaders(request);
    request.addHeader(TOOL_CONTEXT_SPACE_SCOPE_PARAM, "12");

    SpaceScopeRefusedException e = assertThrows(SpaceScopeRefusedException.class, () -> toolCallback.call(TOOL_INPUT));

    assertTrue(e.getMessage().contains("space with id 12"), e.getMessage());
    verify(mcpToolGrantService, never()).findApplicableGrant(any(), any());
    verify(mcpToolGrantService, never()).recordUse(any(), any());
    verify(mcpToolApprovalService).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_OUT_OF_SCOPE));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_START));
    verify(mcpToolApprovalService, never()).traceToolExecution(argThatIs(UserToolRequestType.TOOL_EXECUTION_GRANTED));
  }

  /**
   * A call that isn't limited runs as before: an undeclared tool, its
   * arguments as sent, traced as sent. Mutant: the space scope check applied
   * to every call, which refuses it.
   */
  @Test
  void call_callWithoutSpaceScope_runsAsBefore() {// NOSONAR
    when(mcpServerToolService.isRequireApproval(eq(TOOL_METHOD), any())).thenReturn(false);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, CONVERSATION_ID);
    addIdentityHeaders(request, USERNAME, "USER", USERNAME, null, USERNAME, "[\"COMPLETION\"]", "CHAT", "COMPLETION");

    String output = toolCallback.call(TOOL_INPUT);

    assertTrue(output.contains("Hello Bob"), output);
    verify(mcpToolApprovalService).traceToolExecution(org.mockito.ArgumentMatchers.argThat(execution -> execution != null
        && execution.getToolExecutionType() == UserToolRequestType.TOOL_EXECUTION_START
        && TOOL_INPUT.equals(execution.getToolInput())));
    verify(spaceScopeResolvers, never()).orderedStream();
  }

  @Test
  void getToolCallbacks_wrapsPublicPluginMethods() {// NOSONAR
    assertEquals(1, service.getToolCallbacks().length);
    assertEquals(TOOL_METHOD, toolCallback.getToolDefinition().name());
  }

  private static UserToolExecution argThatIs(UserToolRequestType type) {
    return org.mockito.ArgumentMatchers.argThat(execution -> execution != null && execution.getToolExecutionType() == type);
  }

  /**
   * Binds a request carrying the internal client's context headers.
   *
   * @param contextId      the context id header
   * @param conversationId the conversation id header
   * @return the bound request, to add headers to
   */
  private static MockHttpServletRequest bindRequest(String contextId, String conversationId) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, contextId);
    request.addHeader(TOOL_CONTEXT_CONVERSATION_ID_PARAM, conversationId);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

  public static class UploadToolPlugin implements McpToolPlugin {

    public String upload(String imageBase64) {
      return "received " + imageBase64.length();
    }

  }

  public static class FailingUploadToolPlugin implements McpToolPlugin {

    public String upload(String imageBase64) {
      throw new UnsupportedOperationException("upload failed");
    }

  }

  public static class GreetingToolPlugin implements McpToolPlugin {

    public String greet(String name) {
      return "Hello " + name;
    }

  }

}

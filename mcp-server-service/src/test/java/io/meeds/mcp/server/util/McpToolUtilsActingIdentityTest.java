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

import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ACTOR_AGENT_NAME_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ACTOR_KIND_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ACTOR_USER_NAME_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_AGENT_CHAIN_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_AGENT_NAME_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ID;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ON_BEHALF_OF_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_ORIGIN_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_CONTEXT_USER_NAME_PARAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import io.meeds.mcp.server.constant.Origin;
import io.meeds.mcp.server.constant.PrincipalKind;
import io.meeds.mcp.server.model.ActingIdentity;

/**
 * Pins how the MCP server resolves the acting identity of a tool call
 * (EXO-91056): from the internal client's headers only, behind the gate of
 * the user header, with the legacy headers read the way today's calls mean
 * them, and never from what any other caller sends.
 */
class McpToolUtilsActingIdentityTest {

  private static final String USERNAME = "john";

  /**
   * Clears the request and the security context.
   */
  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  /**
   * The internal client's acting-identity headers are read as sent: actor,
   * person, chain (a JSON array), origin and grant scope.
   */
  @Test
  void internalClientHeadersAreRead() {
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                         McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, USERNAME);
    addIdentityHeaders(request, PrincipalKind.USER.name(), USERNAME, null, USERNAME, "[\"COMPLETION\",\"A,B\"]", "CHAT", "A,B");

    ActingIdentity identity = McpToolUtils.getCurrentActingIdentity();

    assertThat(identity).isEqualTo(ActingIdentity.person(USERNAME, Origin.CHAT).withHop("COMPLETION").withHop("A,B"));
    assertThat(identity.chain()).containsExactly("COMPLETION", "A,B");
  }

  /**
   * An internal client that sends only the legacy headers gets today's
   * meaning: a chat turn with the agent appended, or a source scope (a value
   * holding ':'), which is never an agent hop.
   */
  @Test
  void legacyHeadersAreReadAsToday() {
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                         McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);

    bindRequest(TOOL_CONTEXT_ID, USERNAME);
    assertThat(McpToolUtils.getCurrentActingIdentity()).isEqualTo(ActingIdentity.person(USERNAME, Origin.CHAT));

    bindRequest(TOOL_CONTEXT_ID, USERNAME).addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "COMPLETION");
    assertThat(McpToolUtils.getCurrentActingIdentity()).isEqualTo(ActingIdentity.person(USERNAME, Origin.CHAT).withHop("COMPLETION"));

    bindRequest(TOOL_CONTEXT_ID, USERNAME).addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "schedule:12");
    ActingIdentity schedule = McpToolUtils.getCurrentActingIdentity();
    assertThat(schedule.chain()).isEmpty();
    assertThat(schedule.grantScope()).isEqualTo("schedule:12");
    assertThat(schedule.origin()).isEqualTo(Origin.SCHEDULE);

    bindRequest(TOOL_CONTEXT_ID, USERNAME).addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "email-filter:42");
    ActingIdentity emailFilter = McpToolUtils.getCurrentActingIdentity();
    assertThat(emailFilter.chain()).isEmpty();
    assertThat(emailFilter.grantScope()).isEqualTo("email-filter:42");
    assertThat(emailFilter.origin()).isEqualTo(Origin.SOURCE);
  }

  /**
   * T13: a caller other than the internal client gets its token's user, an
   * empty chain and the external origin, whatever headers it sends, the
   * context id included.
   */
  @Test
  void forgedHeadersOfAUserTokenAreIgnored() {
    authenticateAsBearer("mary", "claude-desktop");
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, USERNAME);
    request.addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "EMAIL_ASSISTANT");
    addIdentityHeaders(request, PrincipalKind.AGENT.name(), "agent-mail", "EMAIL_ASSISTANT", null, "[\"EMAIL_ASSISTANT\"]", "MENTION",
                       "agent-user:EMAIL_ASSISTANT");

    ActingIdentity identity = McpToolUtils.getCurrentActingIdentity();

    assertThat(identity).isEqualTo(ActingIdentity.external("mary"));
    assertThat(identity.subject()).isEqualTo("mary");
    assertThat(identity.chain()).isEmpty();
    assertThat(identity.grantScope()).isNull();
    assertThat(identity.origin()).isEqualTo(Origin.EXTERNAL_MCP);
  }

  /**
   * The internal client's headers are trusted under the user header's gate
   * only: a wrong context id, or no user, resolves no identity.
   */
  @Test
  void internalClientWithoutTheGateResolvesNothing() {
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                         McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);
    MockHttpServletRequest request = bindRequest("forged-context-id", USERNAME);
    addIdentityHeaders(request, PrincipalKind.USER.name(), USERNAME, null, USERNAME, "[]", "CHAT", null);
    assertThat(McpToolUtils.getCurrentActingIdentity()).isNull();

    bindRequest(TOOL_CONTEXT_ID, null);
    assertThat(McpToolUtils.getCurrentActingIdentity()).isNull();

    SecurityContextHolder.clearContext();
    assertThat(McpToolUtils.getCurrentActingIdentity()).isNull();
  }

  /**
   * R3 on the server, and malformed headers: an agent account acting for a
   * person, an unknown kind or origin, a missing or comma-joined chain are
   * refused with an {@link IllegalStateException}.
   */
  @Test
  void refusedIdentityHeadersThrow() {
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                         McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);

    addIdentityHeaders(bindRequest(TOOL_CONTEXT_ID, USERNAME), "AGENT", "agent-mail", "EMAIL_ASSISTANT", USERNAME, "[\"EMAIL_ASSISTANT\"]",
                       "MENTION", null);
    assertThatThrownBy(McpToolUtils::getCurrentActingIdentity).isInstanceOf(IllegalStateException.class);

    addIdentityHeaders(bindRequest(TOOL_CONTEXT_ID, USERNAME), "ROBOT", USERNAME, null, USERNAME, "[]", "CHAT", null);
    assertThatThrownBy(McpToolUtils::getCurrentActingIdentity).isInstanceOf(IllegalStateException.class);

    addIdentityHeaders(bindRequest(TOOL_CONTEXT_ID, USERNAME), "USER", USERNAME, null, USERNAME, "[]", "TELEPATHY", null);
    assertThatThrownBy(McpToolUtils::getCurrentActingIdentity).isInstanceOf(IllegalStateException.class);

    addIdentityHeaders(bindRequest(TOOL_CONTEXT_ID, USERNAME), "USER", USERNAME, null, USERNAME, null, "CHAT", null);
    assertThatThrownBy(McpToolUtils::getCurrentActingIdentity).isInstanceOf(IllegalStateException.class);

    addIdentityHeaders(bindRequest(TOOL_CONTEXT_ID, USERNAME), "USER", USERNAME, null, USERNAME, "COMPLETION,EMAIL_ASSISTANT", "CHAT", null);
    assertThatThrownBy(McpToolUtils::getCurrentActingIdentity).isInstanceOf(IllegalStateException.class);
  }

  /**
   * The identity is resolved once per request: a tool reading it during the
   * call sees the one the server checked, even if the request changes.
   */
  @Test
  void identityIsResolvedOncePerRequest() {
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                         McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);
    MockHttpServletRequest request = bindRequest(TOOL_CONTEXT_ID, USERNAME);
    ActingIdentity first = McpToolUtils.getCurrentActingIdentity();

    request.addHeader(TOOL_CONTEXT_AGENT_NAME_ID_PARAM, "COMPLETION");

    assertThat(McpToolUtils.getCurrentActingIdentity()).isSameAs(first);
  }

  /**
   * Adds the acting-identity headers, each one only when given.
   *
   * @param request       the request
   * @param kind          the actor kind
   * @param actorUserName the actor login
   * @param actorAgent    the actor agent
   * @param onBehalfOf    the person
   * @param chain         the chain
   * @param origin        the origin
   * @param grantScope    the grant scope, sent as the agent header
   */
  private static void addIdentityHeaders(MockHttpServletRequest request, // NOSONAR the headers, each one its own
                                         String kind,
                                         String actorUserName,
                                         String actorAgent,
                                         String onBehalfOf,
                                         String chain,
                                         String origin,
                                         String grantScope) {
    Map<String, String> headers = new java.util.HashMap<>();
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
   * Authenticates with an introspected bearer token.
   *
   * @param principalName the token subject
   * @param clientId      the owning OAuth client
   */
  private static void authenticateAsBearer(String principalName, String clientId) {
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_" + McpToolUtils.TOOL_WRITE_APPROVE_SCOPE));
    DefaultOAuth2AuthenticatedPrincipal principal =
                                                  new DefaultOAuth2AuthenticatedPrincipal(principalName,
                                                                                          Map.of(OAuth2TokenIntrospectionClaimNames.SUB,
                                                                                                 principalName,
                                                                                                 OAuth2TokenIntrospectionClaimNames.CLIENT_ID,
                                                                                                 clientId),
                                                                                          authorities);
    OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                                          "token-" + principalName,
                                                          Instant.now(),
                                                          Instant.now().plusSeconds(60));
    SecurityContextHolder.getContext().setAuthentication(new BearerTokenAuthentication(principal, accessToken, authorities));
  }

  /**
   * Binds a fresh request with the context id and user headers.
   *
   * @param contextId the context id header
   * @param userName  the user header, null for none
   * @return the request, to add headers to
   */
  private static MockHttpServletRequest bindRequest(String contextId, String userName) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TOOL_CONTEXT_ID_PARAM, contextId);
    if (userName != null) {
      request.addHeader(TOOL_CONTEXT_USER_NAME_PARAM, userName);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

}

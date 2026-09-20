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

import static io.meeds.mcp.server.util.McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;
import org.springframework.test.util.ReflectionTestUtils;

import io.meeds.mcp.server.model.McpServerOAuthClientProperties;
import io.meeds.mcp.server.plugin.McpServerOauthOpaqueTokenIntrospector;
import io.meeds.mcp.server.service.McpInternalOAuthClientService;
import io.meeds.mcp.server.service.McpServerAudienceService;
import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.mcp.server.test.McpServiceIntegrationTestSupport;
import io.meeds.oauth2.server.service.OAuthClientService;

/**
 * Covers the MCP access gate at its primary enforcement point — the token
 * introspector — with the <b>real</b> {@link McpServerAudienceService} and the
 * real {@code UserACL} of a running kernel behind it.
 * <p>
 * The sibling suite {@code McpServerOAuthIntegrationTest} replaces the whole
 * introspector with a {@code @MockitoBean} so that it can drive MCP protocol
 * traffic, which means the door's own validations never run there. Here the
 * opposite trade is made: only the introspector's remote
 * {@link SpringOpaqueTokenIntrospector} delegate is stubbed — it is the call
 * to the authorization server, nothing more — so
 * {@code McpServerOauthOpaqueTokenIntrospector.introspect} runs its real
 * validations and the three links the gate depends on are joined for once:
 * the introspected subject, the audience resolution, and the platform
 * identity lookup that decides group membership.
 * <p>
 * The introspector under test is built here rather than autowired, because the
 * module's single Spring context substitutes a mock for that bean
 * ({@link McpServiceIntegrationTestSupport}, which also records why there can
 * be only one such context). Every collaborator it is given <em>is</em> the
 * context's real bean, the audience service and the {@code UserACL} behind it
 * first among them, so what the class does with them is what the deployed bean
 * does; only the wiring is the test's.
 */
@DisplayName("MCP audience gate at the door")
class McpServerAudienceGateIntegrationTest extends McpServiceIntegrationTestSupport {

  private static final String                   USERNAME             = "root";

  private static final String                   UNKNOWN_USER         = "no-such-user";

  private static final String                   EXCLUDING_GROUP      = "*:/platform/no-such-group";

  private static final String                   CLIENT_TEST_SECRET   = "test-secret";

  private static final String                   ACCESS_DENIED_ERROR  = "User is not allowed to use the MCP Server";

  @Autowired
  private McpServerAudienceService              audienceService;

  @Autowired
  private McpServerToolService                  mcpServerToolService;

  @Autowired
  private McpInternalOAuthClientService         internalOAuthClientService;

  @Autowired
  private McpServerOAuthClientProperties        oAuthClientProperties;

  @Autowired
  private OAuthClientService                    oAuthClientService;

  @Autowired
  private RegisteredClientRepository            registeredClientRepository;

  @Autowired
  private PasswordEncoder                       passwordEncoder;

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
  private String                                issuerUri;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                                serverAudience;

  private McpServerOauthOpaqueTokenIntrospector introspector;

  private SpringOpaqueTokenIntrospector         delegate;

  private String                                clientId;

  @BeforeEach
  @Override
  protected void setUp() {
    // no begin() here: McpServiceIntegrationTestSupport.beginRequest() already
    // opened the request lifecycle that endRequest() closes
    mcpServerToolService.enableMcpServer();
    this.delegate = mock(SpringOpaqueTokenIntrospector.class);
    this.introspector = realIntrospector();
    this.clientId = registerClient();
  }

  @AfterEach
  @Override
  protected void tearDown() {
    audienceService.savePermissions(List.of(McpServerAudienceService.DEFAULT_PERMISSION));
    mcpServerToolService.enableMcpServer();
  }

  @Test
  @DisplayName("The shipped default lets a real platform user through")
  void defaultAudienceLetsARealUserThrough() {
    stubIntrospection(USERNAME, clientId);

    OAuth2AuthenticatedPrincipal principal = introspector.introspect("token");

    // Resolved against the real UserACL: root is a member of /platform/users,
    // which the default audience names
    assertEquals(USERNAME, principal.getName());
    assertThat(principal.getAuthorities()).hasSize(1);
  }

  @Test
  @DisplayName("Narrowing the audience refuses a user the real UserACL says is outside it")
  void narrowedAudienceRefusesAUserOutsideIt() {
    audienceService.savePermissions(List.of(EXCLUDING_GROUP));
    stubIntrospection(USERNAME, clientId);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect("token"));

    assertEquals(ACCESS_DENIED_ERROR, exception.getError().getDescription());
  }

  @Test
  @DisplayName("A subject no platform user answers to is refused")
  void unknownUserIsRefused() {
    stubIntrospection(UNKNOWN_USER, clientId);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect("token"));

    assertEquals(ACCESS_DENIED_ERROR, exception.getError().getDescription());
  }

  @Test
  @DisplayName("The internal client passes an audience that excludes every human")
  void internalClientPassesAnAudienceExcludingEveryHuman() {
    audienceService.savePermissions(List.of(EXCLUDING_GROUP));
    // Subject is the client id, as it is for a client-credentials grant, and
    // the client_id claim names the internal client
    stubIntrospection(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);

    OAuth2AuthenticatedPrincipal principal = introspector.introspect("token");

    assertEquals(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, principal.getName());
  }

  @Test
  @DisplayName("A user token whose subject is the internal client id is still gated")
  void userTokenNamedLikeTheInternalClientIsStillGated() {
    audienceService.savePermissions(List.of(EXCLUDING_GROUP));
    // Same subject as above, but the token belongs to an ordinary client
    stubIntrospection(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, clientId);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect("token"));

    assertEquals(ACCESS_DENIED_ERROR, exception.getError().getDescription());
  }

  @Test
  @DisplayName("An audience naming a user by login lets exactly that user through")
  void audienceNamingAUserByLoginLetsThatUserThrough() {
    audienceService.savePermissions(List.of(EXCLUDING_GROUP, USERNAME));

    stubIntrospection(USERNAME, clientId);
    assertEquals(USERNAME, introspector.introspect("token").getName());

    stubIntrospection(UNKNOWN_USER, clientId);
    assertThrows(OAuth2AuthenticationException.class, () -> introspector.introspect("token"));
  }

  @Test
  @DisplayName("Narrowing the audience takes effect on the next request, without a restart")
  void narrowingTheAudienceTakesEffectWithoutARestart() {
    stubIntrospection(USERNAME, clientId);
    assertEquals(USERNAME, introspector.introspect("token").getName());

    audienceService.savePermissions(List.of(EXCLUDING_GROUP));

    // Same token, same introspector, next request: the door reads the audience
    // on every call and nothing above SettingService holds the previous one,
    // so an administrator's narrowing bites at once rather than at a restart
    // or at the expiry of a token already issued
    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect("token"));

    assertEquals(ACCESS_DENIED_ERROR, exception.getError().getDescription());
  }

  /**
   * Stubs the remote introspection call with the response shape the
   * authorization server produces: the subject in {@code sub}, and
   * {@code client_id} naming the client the token was issued to.
   *
   * @param subject       the token subject
   * @param tokenClientId the client the token was issued to
   */
  private void stubIntrospection(String subject, String tokenClientId) {
    OAuth2AuthenticatedPrincipal principal =
                                           new DefaultOAuth2AuthenticatedPrincipal(subject,
                                                                                   Map.of("sub",
                                                                                          subject,
                                                                                          "aud",
                                                                                          serverAudience,
                                                                                          "iss",
                                                                                          issuerUri,
                                                                                          "azp",
                                                                                          clientId,
                                                                                          "client_id",
                                                                                          tokenClientId,
                                                                                          "scope",
                                                                                          TOOL_READ_SCOPE),
                                                                                   List.of());
    when(delegate.introspect(anyString())).thenReturn(principal);
  }

  /**
   * Registers an ordinary external OAuth client the tokens can be attributed
   * to, so that the introspector's authorized-party validation passes and the
   * audience check is what decides the outcome.
   *
   * @return the registered client id
   */
  private String registerClient() {
    String id = "mcp-audience-gate-" + UUID.randomUUID();
    RegisteredClient client = RegisteredClient.withId(id)
                                              .clientId(id)
                                              .clientSecret(passwordEncoder.encode(CLIENT_TEST_SECRET)) // NOSONAR
                                              .clientName("MCP audience gate test client")
                                              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                                              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                                              .scope(OidcScopes.OPENID)
                                              .scope(TOOL_READ_SCOPE)
                                              .clientSettings(ClientSettings.builder().build())
                                              .tokenSettings(TokenSettings.builder()
                                                                          .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                                                                          .accessTokenTimeToLive(Duration.ofMinutes(5))
                                                                          .build())
                                              .build();
    registeredClientRepository.save(client);
    return id;
  }

  @Test
  @DisplayName("A globally disabled MCP server is refused at the door, not at the tool")
  void globallyDisabledMcpServerIsRefusedAtTheDoor() {
    mcpServerToolService.disableMcpServer();
    stubIntrospection(USERNAME, clientId);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect("token"));

    // The door asks isMcpServerEnabledForUser, so it refuses on the global
    // half as well as on the audience; asking the audience alone would have
    // let this token open a session on a switched-off instance
    assertEquals(ACCESS_DENIED_ERROR, exception.getError().getDescription());
  }

  @Test
  @DisplayName("The internal client passes a globally disabled MCP server, as EVA depends on")
  void internalClientPassesAGloballyDisabledMcpServer() {
    mcpServerToolService.disableMcpServer();
    stubIntrospection(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);

    OAuth2AuthenticatedPrincipal principal = introspector.introspect("token");

    assertEquals(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, principal.getName());
  }

  /**
   * Builds the class under test with the context's own collaborators, and with
   * the remote introspection call — and only it — stubbed.
   *
   * @return a real introspector, wired as the container wires the bean
   */
  private McpServerOauthOpaqueTokenIntrospector realIntrospector() {
    McpServerOauthOpaqueTokenIntrospector realOne = new McpServerOauthOpaqueTokenIntrospector();
    ReflectionTestUtils.setField(realOne, "aiOAuthService", internalOAuthClientService);
    ReflectionTestUtils.setField(realOne, "oAuthClientProperties", oAuthClientProperties);
    ReflectionTestUtils.setField(realOne, "oAuthClientService", oAuthClientService);
    ReflectionTestUtils.setField(realOne, "mcpServerToolService", mcpServerToolService);
    ReflectionTestUtils.setField(realOne, "issuerUri", issuerUri);
    ReflectionTestUtils.setField(realOne, "serverAudience", serverAudience);
    ReflectionTestUtils.setField(realOne, "delegate", delegate);
    return realOne;
  }

}

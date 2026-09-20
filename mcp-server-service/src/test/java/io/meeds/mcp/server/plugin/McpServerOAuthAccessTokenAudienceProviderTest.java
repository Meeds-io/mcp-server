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
package io.meeds.mcp.server.plugin;

import static io.meeds.mcp.server.util.McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.test.util.ReflectionTestUtils;

import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.oauth2.server.configuration.plugin.OAuthAccessTokenAudienceProvider;
import io.meeds.oauth2.server.plugin.OAuthAccessTokenAudienceTokenRequestProvider;
import io.meeds.oauth2.server.service.OAuthAccessTokenCustomizerService;
import io.meeds.oauth2.server.service.OAuthSettingService;

/**
 * Pins the MCP access gate at the token endpoint: an excluded user is refused
 * the token, hence never owns a connector that looks broken. The contexts are
 * built with the real Spring Authorization Server builders, so that the
 * provider's reads of grant type, scopes and principal go through the same
 * accessors the authorization server's own contexts answer.
 * <p>
 * The refusal is pinned twice. On the provider alone, as a thrown
 * {@code access_denied}. And through the caller — a real
 * {@link OAuthAccessTokenCustomizerService} on which both this provider and
 * the authorization server's own
 * {@link OAuthAccessTokenAudienceTokenRequestProvider} are registered, the
 * latter wired to a settings service that allows the MCP URL as an audience,
 * exactly as this addon's {@code oauth2-configuration.xml} does. That second
 * pin is the one that matters: the customizer takes the first non-empty
 * provider answer, so a provider that merely returned null would be
 * overridden by the {@code resource} parameter every MCP client sends, and
 * only a test through the aggregate can see that.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP audience at the token endpoint")
class McpServerOAuthAccessTokenAudienceProviderTest {

  private static final String                       MCP_BASE_URL       = "https://mcp.example.com/mcp";

  private static final String                       USERNAME           = "john";

  private static final String                       EXTERNAL_CLIENT_ID = "claude-desktop-42";

  private static final String                       REDIRECT_URI       = "https://client.example.com/callback";

  private static final String                       RESOURCE_PARAM     = "resource";

  @Mock
  private McpServerToolService                      mcpServerToolService;

  @Mock
  private OAuthAccessTokenCustomizerService         customizerService;

  @Mock
  private OAuthSettingService                       oAuthSettingService;

  @InjectMocks
  private McpServerOAuthAccessTokenAudienceProvider provider;

  private OAuth2TokenClaimsSet.Builder              claims;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(provider, "mcpBaseUrl", MCP_BASE_URL);
  }

  @Test
  @DisplayName("Registers itself with the authorization server's token customizer")
  void registersItselfAsAnAudienceProvider() {
    provider.init();

    verify(customizerService).addProvider(provider);
  }

  @Test
  @DisplayName("A user grant inside the MCP audience gets the MCP server as audience")
  void userGrantInsideTheAudienceGetsTheMcpAudience() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(true);

    List<String> audiences = provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                               user(USERNAME),
                                                               TOOL_READ_SCOPE));

    assertEquals(List.of(MCP_BASE_URL), audiences);
  }

  @Test
  @DisplayName("A user grant outside the MCP audience is refused with access_denied, not merely left without an audience")
  void userGrantOutsideTheAudienceIsRefused() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(false);

    // Returning null here would only abstain, and the authorization server's
    // resource-parameter provider would then name the MCP URL in this
    // provider's place: the refusal has to be raised
    assertRefused(() -> provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                          user(USERNAME),
                                                          TOOL_READ_SCOPE,
                                                          TOOL_WRITE_SCOPE)));
  }

  @Test
  @DisplayName("A refresh is gated like the grant it renews, so a later narrowing bites at refresh")
  void refreshGrantOutsideTheAudienceIsRefused() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(false);

    assertRefused(() -> provider.provideAudiences(context(AuthorizationGrantType.REFRESH_TOKEN,
                                                          user(USERNAME),
                                                          TOOL_READ_SCOPE)));
  }

  @Test
  @DisplayName("A client-credentials grant gets the audience whatever the user audience says, without asking it")
  void clientCredentialsGrantGetsTheAudienceWhateverTheUserAudienceSays() {
    // The gate, were it asked, would refuse: this pins that it is not asked at
    // all for a token that has no end user behind it - the internal
    // client-credentials token EVA rides on must be issued whatever audience
    // an administrator configures
    lenient().when(mcpServerToolService.isMcpServerEnabledForUser(any())).thenReturn(false);

    List<String> audiences = provider.provideAudiences(context(AuthorizationGrantType.CLIENT_CREDENTIALS,
                                                               user(EXTERNAL_CLIENT_ID),
                                                               TOOL_READ_SCOPE));

    assertEquals(List.of(MCP_BASE_URL), audiences);
    verify(mcpServerToolService, never()).isMcpServerEnabledForUser(any());
  }

  @Test
  @DisplayName("A user grant whose subject is the internal client id is gated like any user")
  void userGrantNamedLikeTheInternalClientIsNotExempt() {
    // The exemption is decided on the grant type, never on the subject:
    // nothing reserves the login 'mcp-internal' for the internal client
    when(mcpServerToolService.isMcpServerEnabledForUser(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID)).thenReturn(false);

    assertRefused(() -> provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                          user(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID),
                                                          TOOL_READ_SCOPE)));
  }

  @Test
  @DisplayName("A token without an MCP scope gets no audience from this provider, and the audience is not asked")
  void nonMcpScopesGetNoAudience() {
    lenient().when(mcpServerToolService.isMcpServerEnabledForUser(any())).thenReturn(true);

    // Another provider's token: this one must abstain, not refuse, so that
    // the provider owning those scopes can answer
    assertNull(provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                 user(USERNAME),
                                                 OidcScopes.OPENID,
                                                 OidcScopes.PROFILE)));
    assertNull(provider.provideAudiences(context(AuthorizationGrantType.CLIENT_CREDENTIALS,
                                                 user(EXTERNAL_CLIENT_ID),
                                                 OidcScopes.OPENID)));

    verify(mcpServerToolService, never()).isMcpServerEnabledForUser(any());
  }

  @Test
  @DisplayName("A user grant carrying no principal fails closed, as a refusal")
  void userGrantWithoutAPrincipalIsRefused() {
    // The audience is asked about a null login, which it refuses; the
    // refusal is raised so that no other provider can answer in its place
    assertRefused(() -> provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                          null,
                                                          TOOL_READ_SCOPE)));
    verify(mcpServerToolService).isMcpServerEnabledForUser(null);
  }

  @Test
  @DisplayName("Through the customizer, an excluded user is refused even though the client sent resource=<mcp url>")
  void excludedUserIsRefusedThroughTheCustomizerEvenWhenTheClientSendsTheResourceParameter() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(false);
    OAuthAccessTokenCustomizerService customizer = customizerWith(resourceParameterProvider(), provider);

    // Both providers are registered and the authorization carries the very
    // resource the second one would answer with: the token request must still
    // fail, which it only does because this provider throws rather than
    // abstains
    assertRefused(() -> customizer.customize(claimsContext(user(USERNAME), true)));
  }

  @Test
  @DisplayName("Without this provider, the resource parameter alone would have named the MCP audience")
  void resourceParameterAloneWouldHaveNamedTheMcpAudience() {
    OAuthAccessTokenCustomizerService customizer = customizerWith(resourceParameterProvider());

    // The reason the refusal has to be raised: the authorization server's own
    // provider names the MCP URL from the request, so an abstention here
    // would have issued the token
    customizer.customize(claimsContext(user(USERNAME), true));

    assertEquals(List.of(MCP_BASE_URL), claims.build().getAudience());
    verify(mcpServerToolService, never()).isMcpServerEnabledForUser(any());
  }

  @Test
  @DisplayName("Through the customizer, an included user gets the MCP server as the token's aud claim")
  void includedUserGetsTheMcpAudienceClaimThroughTheCustomizer() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(true);
    OAuthAccessTokenCustomizerService customizer = customizerWith(resourceParameterProvider(), provider);

    customizer.customize(claimsContext(user(USERNAME), false));

    assertEquals(List.of(MCP_BASE_URL), claims.build().getAudience());
  }

  /**
   * Asserts that the call is refused the way the token endpoint reports it:
   * an {@link OAuth2AuthenticationException} carrying {@code access_denied}.
   *
   * @param call the call expected to be refused
   */
  private void assertRefused(Executable call) {
    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class, call);
    assertEquals(OAuth2ErrorCodes.ACCESS_DENIED, exception.getError().getErrorCode());
  }

  /**
   * Builds a real token customizer with the given audience providers, in the
   * order the authorization server would hold them: the customizer sorts them
   * itself on registration, so the registration order here does not decide
   * which one is consulted first — its comparator does, as in production.
   *
   * @param providers the audience providers to register
   * @return the customizer
    * <p>
   * Registers through the real {@code addProvider}, so the real comparator
   * decides the order — the resource provider is registered first on purpose.
   * If this test goes red after a change to that comparator, the MCP token
   * gate has stopped refusing and the test is right: the refusal only fires
   * while this addon's provider is consulted before the one answering from
   * {@code resource}.
   */
  private OAuthAccessTokenCustomizerService customizerWith(OAuthAccessTokenAudienceProvider... providers) {
    OAuthAccessTokenCustomizerService customizer = new OAuthAccessTokenCustomizerService();
    ReflectionTestUtils.setField(customizer, "audienceProviders", new ArrayList<>());
    ReflectionTestUtils.setField(customizer, "authorityProviders", new ArrayList<>());
    for (OAuthAccessTokenAudienceProvider audienceProvider : providers) {
      customizer.addProvider(audienceProvider);
    }
    return customizer;
  }

  /**
   * The authorization server's own provider, answering with the
   * {@code resource} parameter of the authorization request when it is an
   * allowed audience — and the MCP URL is one, as this addon registers it.
   *
   * @return the real {@link OAuthAccessTokenAudienceTokenRequestProvider}
   */
  private OAuthAccessTokenAudienceTokenRequestProvider resourceParameterProvider() {
    lenient().when(oAuthSettingService.getAllowedAudiences()).thenReturn(Set.of(MCP_BASE_URL));
    OAuthAccessTokenAudienceTokenRequestProvider resourceProvider = new OAuthAccessTokenAudienceTokenRequestProvider();
    ReflectionTestUtils.setField(resourceProvider, "oAuthSettingService", oAuthSettingService);
    return resourceProvider;
  }

  /**
   * Builds the claims context the authorization server hands its token
   * customizer for an authorization-code access token of an external MCP
   * client, whose stored authorization request may carry
   * {@code resource=<mcp url>} as RFC 8707 clients send it.
   *
   * @param principal    the resource owner
   * @param withResource whether the authorization request carried the MCP URL
   *                     as its {@code resource} parameter
   * @return the claims context; the claims it writes are readable through the
   *         {@code claims} field afterwards
   */
  private OAuth2TokenClaimsContext claimsContext(Authentication principal, boolean withResource) {
    RegisteredClient client = RegisteredClient.withId(EXTERNAL_CLIENT_ID)
                                              .clientId(EXTERNAL_CLIENT_ID)
                                              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                                              .redirectUri(REDIRECT_URI)
                                              .scope(TOOL_READ_SCOPE)
                                              .clientSettings(ClientSettings.builder().build())
                                              .build();
    OAuth2AuthorizationRequest.Builder authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                                                                                        .authorizationUri("https://platform.example.com/oauth2/authorize")
                                                                                        .clientId(EXTERNAL_CLIENT_ID)
                                                                                        .redirectUri(REDIRECT_URI)
                                                                                        .scope(TOOL_READ_SCOPE)
                                                                                        .state("state");
    if (withResource) {
      authorizationRequest.additionalParameters(Map.of(RESOURCE_PARAM, MCP_BASE_URL));
    }
    OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                                                           .principalName(principal.getName())
                                                           .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                                                           .authorizedScopes(Set.of(TOOL_READ_SCOPE))
                                                           .attribute(OAuth2AuthorizationRequest.class.getName(),
                                                                      authorizationRequest.build())
                                                           .build();
    this.claims = OAuth2TokenClaimsSet.builder();
    return OAuth2TokenClaimsContext.with(claims)
                                   .registeredClient(client)
                                   .principal(principal)
                                   .authorization(authorization)
                                   .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                                   .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                                   .authorizedScopes(Set.of(TOOL_READ_SCOPE))
                                   .build();
  }

  /**
   * Builds a token context as the authorization server does for an access
   * token of the given grant.
   *
   * @param grantType the grant the token is issued under
   * @param principal the principal the grant provider put in the context, the
   *                  resource owner for a user grant, or null to leave it out
   * @param scopes    the authorized scopes
   * @return the token context
   */
  private OAuth2TokenContext context(AuthorizationGrantType grantType, Authentication principal, String... scopes) {
    DefaultOAuth2TokenContext.Builder builder = DefaultOAuth2TokenContext.builder()
                                                                         .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                                                                         .authorizationGrantType(grantType)
                                                                         .authorizedScopes(Set.of(scopes));
    if (principal != null) {
      builder.principal(principal);
    }
    return builder.build();
  }

  /**
   * @param name the authenticated name, a platform login for a resource owner
   * @return an authentication whose {@code getName()} is that name, as the
   *         authorization server's {@code sub} claim is written from it
   */
  private Authentication user(String name) {
    return new TestingAuthenticationToken(name, "n/a");
  }

}

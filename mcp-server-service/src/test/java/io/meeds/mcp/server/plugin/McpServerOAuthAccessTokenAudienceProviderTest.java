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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.test.util.ReflectionTestUtils;

import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.oauth2.server.service.OAuthAccessTokenCustomizerService;

/**
 * Pins the MCP access gate at the token endpoint: an excluded user gets no
 * audience, hence no token, hence never a connector that looks broken. The
 * contexts are built with the real {@link DefaultOAuth2TokenContext} builder,
 * so that the provider's reads of grant type, scopes and principal go through
 * the same accessors the authorization server's own contexts answer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP audience at the token endpoint")
class McpServerOAuthAccessTokenAudienceProviderTest {

  private static final String                     MCP_BASE_URL       = "https://mcp.example.com/mcp";

  private static final String                     USERNAME           = "john";

  private static final String                     EXTERNAL_CLIENT_ID = "claude-desktop-42";

  @Mock
  private McpServerToolService                    mcpServerToolService;

  @Mock
  private OAuthAccessTokenCustomizerService       customizerService;

  @InjectMocks
  private McpServerOAuthAccessTokenAudienceProvider provider;

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
  @DisplayName("A user grant outside the MCP audience gets no audience, so the token request fails")
  void userGrantOutsideTheAudienceGetsNoAudience() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(false);

    List<String> audiences = provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                               user(USERNAME),
                                                               TOOL_READ_SCOPE,
                                                               TOOL_WRITE_SCOPE));

    // No other provider names an audience for the MCP scopes, so the
    // authorization server's computeJwtAudiences refuses the token request
    // with invalid_request: the external client never gets a token to store
    assertNull(audiences);
  }

  @Test
  @DisplayName("A refresh is gated like the grant it renews, so a later narrowing bites at refresh")
  void refreshGrantOutsideTheAudienceGetsNoAudience() {
    when(mcpServerToolService.isMcpServerEnabledForUser(USERNAME)).thenReturn(false);

    List<String> audiences = provider.provideAudiences(context(AuthorizationGrantType.REFRESH_TOKEN,
                                                               user(USERNAME),
                                                               TOOL_READ_SCOPE));

    assertNull(audiences);
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

    List<String> audiences = provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                               user(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID),
                                                               TOOL_READ_SCOPE));

    assertNull(audiences);
  }

  @Test
  @DisplayName("A token without an MCP scope gets no audience from this provider, and the audience is not asked")
  void nonMcpScopesGetNoAudience() {
    lenient().when(mcpServerToolService.isMcpServerEnabledForUser(any())).thenReturn(true);

    assertNull(provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                 user(USERNAME),
                                                 OidcScopes.OPENID,
                                                 OidcScopes.PROFILE)));
    assertNull(provider.provideAudiences(context(AuthorizationGrantType.CLIENT_CREDENTIALS,
                                                 user(EXTERNAL_CLIENT_ID),
                                                 OidcScopes.OPENID)));

    // Another provider's token: not this provider's business either way
    verify(mcpServerToolService, never()).isMcpServerEnabledForUser(any());
  }

  @Test
  @DisplayName("A user grant carrying no principal fails closed")
  void userGrantWithoutAPrincipalGetsNoAudience() {
    List<String> audiences = provider.provideAudiences(context(AuthorizationGrantType.AUTHORIZATION_CODE,
                                                               null,
                                                               TOOL_READ_SCOPE));

    // The audience is asked about a null login, which it refuses
    assertNull(audiences);
    verify(mcpServerToolService).isMcpServerEnabledForUser(null);
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

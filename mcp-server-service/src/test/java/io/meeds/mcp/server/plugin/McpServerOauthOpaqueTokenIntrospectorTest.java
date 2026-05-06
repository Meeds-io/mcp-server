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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;
import org.springframework.test.util.ReflectionTestUtils;

import io.meeds.oauth2.server.service.OAuthClientService;

@ExtendWith(MockitoExtension.class)
class McpServerOauthOpaqueTokenIntrospectorTest {

  private static final String                   TOKEN_SCOPES_NOT_SUPPORTED_ERROR =
                                                                                 "Token scopes aren't authorized in OAuth Client";

  private static final String                   INVALID_TOKEN_ERROR              = "invalid_token";

  private static final String                   SCOPE_AUTHORITY_PREFIX           = "SCOPE_%s";

  private static final String                   ADMIN_SCOPE                      = "admin";

  private static final String                   WRITE_SCOPE                      = "write";

  private static final String                   READ_SCOPE                       = "read";

  private static final String                   SCOPE_PARAM                      = "scope";

  private static final String                   TOKEN                            = "opaque-token";

  private static final String                   ISSUER_URI                       = "https://auth.example.com";

  private static final String                   SERVER_AUDIENCE                  = "https://mcp.example.com";

  private static final String                   CLIENT_ID                        = "mcp-client";

  @Mock
  private SpringOpaqueTokenIntrospector         delegate;

  @Mock
  private OAuthClientService                    oAuthClientService;

  private McpServerOauthOpaqueTokenIntrospector introspector;

  @BeforeEach
  void setUp() {
    introspector = new McpServerOauthOpaqueTokenIntrospector();

    ReflectionTestUtils.setField(introspector, "delegate", delegate);
    ReflectionTestUtils.setField(introspector, "oAuthClientService", oAuthClientService);
    ReflectionTestUtils.setField(introspector, "issuerUri", ISSUER_URI);
    ReflectionTestUtils.setField(introspector, "serverAudience", SERVER_AUDIENCE);
  }

  @Test
  void introspect_ShouldReturnPrincipalWithScopeAuthorities_WhenTokenIsValidAndScopeIsString() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              "%s %s".formatted(READ_SCOPE, WRITE_SCOPE)));

    RegisteredClient client = registeredClient(READ_SCOPE, WRITE_SCOPE, ADMIN_SCOPE);

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(client);

    OAuth2AuthenticatedPrincipal result = introspector.introspect(TOKEN);

    assertEquals(principal.getName(), result.getName());
    assertEquals(principal.getAttributes(), result.getAttributes());

    Collection<String> authorities = result.getAuthorities()
                                           .stream()
                                           .map(GrantedAuthority::getAuthority)
                                           .toList();

    assertEquals(2, authorities.size());
    assertTrue(authorities.contains(SCOPE_AUTHORITY_PREFIX.formatted(READ_SCOPE)));
    assertTrue(authorities.contains(SCOPE_AUTHORITY_PREFIX.formatted(WRITE_SCOPE)));
  }

  @Test
  void introspect_ShouldReturnPrincipalWithScopeAuthorities_WhenAudienceAndScopeAreCollections() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              List.of("other-audience", SERVER_AUDIENCE),
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              List.of(READ_SCOPE, WRITE_SCOPE)));

    RegisteredClient client = registeredClient(READ_SCOPE, WRITE_SCOPE);

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(client);

    OAuth2AuthenticatedPrincipal result = introspector.introspect(TOKEN);

    Collection<String> authorities = result.getAuthorities()
                                           .stream()
                                           .map(GrantedAuthority::getAuthority)
                                           .toList();

    assertEquals(2, authorities.size());
    assertTrue(authorities.contains("SCOPE_read"));
    assertTrue(authorities.contains("SCOPE_write"));
  }

  @Test
  void introspect_ShouldIgnoreBlankScopeValues_WhenScopeIsString() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              "read   write"));

    RegisteredClient client = registeredClient(READ_SCOPE, WRITE_SCOPE);

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(client);

    OAuth2AuthenticatedPrincipal result = introspector.introspect(TOKEN);

    Collection<String> authorities = result.getAuthorities()
                                           .stream()
                                           .map(GrantedAuthority::getAuthority)
                                           .toList();

    assertEquals(List.of("SCOPE_read", "SCOPE_write"), authorities);
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenAudienceIsInvalid() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              "invalid-audience",
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              READ_SCOPE));

    when(delegate.introspect(TOKEN)).thenReturn(principal);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals("Token audience is not valid", exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenAudienceIsMissing() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              READ_SCOPE));

    when(delegate.introspect(TOKEN)).thenReturn(principal);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals("Token audience is not valid", exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenIssuerIsInvalid() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              "https://invalid-issuer.example.com",
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              READ_SCOPE));

    when(delegate.introspect(TOKEN)).thenReturn(principal);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals("Token issuer is not valid", exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenIssuerIsMissing() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              READ_SCOPE));

    when(delegate.introspect(TOKEN)).thenReturn(principal);

    OAuth2AuthenticationException exception = assertThrows(
                                                           OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals("Token issuer is not valid", exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenAuthorizedPartyIsBlank() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              "",
                                                              SCOPE_PARAM,
                                                              READ_SCOPE));

    when(delegate.introspect(TOKEN)).thenReturn(principal);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals("Token authorized party (azp) is not valid", exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenClientIsNotFound() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              READ_SCOPE));

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(null);

    OAuth2AuthenticationException exception = assertThrows(
                                                           OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals("Token authorized party (azp) is not valid", exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenScopeIsMissing() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID));

    RegisteredClient client = registeredClient(READ_SCOPE);

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(client);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals(TOKEN_SCOPES_NOT_SUPPORTED_ERROR, exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenScopeIsNotAuthorizedForClient() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              "read admin"));

    RegisteredClient client = registeredClient(READ_SCOPE, WRITE_SCOPE);

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(client);

    OAuth2AuthenticationException exception = assertThrows(OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals(TOKEN_SCOPES_NOT_SUPPORTED_ERROR, exception.getError().getDescription());
  }

  @Test
  void introspect_ShouldThrowInvalidToken_WhenScopeIsBlank() {// NOSONAR
    OAuth2AuthenticatedPrincipal principal = principal(Map.of("aud",
                                                              SERVER_AUDIENCE,
                                                              "iss",
                                                              ISSUER_URI,
                                                              "azp",
                                                              CLIENT_ID,
                                                              SCOPE_PARAM,
                                                              "   "));

    RegisteredClient client = registeredClient(READ_SCOPE);

    when(delegate.introspect(TOKEN)).thenReturn(principal);
    when(oAuthClientService.getClient(CLIENT_ID)).thenReturn(client);

    OAuth2AuthenticationException exception = assertThrows(
                                                           OAuth2AuthenticationException.class,
                                                           () -> introspector.introspect(TOKEN));

    assertEquals(INVALID_TOKEN_ERROR, exception.getError().getErrorCode());
    assertEquals(TOKEN_SCOPES_NOT_SUPPORTED_ERROR, exception.getError().getDescription());
  }

  private OAuth2AuthenticatedPrincipal principal(Map<String, Object> attributes) {
    return new DefaultOAuth2AuthenticatedPrincipal("test-user",
                                                   attributes,
                                                   List.of());
  }

  private RegisteredClient registeredClient(String... scopes) {
    return RegisteredClient.withId("registered-client-id")
                           .clientId(CLIENT_ID)
                           .clientSecret("secret")
                           .authorizationGrantType(
                                                   org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
                           .scopes(scope -> scope.addAll(Set.of(scopes)))
                           .build();
  }

}

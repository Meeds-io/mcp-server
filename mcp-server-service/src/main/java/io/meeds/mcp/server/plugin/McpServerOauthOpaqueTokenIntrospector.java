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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

import io.meeds.mcp.server.model.McpServerOAuthClientProperties;
import io.meeds.mcp.server.service.McpInternalOAuthClientService;
import io.meeds.oauth2.server.service.OAuthClientService;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class McpServerOauthOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

  @Autowired
  private McpInternalOAuthClientService  aiOAuthService;

  @Autowired
  private McpServerOAuthClientProperties oAuthClientProperties;

  @Autowired
  private OAuthClientService             oAuthClientService;

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
  private String                         issuerUri;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                         serverAudience;

  private SpringOpaqueTokenIntrospector  delegate;

  @Override
  public OAuth2AuthenticatedPrincipal introspect(String token) {
    OAuth2AuthenticatedPrincipal principal = getDelegate().introspect(token);

    validateAudience(principal);
    validateIssuer(principal);
    RegisteredClient client = validateAuthorizedParty(principal);

    Collection<GrantedAuthority> authorities = extractScopeAuthorities(principal, client);

    return new DefaultOAuth2AuthenticatedPrincipal(principal.getName(),
                                                   principal.getAttributes(),
                                                   authorities);
  }

  public SpringOpaqueTokenIntrospector getDelegate() {
    if (delegate == null) {
      delegate = SpringOpaqueTokenIntrospector.withIntrospectionUri(oAuthClientProperties.getOpaquetoken()
                                                                                         .getIntrospectionUri())
                                              .clientId(oAuthClientProperties.getIntrospectionClient()
                                                                             .getRegistration()
                                                                             .getClientId())
                                              .clientSecret(aiOAuthService.getClientSecret())
                                              .build();
    }
    return delegate;
  }

  private Collection<GrantedAuthority> extractScopeAuthorities(OAuth2AuthenticatedPrincipal principal, RegisteredClient client) {
    List<String> scopes = extractScopes(principal);
    validateScopes(client, scopes);
    return scopes.stream()
                 .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
                 .toList();
  }

  private List<String> extractScopes(OAuth2AuthenticatedPrincipal principal) {
    Object scope = principal.getAttribute("scope");
    if (scope instanceof String scopeValue) {
      if (StringUtils.isBlank(scopeValue)) {
        return List.of();
      }
      return Arrays.stream(scopeValue.split(" "))
                   .filter(StringUtils::isNotBlank)
                   .toList();
    }
    if (scope instanceof Collection<?> scopes) {
      return scopes.stream()
                   .map(String::valueOf)
                   .filter(StringUtils::isNotBlank)
                   .toList();
    }
    return List.of();
  }

  private void validateAudience(OAuth2AuthenticatedPrincipal principal) {
    Object audience = principal.getAttribute("aud");
    boolean valid = audience != null && switch (audience) {
    case String aud -> Strings.CS.equals(aud, serverAudience);
    case Collection<?> audiences -> audiences.stream()
                                             .map(String::valueOf)
                                             .anyMatch(aud -> Strings.CS.equals(aud, serverAudience));
    default -> false;
    };
    if (!valid) {
      log.warn("Token audience '{}' is not valid. Expected : '{}'", audience, serverAudience);
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                                                              "Token audience is not valid",
                                                              null));
    }
  }

  private void validateIssuer(OAuth2AuthenticatedPrincipal principal) {
    String issuer = principal.getAttribute("iss");
    if (StringUtils.isBlank(issuer) || !Strings.CS.equals(issuer, issuerUri)) {
      log.warn("Token issuer '{}' is not valid. Expected : '{}'", issuer, issuerUri);
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                                                              "Token issuer is not valid",
                                                              null));
    }
  }

  private RegisteredClient validateAuthorizedParty(OAuth2AuthenticatedPrincipal principal) {
    String azp = principal.getAttribute("azp");
    RegisteredClient client = StringUtils.isBlank(azp) ? null : oAuthClientService.getClient(azp);
    if (client == null) {
      log.warn("Token authorized party (azp) '{}' is not valid and associated client wasn't found or isn't active", azp);
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                                                              "Token authorized party (azp) is not valid",
                                                              null));
    }
    return client;
  }

  private void validateScopes(RegisteredClient client, List<String> scopes) {
    if (CollectionUtils.isEmpty(scopes) || !client.getScopes().containsAll(scopes)) {
      log.warn("Token scopes '{}' is not valid. Expected Client scopes to allow them all, current client scopes: {}",
               scopes,
               client.getScopes());
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                                                              "Token scopes aren't authorized in OAuth Client",
                                                              null));
    }
  }

}

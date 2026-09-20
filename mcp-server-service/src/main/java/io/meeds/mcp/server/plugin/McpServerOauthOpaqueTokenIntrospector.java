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
import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.mcp.server.util.McpToolUtils;
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

  @Autowired
  private McpServerToolService           mcpServerToolService;

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
  private String                         issuerUri;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                         serverAudience;

  private SpringOpaqueTokenIntrospector  delegate;

  /**
   * Validates an opaque access token before anything under {@code /mcp} runs.
   * <p>
   * {@link #validateMcpAudience(OAuth2AuthenticatedPrincipal)} is the
   * enforcement point of the MCP access gate on the request path, and covers
   * the whole surface — {@code initialize}, {@code tools/list},
   * {@code tools/call} and the SSE stream alike. Being here also means it is
   * re-evaluated on
   * every <em>request</em>, so narrowing the audience takes a user's access
   * away at once instead of at the expiry of a token already issued to them.
   * One residual: an SSE stream <em>already open</em> when the audience
   * narrows is not re-introspected, so server-to-client notifications keep
   * flowing on it until it drops. Every new request, on that session or any
   * other, is refused.
   *
   * @param token the opaque bearer token presented by the caller
   * @return the authenticated principal, carrying its scope authorities
   */
  @Override
  public OAuth2AuthenticatedPrincipal introspect(String token) {
    OAuth2AuthenticatedPrincipal principal = getDelegate().introspect(token);

    validateAudience(principal);
    validateIssuer(principal);
    RegisteredClient client = validateAuthorizedParty(principal);
    validateMcpAudience(principal);

    Collection<GrantedAuthority> authorities = extractScopeAuthorities(principal, client);

    return new DefaultOAuth2AuthenticatedPrincipal(principal.getName(),
                                                   principal.getAttributes(),
                                                   authorities);
  }

  /**
   * @return the Spring introspector doing the actual call to the authorization
   *         server's introspection endpoint, built lazily because the internal
   *         client secret it authenticates with is generated on first use
   */
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

  /**
   * Maps the token's scopes to Spring Security authorities, once they have
   * been checked against the client's registered scopes.
   *
   * @param principal the introspected token principal
   * @param client    the client the token was issued to
   * @return one {@code SCOPE_*} authority per scope carried by the token
   */
  private Collection<GrantedAuthority> extractScopeAuthorities(OAuth2AuthenticatedPrincipal principal, RegisteredClient client) {
    List<String> scopes = extractScopes(principal);
    validateScopes(client, scopes);
    return scopes.stream()
                 .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
                 .toList();
  }

  /**
   * Reads the {@code scope} claim, which the authorization server may encode
   * either as a space-separated string or as a collection.
   *
   * @param principal the introspected token principal
   * @return the scopes carried by the token, never null
   */
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

  /**
   * Rejects a token that was not issued for this MCP server, i.e. whose
   * {@code aud} claim does not name it. Not to be confused with the MCP
   * <em>user</em> audience of
   * {@link #validateMcpAudience(OAuth2AuthenticatedPrincipal)}: this one is
   * about which server the token is for, that one about who may use it.
   *
   * @param principal the introspected token principal
   */
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

  /**
   * Rejects a token that was not issued by the configured authorization
   * server.
   *
   * @param principal the introspected token principal
   */
  private void validateIssuer(OAuth2AuthenticatedPrincipal principal) {
    String issuer = principal.getAttribute("iss");
    if (StringUtils.isBlank(issuer) || !Strings.CS.equals(issuer, issuerUri)) {
      log.warn("Token issuer '{}' is not valid. Expected : '{}'", issuer, issuerUri);
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                                                              "Token issuer is not valid",
                                                              null));
    }
  }

  /**
   * Rejects a token whose authorized party ({@code azp}) is not a known,
   * active OAuth client.
   *
   * @param principal the introspected token principal
   * @return the registered client the token belongs to, never null
   */
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

  /**
   * Rejects a token whose end user may not use the MCP server.
   * <p>
   * Asks {@link McpServerToolService#isMcpServerEnabledForUser(String)}, the
   * one question the token endpoint asks too, rather than the audience alone:
   * it checks the global {@code mcp.server} flag and then the audience, so the
   * door refuses on either half and the two places cannot drift apart. Asking
   * {@code McpServerAudienceService} directly here would have let a token
   * holder open a session and reach {@code initialize} on an instance where
   * MCP is globally off. This is the only audience check on the request path:
   * the tool level ({@code McpServerToolService.isAllowedTool}) checks the
   * global flag and the scopes, not the audience, since every request under
   * {@code /mcp} crosses this introspector first.
   * <p>
   * The user is the token subject, which the authorization server sets to the
   * platform login on a user grant. A blank subject therefore resolves to no
   * user and is refused, as is a subject no platform identity answers to.
   * <p>
   * The internal client is exempt and must stay so: EVA reaches its tools
   * through the internal client-credentials grant, whose subject is the client
   * id rather than a human login, so asking the audience about it would refuse
   * every EVA tool call the moment an audience was configured. The exemption
   * is decided on the {@code client_id} claim — which the authorization server
   * writes from the client the token was actually issued to — and never on the
   * subject, so a user whose login equals the internal client id is gated like
   * any other user.
   *
   * @param principal the introspected token principal
   */
  private void validateMcpAudience(OAuth2AuthenticatedPrincipal principal) {
    if (McpToolUtils.isInternalClientPrincipal(principal.getAttributes())) {
      return;
    }
    String username = principal.getName();
    if (!mcpServerToolService.isMcpServerEnabledForUser(username)) {
      // debug, not warn: an administrator excluding a user is normal flow, and
      // this runs on every request, so one excluded client would flood the log
      log.debug("User '{}' is not allowed to use the MCP Server", username);
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                                                              "User is not allowed to use the MCP Server",
                                                              null));
    }
  }

  /**
   * Checks that every scope the token carries is one its OAuth client is
   * registered for.
   *
   * @param client the client the token was issued to
   * @param scopes the scopes carried by the token
   */
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

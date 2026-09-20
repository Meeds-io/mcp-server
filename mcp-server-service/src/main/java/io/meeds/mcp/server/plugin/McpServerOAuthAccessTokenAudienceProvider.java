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

import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_APPROVE_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.stereotype.Component;

import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.oauth2.server.configuration.plugin.OAuthAccessTokenAudienceProvider;
import io.meeds.oauth2.server.service.OAuthAccessTokenCustomizerService;

import jakarta.annotation.PostConstruct;

/**
 * Names this MCP server as the audience of the access tokens issued for its
 * tool scopes — and refuses the token outright for a user the MCP audience
 * excludes.
 * <p>
 * That refusal is what keeps an excluded user from <em>adding</em> the server
 * to an external MCP client at all. Without it they complete the whole OAuth
 * flow — discovery, dynamic client registration, login, consent — obtain a
 * token, and only then meet the door's 401: they end up owning a connector
 * that looks broken. With it the token request fails with
 * {@code access_denied}, and nothing is ever recorded as connected. A refresh
 * goes through the same customizer, so an audience narrowed later bites at
 * the next refresh as well as, through the door, at the very next request.
 * <p>
 * The refusal has to be <em>raised</em>, not expressed by returning null.
 * {@code OAuthAccessTokenCustomizerService.computeJwtAudiences} takes the
 * first non-empty answer among the registered providers and only fails the
 * request when every one of them abstains — and the authorization server
 * ships a second provider, {@code OAuthAccessTokenAudienceTokenRequestProvider},
 * which answers with the {@code resource} parameter of the authorization
 * request (RFC 8707) whenever that value is one of
 * {@code OAuthSettingService.getAllowedAudiences()}. This addon registers its
 * own URL as exactly such an allowed audience, and MCP clients do send
 * {@code resource}. So a null here would merely abstain, that provider would
 * name the MCP URL in its place, and the excluded user would get a token
 * anyway. Throwing {@link OAuth2AuthenticationException} instead ends the
 * stream before any other provider is consulted, and the customizer
 * propagates it as the token endpoint's error response.
 */
@Component
public class McpServerOAuthAccessTokenAudienceProvider implements OAuthAccessTokenAudienceProvider {

  private static final String[]             ALLOWED_SCOPES = {
    TOOL_READ_SCOPE,
    TOOL_WRITE_SCOPE,
    TOOL_WRITE_APPROVE_SCOPE
  };

  @Autowired
  private OAuthAccessTokenCustomizerService oAuthAccessTokenCustomizerService;

  @Autowired
  private McpServerToolService              mcpServerToolService;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                            mcpBaseUrl;

  /**
   * Registers this provider with the authorization server's access token
   * customizer, which consults it on every access token it issues.
   */
  @PostConstruct
  public void init() {
    oAuthAccessTokenCustomizerService.addProvider(this);
  }

  /**
   * @param context the access token being issued
   * @return this MCP server's URL as the token's audience when the token
   *         carries an MCP tool scope and either has no end user (a
   *         client-credentials grant) or has one the MCP server is enabled
   *         for; null for a token without an MCP scope, which lets another
   *         provider answer
   * @throws OAuth2AuthenticationException with {@code access_denied} for an
   *                                       MCP token whose end user is outside
   *                                       the MCP audience, or whose context
   *                                       carries no principal at all — a
   *                                       grant this code does not know fails
   *                                       closed, as a refusal rather than an
   *                                       abstention another provider could
   *                                       override
   */
  @Override
  public List<String> provideAudiences(OAuth2TokenContext context) {
    if (!CollectionUtils.containsAny(context.getAuthorizedScopes(), ALLOWED_SCOPES)) {
      return null; // NOSONAR
    } else if (isClientCredentialsGrant(context)
               || mcpServerToolService.isMcpServerEnabledForUser(getEndUserName(context))) {
      return List.of(mcpBaseUrl);
    } else {
      throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED,
                                                              "User is not allowed to use the MCP Server",
                                                              null));
    }
  }

  /**
   * Tells whether the token is issued to a client for itself, with no end user
   * behind it. Such a token is not asked the user audience — there is no user
   * to ask about — which is what keeps the internal client-credentials token
   * EVA's tool calling rides on untouched by whatever audience an
   * administrator configures, and by the global flag.
   * <p>
   * Decided on the grant type and never on the subject: the subject of a
   * client-credentials token is the client id, and a user whose login equals
   * the internal client id must not be exempt, here as everywhere else in the
   * gate.
   *
   * @param context the access token being issued
   * @return true for a client-credentials grant
   */
  private boolean isClientCredentialsGrant(OAuth2TokenContext context) {
    return AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType());
  }

  /**
   * Resolves the end user of a user grant. Spring Authorization Server puts
   * the resource owner's {@link Authentication} in the token context for the
   * authorization-code and refresh-token grants — the {@code Principal}
   * attribute of the {@code OAuth2Authorization}, asserted non-null there —
   * and its {@code getName()} is the very string
   * {@code OAuthAccessTokenCustomizerService} writes as the token's {@code sub}
   * claim, which the door then checks. So the audience is asked about one
   * identity, here and at the door.
   *
   * @param context the access token being issued
   * @return the end user's login, or null when the context carries no
   *         principal — which the audience refuses, so that
   *         {@link #provideAudiences} throws and a grant this code does not
   *         know fails closed
   */
  private String getEndUserName(OAuth2TokenContext context) {
    Authentication principal = context.getPrincipal();
    return principal == null ? null : principal.getName();
  }

}

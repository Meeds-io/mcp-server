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
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.stereotype.Component;

import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.oauth2.server.configuration.plugin.OAuthAccessTokenAudienceProvider;
import io.meeds.oauth2.server.service.OAuthAccessTokenCustomizerService;

import jakarta.annotation.PostConstruct;

/**
 * Names this MCP server as the audience of the access tokens issued for its
 * tool scopes — and refuses to, for a user the MCP audience excludes.
 * <p>
 * That refusal is what keeps an excluded user from <em>adding</em> the server
 * to an external MCP client at all. Without it they complete the whole OAuth
 * flow — discovery, dynamic client registration, login, consent — obtain a
 * token, and only then meet the door's 401: they end up owning a connector
 * that looks broken. With it, no provider names an audience for their token,
 * {@code OAuthAccessTokenCustomizerService.computeJwtAudiences} fails the
 * token request with {@code invalid_request} ("No valid audience provided"),
 * and nothing is ever recorded as connected. A refresh goes through the same
 * customizer, so an audience narrowed later bites at the next refresh as well
 * as, through the door, at the very next request.
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
   *         for; null otherwise. For a token without an MCP scope the null
   *         lets another provider answer; for an MCP token whose end user is
   *         excluded it makes the authorization server refuse the token
   *         request, as no other provider names an audience for those scopes
   */
  @Override
  public List<String> provideAudiences(OAuth2TokenContext context) {
    if (!CollectionUtils.containsAny(context.getAuthorizedScopes(), ALLOWED_SCOPES)) {
      return null; // NOSONAR
    } else if (isClientCredentialsGrant(context)
               || mcpServerToolService.isMcpServerEnabledForUser(getEndUserName(context))) {
      return List.of(mcpBaseUrl);
    } else {
      return null; // NOSONAR
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
   *         principal — which the audience refuses, so a grant this code does
   *         not know fails closed
   */
  private String getEndUserName(OAuth2TokenContext context) {
    Authentication principal = context.getPrincipal();
    return principal == null ? null : principal.getName();
  }

}

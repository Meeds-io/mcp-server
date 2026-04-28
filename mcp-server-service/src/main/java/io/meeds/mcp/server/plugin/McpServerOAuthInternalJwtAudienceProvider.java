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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.stereotype.Component;

import io.meeds.mcp.server.service.McpInternalOAuthClientService;
import io.meeds.oauth2.server.configuration.plugin.OAuthJwtAudienceProvider;
import io.meeds.oauth2.server.service.OAuthJwtCustomizerService;

import jakarta.annotation.PostConstruct;

@Component
public class McpServerOAuthInternalJwtAudienceProvider implements OAuthJwtAudienceProvider {

  @Autowired
  private OAuthJwtCustomizerService     oAuthJwtCustomizerService;

  @Autowired
  private McpInternalOAuthClientService aiOAuthService;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                        mcpBaseUrl;

  @PostConstruct
  public void init() {
    oAuthJwtCustomizerService.addProvider(this);
  }

  public List<String> provideAudiences(OAuth2TokenContext context) {
    Authentication principal = context.getPrincipal();
    if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(context.getAuthorizationGrantType().getValue())
        && StringUtils.equals(principal.getName(), aiOAuthService.getClientRegistrationId())) {
      return List.of(mcpBaseUrl);
    }
    return null; // NOSONAR
  }

}

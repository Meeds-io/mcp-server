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
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.stereotype.Component;

import io.meeds.oauth2.server.configuration.plugin.OAuthAccessTokenAudienceProvider;
import io.meeds.oauth2.server.service.OAuthAccessTokenCustomizerService;

import jakarta.annotation.PostConstruct;

@Component
public class McpServerOAuthAccessTokenAudienceProvider implements OAuthAccessTokenAudienceProvider {

  private static final String[]             ALLOWED_SCOPES = {
    TOOL_READ_SCOPE,
    TOOL_WRITE_SCOPE,
    TOOL_WRITE_APPROVE_SCOPE
  };

  @Autowired
  private OAuthAccessTokenCustomizerService oAuthAccessTokenCustomizerService;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                            mcpBaseUrl;

  @PostConstruct
  public void init() {
    oAuthAccessTokenCustomizerService.addProvider(this);
  }

  public List<String> provideAudiences(OAuth2TokenContext context) {
    if (CollectionUtils.containsAny(context.getAuthorizedScopes(), ALLOWED_SCOPES)) {
      return List.of(mcpBaseUrl);
    } else {
      return null; // NOSONAR
    }
  }

}

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
package io.meeds.mcp.server.rest;

import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.meeds.mcp.server.service.McpServerToolService;
import io.meeds.oauth2.server.util.Utils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class OAuthProtectedResourceMetadataRest {

  private static final List<String> SCOPES = List.of(OidcScopes.OPENID,
                                                     Utils.OFFLINE_ACCESS_SCOPE,
                                                     TOOL_READ_SCOPE,
                                                     TOOL_WRITE_SCOPE);

  @Value("${meeds.oauth.server-base-url}")
  private String                    oauthIssuerUrl;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                    mcpUrl;

  @Autowired
  private McpServerToolService      mcpServerToolService;

  @Operation(method = "GET", summary = "Retrieve the OAuth Protected Resource Metadata Document")
  @ApiResponses(value = {
    @ApiResponse(responseCode = "200", description = "Request fulfilled"),
  })
  @GetMapping({ "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**" })
  public Map<String, Object> metadata(HttpServletRequest request) {
    if (mcpServerToolService.isMcpServerEnabled()) {
      return Map.of("resource",
                    mcpUrl,
                    "authorization_servers",
                    List.of(oauthIssuerUrl),
                    "scopes_supported",
                    SCOPES);
    } else {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP Server is disabled");
    }
  }

}

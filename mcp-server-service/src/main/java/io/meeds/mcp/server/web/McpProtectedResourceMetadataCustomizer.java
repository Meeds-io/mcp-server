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
package io.meeds.mcp.server.web;

import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.stereotype.Component;

import io.meeds.oauth2.server.util.Utils;

/**
 * The claims of the OAuth 2.0 Protected Resource Metadata document (RFC 9728)
 * of the MCP endpoint. Spring Security's resource server serves that document
 * itself, at {@code /.well-known/oauth-protected-resource/**}, ahead of any
 * controller, so the document is customized here and passed to the resource
 * server configuration of each security filter chain.
 */
@Component
public class McpProtectedResourceMetadataCustomizer implements Consumer<OAuth2ProtectedResourceMetadata.Builder> {

  private static final List<String> SCOPES = List.of(OidcScopes.OPENID,
                                                     Utils.OFFLINE_ACCESS_SCOPE,
                                                     TOOL_READ_SCOPE,
                                                     TOOL_WRITE_SCOPE);

  @Value("${meeds.oauth.server-base-url}")
  private String                    oauthIssuerUrl;

  @Value("${meeds.oauth.mcp-server-url}")
  private String                    mcpUrl;

  @Override
  public void accept(OAuth2ProtectedResourceMetadata.Builder builder) {
    // The resource is the MCP endpoint, which is also the audience the token
    // introspection requires. Access tokens are not bound to a client
    // certificate (RFC 8705), contrary to Spring Security's default claim
    builder.resource(mcpUrl)
           .authorizationServer(oauthIssuerUrl)
           .scopes(scopes -> scopes.addAll(SCOPES))
           .tlsClientCertificateBoundAccessTokens(false);
  }

}

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

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import io.meeds.mcp.server.service.McpServerToolService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class McpBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

  @Autowired
  private McpServerToolService mcpServerToolService;

  @Value("${meeds.oauth.mcp-server-protected-resource}")
  private String               mcpProtectedResourceUrl;

  @Override
  public void commence(HttpServletRequest request,
                       HttpServletResponse response,
                       AuthenticationException authException) throws IOException {
    if (mcpServerToolService.isMcpServerEnabled()) {
      if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
        log.trace("Anonymous MCP request without '{}' Header, Set '{}' Header in Response",
                  HttpHeaders.AUTHORIZATION,
                  HttpHeaders.WWW_AUTHENTICATE);
      } else if (log.isDebugEnabled()) {
        log.debug("Error while authenticating MCP request", authException);
      } else {
        log.warn("Error while authenticating MCP request: {}", authException.getMessage());
      }
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                         "Bearer resource_metadata=\"%s\"".formatted(mcpProtectedResourceUrl));
    } else {
      log.trace("MCP Server disabled, thus no 'WWW-Authenticate' Header will be provided");
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
  }

}

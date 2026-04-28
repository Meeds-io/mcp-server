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
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import io.meeds.web.security.plugin.RootWebappFilterPlugin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class McpOAuthResourceRedirectWebFilter implements RootWebappFilterPlugin {

  private static final Map<String, String> REDIRECT_URIS = Map.of("/.well-known/oauth-protected-resource/mcp",
                                                                  "/mcp-server/.well-known/oauth-protected-resource",
                                                                  "/mcp",
                                                                  "/mcp-server/mcp");

  @Override
  public boolean matches(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
    return REDIRECT_URIS.containsKey(httpRequest.getRequestURI());
  }

  @Override
  public void doFilter(HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse,
                       FilterChain chain) throws IOException, ServletException {
    httpResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    httpResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
    httpResponse.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
    httpResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "*");
    if (httpRequest.getMethod().equalsIgnoreCase("GET")) {
      httpResponse.sendRedirect(REDIRECT_URIS.get(httpRequest.getRequestURI()));
    }
  }

}

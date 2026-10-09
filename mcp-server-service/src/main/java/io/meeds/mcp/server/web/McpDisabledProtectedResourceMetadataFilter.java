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

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import io.meeds.mcp.server.service.McpServerToolService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers 404 to the OAuth 2.0 Protected Resource Metadata requests while the
 * MCP server is disabled, as {@link McpBearerAuthenticationEntryPoint} then
 * withholds the metadata URL. Spring Security's resource server serves the
 * document itself, so this filter is placed ahead of it in the security filter
 * chain. Not a bean on purpose: a Filter bean would also be registered on
 * every request of the servlet context.
 */
public class McpDisabledProtectedResourceMetadataFilter extends OncePerRequestFilter {

  private static final RequestMatcher METADATA_REQUEST_MATCHER =
                                                              PathPatternRequestMatcher.withDefaults()
                                                                                       .matcher(HttpMethod.GET,
                                                                                                "/.well-known/oauth-protected-resource/**");

  private final McpServerToolService  mcpServerToolService;

  public McpDisabledProtectedResourceMetadataFilter(McpServerToolService mcpServerToolService) {
    this.mcpServerToolService = mcpServerToolService;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
    if (METADATA_REQUEST_MATCHER.matches(request) && !mcpServerToolService.isMcpServerEnabled()) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND, "MCP Server is disabled");
    } else {
      filterChain.doFilter(request, response);
    }
  }

}

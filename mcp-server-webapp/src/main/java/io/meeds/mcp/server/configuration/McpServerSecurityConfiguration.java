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
package io.meeds.mcp.server.configuration;

import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_APPROVE_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.meeds.mcp.server.plugin.McpServerOauthOpaqueTokenIntrospector;
import io.meeds.mcp.server.web.McpBearerAuthenticationEntryPoint;
import io.meeds.oauth2.server.web.OAuthCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class McpServerSecurityConfiguration {

  private static final String   SCOPE_FORMAT    = "SCOPE_%s";

  private static final String[] PATH_PATTERNS   = {
    "/mcp",
    "/mcp/**"
  };

  private static final String[] ALLOWED_METHODS = {
    "GET",
    "POST",
    "DELETE",
    "OPTIONS"
  };

  private static final String[] ALLOWED_HEADERS = {
    io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION,
    io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID,
    io.modelcontextprotocol.spec.HttpHeaders.LAST_EVENT_ID
  };

  private static final String[] ALLOWED_SCOPES  = {
    SCOPE_FORMAT.formatted(TOOL_READ_SCOPE),
    SCOPE_FORMAT.formatted(TOOL_WRITE_SCOPE),
    SCOPE_FORMAT.formatted(TOOL_WRITE_APPROVE_SCOPE)
  };

  private static final String[] EXPOSED_HEADERS = {
    io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION,
    io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID,
    io.modelcontextprotocol.spec.HttpHeaders.LAST_EVENT_ID,
    org.springframework.http.HttpHeaders.WWW_AUTHENTICATE
  };

  @Bean
  @Order(1)
  SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                             McpServerOauthOpaqueTokenIntrospector opaqueTokenIntrospector,
                                             McpBearerAuthenticationEntryPoint authenticationEntryPoint) {
    return http.securityMatcher("/**")
               .csrf(csrf -> csrf.disable())
               .cors(Customizer.withDefaults())
               .authorizeHttpRequests(authorize -> authorize.requestMatchers("/.well-known/**")
                                                            .permitAll()
                                                            .requestMatchers("/mcp/**")
                                                            .hasAnyAuthority(ALLOWED_SCOPES)
                                                            .requestMatchers("/mcp")
                                                            .hasAnyAuthority(ALLOWED_SCOPES)
                                                            .anyRequest()
                                                            .denyAll())
               .oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(c -> c.introspector(opaqueTokenIntrospector))
                                                     .authenticationEntryPoint(authenticationEntryPoint))
               .build();
  }

  /**
   * Named Bean here instead of Component on purpose to make CORS Enabled in
   * some Web contexts only
   * 
   * @param corsConfigurationSource {@link OAuthCorsConfigurationSource}
   * @return {@link UrlBasedCorsConfigurationSource} service
   */
  @Bean("corsConfigurationSource")
  UrlBasedCorsConfigurationSource corsConfigurationSource(OAuthCorsConfigurationSource corsConfigurationSource) {
    corsConfigurationSource.addPaths(PATH_PATTERNS);
    Arrays.stream(PATH_PATTERNS).forEach(path -> {
      corsConfigurationSource.addAllowedHeaders(path, ALLOWED_HEADERS);
      corsConfigurationSource.addAllowedMethods(path, ALLOWED_METHODS);
      corsConfigurationSource.addExposedHeaders(path, EXPOSED_HEADERS);
    });
    return corsConfigurationSource;
  }

}

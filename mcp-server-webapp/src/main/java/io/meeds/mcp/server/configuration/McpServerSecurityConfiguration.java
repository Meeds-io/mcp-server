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

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.meeds.mcp.server.configuration.model.McpServerOAuthClientProperties;
import io.meeds.mcp.server.service.McpInternalOAuthClientService;
import io.meeds.oauth2.server.web.OAuthCorsConfigurationSource;

import io.modelcontextprotocol.spec.HttpHeaders;

@Configuration
@EnableMethodSecurity
public class McpServerSecurityConfiguration {

  private static final String[] PATH_PATTERNS   = {
    "/mcp",
    "/mcp/**"
  };

  private static final String[] ALLOWED_METHODS = {
    "DELETE"
  };

  private static final String[] ALLOWED_HEADERS = {
    HttpHeaders.PROTOCOL_VERSION,
    HttpHeaders.MCP_SESSION_ID,
    HttpHeaders.LAST_EVENT_ID
  };

  private static final String[] EXPOSED_HEADERS = {
    HttpHeaders.PROTOCOL_VERSION,
    HttpHeaders.MCP_SESSION_ID,
    HttpHeaders.LAST_EVENT_ID
  };

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
  private String                issuerUri;

  @Bean
  @Order(1)
  SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                             McpInternalOAuthClientService aiOAuthService,
                                             McpServerOAuthClientProperties oAuthClientProperties,
                                             AuthenticationEntryPoint authenticationEntryPoint) throws Exception {
    return http.securityMatcher("/**")
               .csrf(csrf -> csrf.disable())
               .cors(Customizer.withDefaults())
               .authorizeHttpRequests(authorize -> authorize.requestMatchers("/.well-known/**")
                                                            .permitAll()
                                                            .anyRequest()
                                                            .authenticated())
               .oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(c -> configureOpaqueToken(c,
                                                                                            oAuthClientProperties,
                                                                                            aiOAuthService))
                                                     .authenticationEntryPoint(authenticationEntryPoint))
               .build();
  }

  @Bean
  OpaqueTokenIntrospector opaqueTokenIntrospector(McpInternalOAuthClientService aiOAuthService,
                                                  McpServerOAuthClientProperties oAuthClientProperties) {
    OpaqueTokenIntrospector delegate = SpringOpaqueTokenIntrospector.withIntrospectionUri(oAuthClientProperties.getOpaquetoken()
                                                                                                               .getIntrospectionUri())
                                                                    .clientId(oAuthClientProperties.getIntrospectionClient()
                                                                                                   .getRegistration()
                                                                                                   .getClientId())
                                                                    .clientSecret(aiOAuthService.getClientSecret())
                                                                    .build();
    return token -> {
      OAuth2AuthenticatedPrincipal principal = delegate.introspect(token);
      boolean hasScope = principal.getAttribute("scope") != null;
      Collection<GrantedAuthority> authorities = hasScope ? Arrays.stream(((String) principal.getAttribute("scope")).split(" "))
                                                                  .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                                                                  .collect(Collectors.toList()) :
                                                          List.of();
      return new DefaultOAuth2AuthenticatedPrincipal(principal.getName(),
                                                     principal.getAttributes(),
                                                     authorities);
    };
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

  private void configureOpaqueToken(OAuth2ResourceServerConfigurer<HttpSecurity>.OpaqueTokenConfigurer opaqueTokenConfigurer,
                                    McpServerOAuthClientProperties mcpServerOAuthClientProperties,
                                    McpInternalOAuthClientService aiOAuthService) {
    withDefaults().customize(opaqueTokenConfigurer);
    opaqueTokenConfigurer.introspectionUri(mcpServerOAuthClientProperties.getOpaquetoken().getIntrospectionUri());
    opaqueTokenConfigurer.introspectionClientCredentials(mcpServerOAuthClientProperties.getIntrospectionClient()
                                                                                       .getRegistration()
                                                                                       .getClientId(),
                                                         aiOAuthService.getClientSecret());
  }

}

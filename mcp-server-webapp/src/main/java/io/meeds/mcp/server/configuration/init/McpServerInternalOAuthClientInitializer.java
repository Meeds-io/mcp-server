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
package io.meeds.mcp.server.configuration.init;

import static io.meeds.oauth2.server.util.EntityMapper.CLIENT_SERVICE_SETTING;
import static io.meeds.oauth2.server.util.EntityMapper.CLIENT_SYSTEM_SETTING;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerProperties;
import org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerProperties.Client;
import org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerProperties.Registration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import io.meeds.common.ContainerTransactional;
import io.meeds.mcp.server.model.McpServerOAuthClientProperties;
import io.meeds.mcp.server.service.McpInternalOAuthClientService;
import io.meeds.oauth2.server.service.OAuthClientService;

import jakarta.annotation.PostConstruct;

@Component
public class McpServerInternalOAuthClientInitializer {

  @Autowired
  private McpServerOAuthClientProperties oAuthClientProperties;

  @Autowired
  private OAuthClientService             oAuthClientService;

  @Autowired
  private McpInternalOAuthClientService  aiOAuthService;

  @Autowired
  private PasswordEncoder                passwordEncoder;

  @PostConstruct
  public void init() {
    saveClient(oAuthClientProperties.getInternalClient());
    saveClient(oAuthClientProperties.getIntrospectionClient());
  }

  @ContainerTransactional
  protected void saveClient(Client client) {
    RegisteredClient registeredClient = getRegisteredClient(client);
    oAuthClientService.saveClient(registeredClient);
  }

  private RegisteredClient getRegisteredClient(Client client) {
    Registration registration = client.getRegistration();
    PropertyMapper map = PropertyMapper.get();
    String clientId = registration.getClientId();

    RegisteredClient.Builder builder = RegisteredClient.withId(clientId);
    builder.clientId(clientId);
    builder.clientSecret(passwordEncoder.encode(aiOAuthService.getClientSecret()));
    map.from(registration::getClientName).to(builder::clientName);
    registration.getClientAuthenticationMethods()
                .forEach(clientAuthenticationMethod -> map.from(clientAuthenticationMethod)
                                                          .whenHasText()
                                                          .as(ClientAuthenticationMethod::new)
                                                          .to(builder::clientAuthenticationMethod));
    registration.getAuthorizationGrantTypes()
                .forEach(authorizationGrantType -> map.from(authorizationGrantType)
                                                      .whenHasText()
                                                      .as(AuthorizationGrantType::new)
                                                      .to(builder::authorizationGrantType));
    registration.getRedirectUris()
                .forEach(redirectUri -> map.from(redirectUri)
                                           .whenHasText()
                                           .to(builder::redirectUri));
    registration.getPostLogoutRedirectUris()
                .forEach(redirectUri -> map.from(redirectUri)
                                           .whenHasText()
                                           .to(builder::postLogoutRedirectUri));
    registration.getScopes()
                .forEach(scope -> map.from(scope)
                                     .whenHasText()
                                     .to(builder::scope));
    builder.clientSettings(getClientSettings(client, map));
    builder.tokenSettings(getTokenSettings(client, map));
    return builder.build();
  }

  private ClientSettings getClientSettings(Client client, PropertyMapper map) {
    ClientSettings.Builder builder = ClientSettings.builder();
    map.from(client::isRequireProofKey).to(builder::requireProofKey);
    map.from(client::isRequireAuthorizationConsent).to(builder::requireAuthorizationConsent);
    map.from(client::getJwkSetUri).to(builder::jwkSetUrl);
    map.from(client::getTokenEndpointAuthenticationSigningAlgorithm)
       .as(this::jwsAlgorithm)
       .to(builder::tokenEndpointAuthenticationSigningAlgorithm);
    builder.setting(CLIENT_SYSTEM_SETTING, true);
    builder.setting(CLIENT_SERVICE_SETTING, true);
    return builder.build();
  }

  private TokenSettings getTokenSettings(Client client, PropertyMapper map) {
    OAuth2AuthorizationServerProperties.Token token = client.getToken();
    TokenSettings.Builder builder = TokenSettings.builder();
    map.from(token::getAuthorizationCodeTimeToLive).to(builder::authorizationCodeTimeToLive);
    map.from(token::getAccessTokenTimeToLive).to(builder::accessTokenTimeToLive);
    map.from(token::getAccessTokenFormat).as(OAuth2TokenFormat::new).to(builder::accessTokenFormat);
    map.from(token::getDeviceCodeTimeToLive).to(builder::deviceCodeTimeToLive);
    map.from(token::isReuseRefreshTokens).to(builder::reuseRefreshTokens);
    map.from(token::getRefreshTokenTimeToLive).to(builder::refreshTokenTimeToLive);
    map.from(token::getIdTokenSignatureAlgorithm)
       .as(this::signatureAlgorithm)
       .to(builder::idTokenSignatureAlgorithm);
    return builder.build();
  }

  private JwsAlgorithm jwsAlgorithm(String signingAlgorithm) {
    String name = signingAlgorithm.toUpperCase(Locale.ROOT);
    JwsAlgorithm jwsAlgorithm = SignatureAlgorithm.from(name);
    if (jwsAlgorithm == null) {
      jwsAlgorithm = MacAlgorithm.from(name);
    }
    return jwsAlgorithm;
  }

  private SignatureAlgorithm signatureAlgorithm(String signatureAlgorithm) {
    return SignatureAlgorithm.from(signatureAlgorithm.toUpperCase(Locale.ROOT));
  }

}

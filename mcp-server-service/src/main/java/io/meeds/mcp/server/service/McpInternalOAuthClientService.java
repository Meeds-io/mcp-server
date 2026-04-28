/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2025 Meeds Association contact@meeds.io
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
package io.meeds.mcp.server.service;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.web.security.security.SecureRandomService;

import lombok.Synchronized;

/**
 * A Service used to generate a comment client secret between MCP Client and
 * OAuth Server
 */
@Service
public class McpInternalOAuthClientService {

  @Value("${meeds.oauth2.client.registration-id:mcp-internal}")
  private String              registrationId;

  @Value("${meeds.oauth2.client.secret.length:48}")
  private int                 secretLength;

  @Autowired
  private SecureRandomService secureRandomService;

  private String              clientSecret;

  public String getClientRegistrationId() {
    return registrationId;
  }

  @Synchronized
  public String getClientSecret() {
    if (clientSecret == null) {
      SecureRandom secureRandom = secureRandomService.getSecureRandom();
      byte[] bytes = new byte[secretLength];
      secureRandom.nextBytes(bytes);
      clientSecret = Base64.getUrlEncoder()
                           .withoutPadding()
                           .encodeToString(bytes);
    }
    return clientSecret;
  }

}

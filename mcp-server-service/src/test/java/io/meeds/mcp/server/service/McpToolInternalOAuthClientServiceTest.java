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
package io.meeds.mcp.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.web.security.security.SecureRandomService;

@SpringBootTest(classes = { McpInternalOAuthClientService.class, })
@TestPropertySource(properties = {
  "meeds.oauth2.client.registration-id=mcp-internal-test",
  "meeds.oauth2.client.secret.length=64"
})
@ExtendWith(MockitoExtension.class)
class McpToolInternalOAuthClientServiceTest {

  @MockitoBean
  private SecureRandomService           secureRandomService;

  @Autowired
  private McpInternalOAuthClientService aiOAuthService;

  @BeforeEach
  void setUp() {
    when(secureRandomService.getSecureRandom()).thenReturn(new SecureRandom());
  }

  @Test
  void testGetClientRegistrationId() {
    assertEquals("mcp-internal-test", aiOAuthService.getClientRegistrationId());
  }

  @Test
  void testGetClientSecret() {
    String clientSecret = aiOAuthService.getClientSecret();
    assertNotNull(clientSecret);
    assertEquals(64, Base64.getUrlDecoder().decode(clientSecret.getBytes()).length);
  }

}

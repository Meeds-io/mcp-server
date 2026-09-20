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
package io.meeds.mcp.server.plugin;

import static io.meeds.mcp.server.util.McpToolUtils.MCP_SERVER_FEATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.ExoFeatureService;

import io.meeds.mcp.server.service.McpServerAudienceService;

/**
 * Pins the seam every other caller of the gate rides on: the plugin's name,
 * its self-registration, and the delegation to
 * {@link McpServerAudienceService}. A test that mocks {@code ExoFeatureService}
 * — as the tool-service suite must — cannot see any of this, because the mock
 * answers in the plugin's place.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP server feature plugin")
class McpServerFeaturePluginTest {

  private static final String      USERNAME = "john";

  @Mock
  private ExoFeatureService        featureService;

  @Mock
  private McpServerAudienceService audienceService;

  @InjectMocks
  private McpServerFeaturePlugin   plugin;

  @Test
  @DisplayName("Registers itself under the mcp.server feature name")
  void registersItselfUnderTheFeatureName() {
    assertEquals(MCP_SERVER_FEATURE, plugin.getName());

    plugin.init();

    // Without this registration ExoFeatureServiceImpl falls back to the
    // exo.feature.mcp.server.permissions property and the stored audience is
    // never consulted at all
    verify(featureService).addFeaturePlugin(plugin);
  }

  @Test
  @DisplayName("Answers the audience question, and nothing else")
  void answersTheAudienceQuestion() {
    when(audienceService.isUserInAudience(USERNAME)).thenReturn(true);
    assertTrue(plugin.isFeatureActiveForUser(MCP_SERVER_FEATURE, USERNAME));

    when(audienceService.isUserInAudience(USERNAME)).thenReturn(false);
    assertFalse(plugin.isFeatureActiveForUser(MCP_SERVER_FEATURE, USERNAME));
  }

}

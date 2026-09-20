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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.FeaturePlugin;

import io.meeds.mcp.server.service.McpServerAudienceService;

import jakarta.annotation.PostConstruct;

/**
 * Teaches {@code ExoFeatureService} who the {@code mcp.server} feature is
 * active for. Registering it changes what
 * {@code isFeatureActiveForUser("mcp.server", username)} answers for every
 * caller, present and future, without any of them knowing that the audience
 * exists or how it is stored.
 * <p>
 * Registering it also switches off the
 * {@code exo.feature.mcp.server.permissions} system-property fallback that
 * {@code ExoFeatureServiceImpl} applies when no plugin is registered — which is
 * exactly why {@link McpServerAudienceService} reads that same property as its
 * default: an existing deployment that had narrowed MCP access with it must
 * stay narrowed.
 * <p>
 * The plugin answers the audience question only. The global on/off flag is
 * checked by {@code ExoFeatureServiceImpl.isFeatureActiveForUser} before it
 * ever delegates here, so a globally disabled MCP server stays disabled for
 * everybody whatever the audience says.
 * <p>
 * <b>This plugin is the read side of the gate, not the gate.</b> The gate —
 * {@code McpServerToolService.isMcpServerEnabledForUser}, asked at the door
 * and at the token endpoint — reads {@link McpServerAudienceService} directly,
 * because {@code ExoFeatureServiceImpl.isFeatureActiveForUser} answers
 * <em>everybody</em> on a plugin-registry miss and an enforcement point must
 * not fail open. What registering this plugin buys is that
 * {@code GET /portal/rest/v1/features/mcp.server} answers per user, which the
 * administration UI of the follow-up task (EXO-90441) needs without a new
 * endpoint. It is not dead code, though nothing on the {@code /mcp} request
 * path depends on it.
 */
@Component
public class McpServerFeaturePlugin extends FeaturePlugin {

  @Autowired
  private ExoFeatureService        featureService;

  @Autowired
  private McpServerAudienceService audienceService;

  /**
   * Self-registers with {@code ExoFeatureService}. Done from
   * {@code @PostConstruct} rather than from the kernel's {@code start()}
   * because this is a Spring bean in the MCP server's own WAR context, which
   * boots after the kernel component it registers into.
   */
  @Override
  @PostConstruct
  public void init() {
    featureService.addFeaturePlugin(this);
  }

  /**
   * @return the feature name this plugin answers for, {@code mcp.server}
   */
  @Override
  public String getName() {
    return MCP_SERVER_FEATURE;
  }

  /**
   * @param featureName the feature being resolved, always {@link #getName()}
   *                    here since a plugin is registered under its own name
   * @param username    the platform login of the end user
   * @return true when that user belongs to the MCP audience
   */
  @Override
  public boolean isFeatureActiveForUser(String featureName, String username) {
    return audienceService.isUserInAudience(username);
  }

}

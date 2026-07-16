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
package io.meeds.mcp.server.tool.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(value = Include.NON_EMPTY)
public record NotificationSettingsModel(
                                       List<NotificationChannelModel> channels,
                                       List<NotificationPluginModel> plugins,
                                       @JsonProperty("muted_space_ids")
                                       List<Long> mutedSpaceIds) {

  @JsonInclude(value = Include.NON_EMPTY)
  public record NotificationChannelModel(
                                        @JsonProperty("channel_id")
                                        String id,
                                        @JsonProperty("enabled")
                                        boolean enabled) {
  }

  @JsonInclude(value = Include.NON_EMPTY)
  public record NotificationPluginModel(
                                       @JsonProperty("plugin_id")
                                       String id,
                                       @JsonProperty("enabled_channels")
                                       List<String> enabledChannels) {
  }

}

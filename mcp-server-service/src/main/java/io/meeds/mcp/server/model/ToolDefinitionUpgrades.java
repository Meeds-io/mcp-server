/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2026 Meeds Association contact@meeds.io
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
package io.meeds.mcp.server.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The content of an {@code ai-tool-definition-upgrades.json} file, shipped by
 * an add-on beside its {@code ai-tool-definitions.json}. It lives in its own
 * file, never as a key of a definition: a server that does not know a key of a
 * definition leaves the whole tool out. Unknown properties are ignored.
 *
 * @param tools the upgrade entries, one per tool whose definition changed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolDefinitionUpgrades(@JsonProperty("tools") List<ToolDefinitionUpgrade> tools) {
}

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
 * One entry of an {@code ai-tool-definition-upgrades.json} file: the digests
 * of the definitions of a tool that an earlier version of its add-on shipped,
 * each computed by {@code McpToolUtils#definitionDigest(String, String)}. A
 * saved definition whose digest is listed was never edited by an
 * administrator, so it takes the shipped description and input schema at the
 * next registry build. Unknown properties are ignored, so a field added by a
 * newer add-on never drops the entry on an older server.
 *
 * @param name                the MCP tool name
 * @param replacedDefinitions the digests of the definitions the shipped one
 *                              replaces
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolDefinitionUpgrade(@JsonProperty("name") String name,
                                    @JsonProperty("replaced_definitions") List<String> replacedDefinitions) {
}

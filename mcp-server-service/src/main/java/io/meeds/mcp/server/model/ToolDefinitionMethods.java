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
package io.meeds.mcp.server.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The content of an {@code ai-tool-definitions.json} file: its tools and the
 * Font Awesome icon name its tools use when they declare none. Unknown
 * properties are ignored, so a field added by a newer add-on never drops the
 * whole file on an older server.
 *
 * @param tools the tool definitions
 * @param icon  the file's default Font Awesome icon name, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolDefinitionMethods(@JsonProperty("tools") List<SimpleToolDefinition> tools,
                                    @JsonProperty("icon") @JsonInclude(Include.NON_EMPTY) String icon) {

  /**
   * @param tools the tool definitions, with no file default icon
   */
  public ToolDefinitionMethods(List<SimpleToolDefinition> tools) {
    this(tools, null);
  }

}

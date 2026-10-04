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
package io.meeds.mcp.server.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.util.McpToolIconUtils;
import io.meeds.mcp.server.util.McpToolUtils;

class ToolDefinitionIconsTest {

  /**
   * Every tool of this module declares an icon, and every declared icon has
   * a bundled SVG, so external MCP clients get an icon for each of them.
   */
  @Test
  void everyToolDeclaresAnIconThatResolves() {
    URL url = getClass().getClassLoader().getResource("ai-tool-definitions.json");
    assertNotNull(url);
    List<SimpleToolDefinition> tools = McpToolUtils.parseToolDefinitions(url);
    assertFalse(tools.isEmpty());
    for (SimpleToolDefinition tool : tools) {
      assertFalse(StringUtils.isBlank(tool.getIcon()), tool.getName() + " declares no icon");
      assertFalse(McpToolIconUtils.toMcpIcons(tool.getIcon()).isEmpty(),
                  tool.getName() + " declares an icon with no bundled SVG: " + tool.getIcon());
    }
  }

}

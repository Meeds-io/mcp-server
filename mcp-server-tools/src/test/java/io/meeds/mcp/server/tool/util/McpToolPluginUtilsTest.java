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
package io.meeds.mcp.server.tool.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McpToolPluginUtilsTest {

  /**
   * A limit above the bound is lowered to it.
   */
  @Test
  void clampsALimitAboveTheBound() {
    assertEquals(100, McpToolPluginUtils.clampLimit(101, 10));
    assertEquals(100, McpToolPluginUtils.clampLimit(Integer.MAX_VALUE, 10));
    assertEquals(20, McpToolPluginUtils.clampLimit(21, 10, 20));
  }

  /**
   * A limit within the bound is applied as asked.
   */
  @Test
  void keepsALimitWithinTheBound() {
    assertEquals(100, McpToolPluginUtils.clampLimit(100, 10));
    assertEquals(1, McpToolPluginUtils.clampLimit(1, 10));
  }

  /**
   * No limit, zero or a negative one gives the default.
   */
  @Test
  void appliesTheDefaultWithoutAPositiveLimit() {
    assertEquals(10, McpToolPluginUtils.clampLimit(null, 10));
    assertEquals(10, McpToolPluginUtils.clampLimit(0, 10));
    assertEquals(10, McpToolPluginUtils.clampLimit(-5, 10));
  }
}

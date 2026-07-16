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
package io.meeds.mcp.server.tool;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class TagMcpToolTest extends IntegrationTestBase {

  @Autowired
  private TagMcpTool tagMcpTool;

  @Test
  void getContentTagsIsEmptyForUntaggedContent() throws Exception {
    // no favorite ACL plugin is registered for 'activity' in this test container, so access defaults to allowed
    assertNotNull(tagMcpTool.getContentTags("activity", "1"));
  }

  @Test
  void getContentTagsWithBlankFails() {
    assertThrows(IllegalArgumentException.class, () -> tagMcpTool.getContentTags("", "1"));
  }

  @Test
  void searchTags() throws Exception {
    assertNotNull(tagMcpTool.searchTags("road", 10));
  }

  @Test
  void getContentsByTag() {
    assertNotNull(tagMcpTool.getContentsByTag("nonexistent-tag", "activity", 0, 10));
  }

  @Test
  void getContentsByTagWithBlankFails() {
    assertThrows(IllegalArgumentException.class, () -> tagMcpTool.getContentsByTag("", null, 0, 10));
  }

}

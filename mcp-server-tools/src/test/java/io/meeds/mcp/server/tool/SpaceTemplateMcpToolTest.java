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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class SpaceTemplateMcpToolTest extends IntegrationTestBase {

  @Autowired
  private SpaceTemplateMcpTool spaceTemplateMcpTool;

  @Test
  void listSpaceTemplates() {
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);

    assertNotNull(templates);
  }

  @Test
  void listSpaceTemplatesWithQuery() {
    List<SpaceTemplateModel> allTemplates = spaceTemplateMcpTool.listSpaceTemplates(null);

    if (!allTemplates.isEmpty()) {
      SpaceTemplateModel first = allTemplates.get(0);

      List<SpaceTemplateModel> result = spaceTemplateMcpTool.listSpaceTemplates(first.getName());

      assertFalse(result.isEmpty());
      assertTrue(result.stream().anyMatch(t -> t.getId() == first.getId()));
    }
  }

  @Test
  void getSpaceTemplateById() {
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);

    if (!templates.isEmpty()) {
      SpaceTemplateModel template = spaceTemplateMcpTool.getSpaceTemplateById(templates.get(0).getId());

      assertNotNull(template);
      assertEquals(templates.get(0).getId(), template.getId());
    }
  }

  @Test
  void getSpaceTemplateByIdWhenNotFound() {
    SpaceTemplateModel template = spaceTemplateMcpTool.getSpaceTemplateById(-1L);

    assertNull(template);
  }

}
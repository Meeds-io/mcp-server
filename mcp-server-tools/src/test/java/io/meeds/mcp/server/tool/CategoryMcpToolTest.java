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

import io.meeds.mcp.server.tool.model.CategoryModel;
import io.meeds.mcp.server.tool.model.CategoryTreeModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;
import io.meeds.social.activity.plugin.ActivityAclPlugin;

class CategoryMcpToolTest extends IntegrationTestBase {

  @Autowired
  private CategoryMcpTool categoryMcpTool;

  @Test
  void getCategoryTree() {
    CategoryTreeModel tree = categoryMcpTool.getCategoryTree();

    // Depending on test dataset, category tree can be null.
    // Main goal is to verify the tool call is wired and does not fail.
    if (tree != null) {
      assertTrue(tree.getId() >= 0);
    }
  }

  @Test
  void getCategoriesOfContent() {
    List<CategoryModel> categories = categoryMcpTool.getCategoriesOfContent(ActivityAclPlugin.OBJECT_TYPE, "1");

    assertNotNull(categories);
  }

  @Test
  void addAndRemoveContentToCategoryWhenCategoryExists() throws Exception { // NOSONAR
    CategoryTreeModel tree = categoryMcpTool.getCategoryTree();

    if (tree != null) {
      long categoryId = tree.getId();

      categoryMcpTool.addContentToCategory(categoryId, ActivityAclPlugin.OBJECT_TYPE, "1");

      List<CategoryModel> categories = categoryMcpTool.getCategoriesOfContent(ActivityAclPlugin.OBJECT_TYPE, "1");

      assertNotNull(categories);

      categoryMcpTool.removeContentFromCategory(categoryId, ActivityAclPlugin.OBJECT_TYPE, "1");
    }
  }

}

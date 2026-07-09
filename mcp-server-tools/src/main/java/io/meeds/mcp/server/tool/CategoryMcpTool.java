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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.CategoryModel;
import io.meeds.mcp.server.tool.model.CategoryTreeModel;
import io.meeds.mcp.server.tool.plugin.CategoryMcpToolPlugin;
import io.meeds.social.category.model.CategoryFilter;
import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.model.CategoryTree;
import io.meeds.social.category.plugin.CategoryPlugin;
import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;

import lombok.SneakyThrows;

@Service
public class CategoryMcpTool implements McpToolPlugin {

  private static final long           MAX_TO_LOAD = 100;

  @Autowired
  private CategoryService             categoryService;

  @Autowired
  private CategoryLinkService         categoryLinkService;

  @Autowired(required = false)
  private List<CategoryMcpToolPlugin> categoryMcpToolPlugins;

  @Autowired(required = false)
  private List<CategoryPlugin>        categoryPlugins;

  /**
   * List the content types that support categories - the values to pass as
   * content_type to get_categories_of_content, add_content_to_category and
   * remove_content_from_category. Dynamically reflects the registered category
   * plugins. Note: "email" content is categorized through its own dedicated
   * list_email_categories / add_email_category / remove_email_category tools (the
   * agent works with the IMAP mail id, not the internal id these generic tools
   * expect), so prefer those for email.
   */
  public List<String> getContentTypes() {
    return categoryPlugins == null ? List.of()
                                   : categoryPlugins.stream()
                                                    .map(CategoryPlugin::getType)
                                                    .filter(type -> type != null && !type.isBlank())
                                                    .distinct()
                                                    .sorted()
                                                    .toList();
  }

  public CategoryTreeModel getCategoryTree() {
    CategoryFilter filter = new CategoryFilter();
    filter.setDepth(MAX_TO_LOAD);
    filter.setLimit(MAX_TO_LOAD);
    CategoryTree categoryTree = categoryService.getCategoryTree(filter,
                                                                getCurrentUserName(),
                                                                getCurrentUserLocale());
    return categoryTree == null ? null : new CategoryTreeModel(categoryTree);
  }

  public List<CategoryModel> getCategoriesOfContent(String contentType, String contentId) {
    return categoryLinkService.getLinkedIds(new CategoryObject(contentType, contentId, 0l))
                              .stream()
                              .map(id -> {
                                try {
                                  return categoryService.getCategory(id, getCurrentUserName(), getCurrentUserLocale());
                                } catch (IllegalAccessException | ObjectNotFoundException e) {
                                  return null;
                                }
                              })
                              .filter(Objects::nonNull)
                              .map(c -> new CategoryModel(c, categoryService.canManageLink(c, getCurrentUserName())))
                              .toList();
  }

  public void removeContentFromCategory(long categoryId,
                                        String contentType,
                                        String contentId) throws IllegalAccessException,
                                                          ObjectNotFoundException {
    categoryLinkService.unlink(categoryId, new CategoryObject(contentType, contentId, 0l), getCurrentUserName());
  }

  public void addContentToCategory(long categoryId,
                                   String contentType,
                                   String contentId) {
    if (categoryMcpToolPlugins == null) {
      categoryMcpToolPlugins = Collections.emptyList();
    }
    categoryMcpToolPlugins.stream()
                          .filter(p -> p.match(contentType, contentId))
                          .findFirst()
                          .ifPresentOrElse(p -> addContentToCategory(p, categoryId, contentType, contentId, getCurrentUserName()),
                                           () -> addContentToCategory(categoryId, contentType, contentId, getCurrentUserName()));
  }

  @SneakyThrows
  private void addContentToCategory(long categoryId,
                                    String contentType,
                                    String contentId,
                                    String userName) {
    categoryLinkService.link(categoryId,
                             new CategoryObject(contentType, contentId, 0l),
                             userName);
  }

  @SneakyThrows
  private void addContentToCategory(CategoryMcpToolPlugin plugin,
                                    long categoryId,
                                    String contentType,
                                    String contentId,
                                    String userName) {
    plugin.addCategory(categoryId, contentType, contentId, userName);
  }

}

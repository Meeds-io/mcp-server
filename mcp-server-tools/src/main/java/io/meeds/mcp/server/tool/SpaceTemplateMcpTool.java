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
package io.meeds.mcp.server.tool;

import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceTemplateModel;

import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.util.SpaceToolUtils;
import io.meeds.social.space.template.model.SpaceTemplate;
import io.meeds.social.space.template.model.SpaceTemplateFilter;
import io.meeds.social.space.template.service.SpaceTemplateService;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SpaceTemplateMcpTool implements McpToolPlugin {

  private SpaceTemplateService spaceTemplateService;

  public SpaceTemplateMcpTool(SpaceTemplateService spaceTemplateService) {
    this.spaceTemplateService = spaceTemplateService;
  }

  public List<SpaceTemplateModel> listSpaceTemplates(String query) {
    String username = getCurrentUserName();
    SpaceTemplateFilter spaceTemplateFilter = new SpaceTemplateFilter(username,
                                                                      getCurrentUserLocale(),
                                                                      false);
    return spaceTemplateService.getSpaceTemplates(spaceTemplateFilter, Pageable.unpaged(), true)
                               .stream()
                               .map(SpaceToolUtils::toSpaceTemplateModel)
                               .filter(Objects::nonNull)
                               .filter(t -> StringUtils.isBlank(query)
                                            || t.getName().toLowerCase().contains(query.toLowerCase())
                                            || (StringUtils.isNotBlank(t.getDescription())
                                                && t.getDescription().toLowerCase().contains(query.toLowerCase())))
                               .toList();
  }

  public SpaceTemplateModel getSpaceTemplateById(long templateId) {
    try {
      SpaceTemplate spaceTemplate = spaceTemplateService.getSpaceTemplate(templateId,
                                                                          getCurrentUserName(),
                                                                          getCurrentUserLocale(),
                                                                          true);
      return toSpaceTemplateModel(spaceTemplate);
    } catch (Exception e) {
      log.warn("Error retrieving a Space Template with id {}", templateId, e);
      return null;
    }
  }

}

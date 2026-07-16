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
package io.meeds.mcp.server.tool.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class SpaceModel {

  @JsonProperty("space_id")
  private long            spaceId;

  @JsonProperty("space_template_id")
  private long            spaceTemplateId;

  private String          name;

  private String          description;

  private Visibility      visibility;

  private Registration    registration;

  private String          url;

  @JsonProperty("avatar_url")
  private String          avatarUrl;

  @JsonProperty("banner_url")
  private String          bannerUrl;

  @JsonProperty("members_count")
  private int             membersCount;

  @JsonProperty("managers_count")
  private int             managersCount;

  @JsonProperty("category_ids")
  private List<Long>      categoryIds;

  @JsonProperty("my_roles")
  private List<SpaceRole> myRoles;

  @JsonProperty("parent_space_id")
  private Long            parentSpaceId;

}

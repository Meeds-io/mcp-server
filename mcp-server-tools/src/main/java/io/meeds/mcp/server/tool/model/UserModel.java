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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class UserModel {

  private String  username;

  @JsonProperty("login_id")
  private String  loginId;

  @JsonProperty("first_name")
  private String  firstName;

  @JsonProperty("last_name")
  private String  lastName;

  @JsonProperty("display_name")
  private String  displayName;

  private String  country;

  private String  city;

  private String  company;

  private String  department;

  private String  team;

  private String  position;

  @JsonProperty("about_me")
  private String  aboutMe;

  @JsonProperty("avatar_url")
  private String  avatarUrl;

  @JsonProperty("banner_url")
  private String  bannerUrl;

  @JsonProperty("profile_page_url")
  private String  profilePageUrl;

  @JsonProperty("is_guest")
  private boolean isGuest;

  @JsonProperty("is_internal")
  private boolean isInternal;

  public String getName() {
    return displayName;
  }

}

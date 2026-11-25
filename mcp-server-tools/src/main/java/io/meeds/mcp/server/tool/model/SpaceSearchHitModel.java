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
package io.meeds.mcp.server.tool.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;

/**
 * One hit of {@code search_spaces}: just enough for an assistant to tell the
 * space it was asked about from its neighbours and to act on it, without the
 * full {@link SpaceModel}.
 *
 * @param spaceId the space technical identifier
 * @param name the space display name
 * @param description the space description, shortened when long
 * @param url the link to the space
 * @param visibility whether the space is listed in the spaces directory
 * @param registration how a user joins the space
 * @param member whether the current user is a member of the space
 * @param myRoles the current user's roles in the space, empty when none
 * @param lastActivityDate the date of the latest post or comment in the
 *          space stream, only for a space whose stream the current user can
 *          read
 */
@JsonInclude(value = Include.NON_EMPTY)
public record SpaceSearchHitModel(
                                  @JsonProperty("space_id")
                                  long spaceId,
                                  String name,
                                  String description,
                                  String url,
                                  Visibility visibility,
                                  Registration registration,
                                  @JsonProperty("is_member")
                                  @JsonInclude(Include.ALWAYS)
                                  boolean member,
                                  @JsonProperty("my_roles")
                                  List<SpaceRole> myRoles,
                                  @JsonProperty("last_activity_date")
                                  String lastActivityDate) {
}

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

/**
 * A page of sub-spaces.
 *
 * @param subSpaces  the sub-spaces of the page, possibly fewer than the limit
 *                     asked while more follow
 * @param nextOffset the offset the next page starts at, null when the list
 *                     has ended
 */
@JsonInclude(value = Include.NON_NULL)
public record SubSpacesPageModel(
                                 @JsonProperty("sub_spaces")
                                 List<SpaceModel> subSpaces,
                                 @JsonProperty("next_offset")
                                 Integer nextOffset) {
}

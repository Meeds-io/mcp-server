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
 * A page of activities, saying whether more remain.
 *
 * @param activities the activities found
 * @param usedLimit  the largest number of activities the call returns
 * @param hasMore    whether more activities may match than the ones returned
 * @param nextOffset the offset that reads the next page, absent when no more
 *                   remain or when the tool takes no offset
 */
public record ActivityCollectionModel(
                                      List<ActivitySummaryModel> activities,
                                      @JsonProperty("used_limit")
                                      int usedLimit,
                                      @JsonProperty("has_more")
                                      boolean hasMore,
                                      @JsonProperty("next_offset")
                                      @JsonInclude(value = Include.NON_NULL)
                                      Integer nextOffset) {
}

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
package io.meeds.mcp.server.tool.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single work-experience entry of a user profile (rides
 * {@code Profile.EXPERIENCES}).
 */
@JsonInclude(value = Include.NON_EMPTY)
public record ExperienceModel(
                             @JsonProperty("experience_id")
                             String experienceId,
                             String company,
                             String position,
                             String skills,
                             @JsonProperty("start_date")
                             String startDate,
                             @JsonProperty("end_date")
                             String endDate,
                             @JsonProperty("is_current")
                             Boolean isCurrent,
                             String description) {
}

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

@JsonInclude(value = Include.NON_EMPTY)
public record ActivityModel(
                            @JsonProperty("activity_id")
                            long id,
                            @JsonProperty("shared_activity_id")
                            Long sharedActivityId,
                            String content,
                            String url,
                            @JsonProperty("pin_date")
                            String pinDate,
                            @JsonProperty("updated_date")
                            String updatedDate,
                            @JsonProperty("created_date")
                            String createDate,
                            /*
                             * Set only while the activity is a pending scheduled post; cleared
                             * once it is published, so its presence means "not published yet".
                             */
                            @JsonProperty("publication_start_time")
                            String publicationStartTime,
                            @JsonProperty("content_type")
                            String referencedContentType,
                            @JsonProperty("content_id")
                            String referencedContentId,
                            @JsonProperty("number_of_likes")
                            int numberOfLikes,
                            @JsonProperty("number_of_comments")
                            int numberOfComments,
                            @JsonProperty("number_of_shares")
                            int numberOfShares,
                            @JsonProperty("has_liked")
                            boolean hasLiked,
                            @JsonProperty("has_commented")
                            boolean hasCommented,
                            boolean hidden,
                            boolean pinned,
                            @JsonProperty("can_edit")
                            boolean canEdit,
                            @JsonProperty("can_delete")
                            boolean canDelete,
                            @JsonProperty("pin_author")
                            UserModel pinAuthor,
                            UserModel author,
                            SpaceModel space) {
}

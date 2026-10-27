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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An activity as an item of a list of activities: the fields of
 * {@link ActivityModel}, with its author, the author of its pin and its space
 * reduced to what names them. get_activity returns the full shape.
 */
@JsonInclude(value = Include.NON_EMPTY)
public record ActivitySummaryModel(
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
                                   UserSummaryModel pinAuthor,
                                   UserSummaryModel author,
                                   SpaceSummaryModel space) {
}

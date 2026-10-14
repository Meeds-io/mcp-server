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
package io.meeds.mcp.server.tool.plugin;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;

import io.meeds.mcp.server.plugin.SpaceScopeResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells which space a comment belongs to: the space of the activity it
 * comments on. A reply to a comment belongs there too, since its parent is
 * always the top activity of the thread. An activity that isn't a comment, a
 * comment on a user stream's activity, a missing comment or parent belong to
 * no space. Kept apart from the {@value ActivitySpaceScopeResolver#NAME}
 * resolver so that a tool taking an activity never accepts a comment id. Read
 * as the system, for the space scope decision only.
 */
@Service("commentSpaceScopeResolver")
@Slf4j
public class CommentSpaceScopeResolver implements SpaceScopeResolver {

  /** The name the comment tools' {@code space_scope.resolver} gives. */
  public static final String NAME = "comment";

  @Autowired
  private ActivityManager    activityManager;

  /**
   * @return {@value #NAME}
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * @param objectId the comment id, without its {@code comment} prefix
   * @return the id of the space whose stream holds the commented activity, or
   *         null for an activity that isn't a comment, a comment on a user
   *         stream's activity, a missing comment or parent, an id that isn't
   *         one, or a lookup that fails
   */
  @Override
  public Long spaceOf(String objectId) {
    try {
      ExoSocialActivity comment = activityManager.getActivity(objectId);
      if (comment == null || !comment.isComment() || StringUtils.isBlank(comment.getParentId())) {
        return null;
      }
      ExoSocialActivity parent = activityManager.getActivity(comment.getParentId());
      if (parent == null || parent.isComment() || !StringUtils.isNumeric(parent.getSpaceId())) {
        return null;
      }
      return Long.valueOf(parent.getSpaceId());
    } catch (RuntimeException e) {
      log.debug("The space of comment '{}' couldn't be read", objectId, e);
      return null;
    }
  }

}

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

import io.meeds.mcp.server.plugin.ReadScopeResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells which space an activity belongs to: the space whose stream holds it.
 * A shared activity is a copy posted in the stream it was shared to, so it
 * belongs to that space, and the activity it was shared from to its own. A
 * comment, an activity of a user's stream and a missing one belong to no
 * space. Read as the system, for the read scope decision only.
 */
@Service("activityReadScopeResolver")
@Slf4j
public class ActivityReadScopeResolver implements ReadScopeResolver {

  /** The name the activity tools' {@code read_scope.resolver} gives. */
  public static final String NAME = "activity";

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
   * @param objectId the activity id
   * @return the id of the space whose stream holds the activity, or null for
   *         a comment, a user stream's activity, a missing activity, an id
   *         that isn't one, or a lookup that fails
   */
  @Override
  public Long spaceOf(String objectId) {
    try {
      ExoSocialActivity activity = activityManager.getActivity(objectId);
      if (activity == null || activity.isComment() || !StringUtils.isNumeric(activity.getSpaceId())) {
        return null;
      }
      return Long.valueOf(activity.getSpaceId());
    } catch (RuntimeException e) {
      log.debug("The space of activity '{}' couldn't be read", objectId, e);
      return null;
    }
  }

}

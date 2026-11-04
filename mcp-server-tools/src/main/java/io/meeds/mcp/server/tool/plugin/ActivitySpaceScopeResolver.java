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

import org.exoplatform.portal.config.UserACL;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.mcp.server.plugin.SpaceScopeResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells which space an activity belongs to: the space whose stream holds it.
 * A shared activity is a copy posted in the stream it was shared to, so it
 * belongs to that space, and the activity it was shared from to its own. A
 * comment, an activity of a user's stream and a missing one belong to no
 * space. Read as the system, for the space scope decision only. For a write
 * declaring {@code space_role: editor}, it also tells whether a person could
 * edit the activity in person, by social's own rule.
 */
@Service("activitySpaceScopeResolver")
@Slf4j
public class ActivitySpaceScopeResolver implements SpaceScopeResolver {

  /** The name the activity tools' {@code space_scope.resolver} gives. */
  public static final String NAME = "activity";

  @Autowired
  private ActivityManager    activityManager;

  @Autowired
  private IdentityManager    identityManager;

  @Autowired
  private UserACL            userAcl;

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

  /**
   * Tells whether a person could change the activity in person, as social
   * decides it for the post's own edit, image and publish actions
   * ({@link ActivityManager#isActivityEditable}): its poster, or, for a post
   * scheduled for later, whoever may manage the space's content.
   *
   * @param objectId the activity id
   * @param userName the person a digital worker would change the activity for
   * @return true when the person could change it; false for a comment, a
   *         missing activity, a deleted or disabled person, or a lookup that
   *         fails
   */
  @Override
  public Boolean canEdit(String objectId, String userName) {
    if (StringUtils.isBlank(userName)) {
      return false;
    }
    try {
      ExoSocialActivity activity = activityManager.getActivity(objectId);
      if (activity == null || activity.isComment()) {
        return false;
      }
      Identity person = identityManager.getOrCreateUserIdentity(userName);
      if (person == null || person.isDeleted() || !person.isEnable()) {
        return false;
      }
      org.exoplatform.services.security.Identity aclIdentity = userAcl.getUserIdentity(userName);
      return aclIdentity != null && activityManager.isActivityEditable(activity, aclIdentity);
    } catch (RuntimeException e) {
      log.debug("Whether '{}' may change activity '{}' couldn't be told", userName, objectId, e);
      return false;
    }
  }

}

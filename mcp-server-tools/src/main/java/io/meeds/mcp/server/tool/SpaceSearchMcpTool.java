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
package io.meeds.mcp.server.tool;

import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.getUserRoles;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toRegistration;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toVisibility;
import static io.meeds.mcp.server.util.McpToolUtils.formatDate;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.SpaceFilter;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.SpaceSearchHitModel;
import io.meeds.social.space.constant.SpaceRegistration;
import io.meeds.social.space.constant.SpaceVisibility;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds a space by the words of its name or description, so that an assistant
 * resolves the space a user names with one call instead of listing every
 * space.
 */
@Service
@Slf4j
public class SpaceSearchMcpTool implements McpToolPlugin {

  /** The most hits one search returns */
  public static final int    MAX_LIMIT              = 50;

  /** The length past which a hit's description is shortened */
  public static final int    DESCRIPTION_MAX_LENGTH = 300;

  /** The message of a search without a word to look for */
  public static final String MSG_QUERY_REQUIRED     = "A query is required: give a word of the space name or description";

  @Autowired
  private SpaceService       spaceService;

  @Autowired
  private IdentityManager    identityManager;

  @Autowired
  private ActivityManager    activityManager;

  /**
   * Searches the spaces the current user may see in the spaces directory, by
   * the words of their name or description, the best match first. A hidden
   * space is returned only to its members and to the users invited to it, as
   * in the spaces directory.
   *
   * @param query the words to look for, ignoring case and accents; required
   * @param limit the most hits to return, {@value McpToolPlugin#DEFAULT_LIMIT}
   *          when absent, at most {@value #MAX_LIMIT}
   * @return the matching spaces, the best match first
   * @throws IllegalArgumentException when the query holds no word
   */
  @SneakyThrows
  public List<SpaceSearchHitModel> searchSpaces(String query, Integer limit) {
    if (StringUtils.isBlank(query)) {
      throw new IllegalArgumentException(MSG_QUERY_REQUIRED);
    }
    String username = getCurrentUserName();
    int size = Math.clamp(getInteger(limit, DEFAULT_LIMIT), 1, MAX_LIMIT);
    Space[] spaces = spaceService.getVisibleSpacesWithListAccess(username, new SpaceFilter(query.trim()))
                                 .load(0, size);
    if (spaces == null) {
      return Collections.emptyList();
    }
    return Stream.of(spaces)
                 .filter(Objects::nonNull)
                 .map(space -> toHit(space, username))
                 .toList();
  }

  /**
   * Builds the hit of a space as the given user sees it.
   *
   * @param space the matching space
   * @param username the current user
   * @return the hit
   */
  private SpaceSearchHitModel toHit(Space space, String username) {
    String domain = CommonsUtils.getCurrentDomain();
    return new SpaceSearchHitModel(space.getSpaceId(),
                                   space.getDisplayName(),
                                   StringUtils.abbreviate(space.getDescription(), DESCRIPTION_MAX_LENGTH),
                                   "%s/portal/s/%s".formatted(domain, space.getSpaceId()),
                                   space.getVisibility() == null ? null :
                                                                 toVisibility(SpaceVisibility.valueOf(space.getVisibility()
                                                                                                           .toUpperCase())),
                                   space.getRegistration() == null ? null :
                                                                   toRegistration(SpaceRegistration.valueOf(space.getRegistration()
                                                                                                                 .toUpperCase())),
                                   spaceService.isMember(space, username),
                                   getUserRoles(spaceService, space, username),
                                   getLastActivityDate(space, username));
  }

  /**
   * Reads the date of the latest post or comment of a space stream, for a
   * user who can read that stream: a member of the space or one of its super
   * managers. Anyone else gets no date, so a private space's activity is not
   * told to a user outside it.
   *
   * @param space the space
   * @param username the current user
   * @return the ISO-8601 date of the latest activity, null when the user
   *         cannot read the stream, the stream is empty or the date can't be
   *         read
   */
  private String getLastActivityDate(Space space, String username) {
    if (!spaceService.canViewSpace(space, username)) {
      return null;
    }
    try {
      Identity spaceIdentity = identityManager.getOrCreateSpaceIdentity(space.getPrettyName());
      if (spaceIdentity == null) {
        return null;
      }
      List<ExoSocialActivity> activities = activityManager.getActivitiesOfSpaceWithListAccess(spaceIdentity)
                                                          .loadAsList(0, 1);
      if (CollectionUtils.isEmpty(activities) || activities.getFirst() == null) {
        return null;
      }
      ExoSocialActivity latest = activities.getFirst();
      return latest.getUpdated() != null ? formatDate(latest.getUpdated()) : formatDate(latest.getPostedTime());
    } catch (RuntimeException e) {
      log.debug("Can't read the latest activity of space {}", space.getId(), e);
      return null;
    }
  }

}

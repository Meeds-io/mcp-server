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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
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
  public static final String MSG_QUERY_REQUIRED     = "A query is required: give a word of the space name or description, without a '~' before it";

  /** What separates two words of a query */
  private static final Pattern WORD_SEPARATOR     = Pattern.compile("[^\\p{L}\\p{N}]+");

  @Autowired
  private SpaceService       spaceService;

  @Autowired
  private IdentityManager    identityManager;

  @Autowired
  private ActivityManager    activityManager;

  /**
   * Searches the spaces the current user may see in the spaces directory, by
   * the words of their name or description, the best match first: among the
   * first {@value #MAX_LIMIT} the search engine returns, a space whose name
   * has a word starting with each word of the query comes before one that
   * only mentions them. The spaces are those of the spaces directory's
   * search, where a hidden space reaches its members and the users who
   * manage its space template (template administrators included), with
   * Elasticsearch and on the database fallback alike; a user only invited to
   * it doesn't get it ({@code XSpaceFilter#setSpaceFilter} drops the invited
   * status on the fallback).
   *
   * @param query the words to look for, ignoring case and accents; required
   * @param limit the most hits to return, {@value McpToolPlugin#DEFAULT_LIMIT}
   *          when absent, at most {@value #MAX_LIMIT}
   * @return the matching spaces, the best match first
   * @throws IllegalArgumentException when the query holds no word, or none
   *           left once the unified search syntax cuts what follows a
   *           {@code ~} in each word
   */
  @SneakyThrows
  public List<SpaceSearchHitModel> searchSpaces(String query, Integer limit) {
    List<String> words = words(query);
    // the condition searched is the query as SpaceFilter processes it: it must hold a word too
    SpaceFilter filter = new SpaceFilter(StringUtils.trimToEmpty(query));
    if (words.isEmpty() || words(filter.getSpaceNameSearchCondition()).isEmpty()) {
      throw new IllegalArgumentException(MSG_QUERY_REQUIRED);
    }
    String username = getCurrentUserName();
    int size = Math.clamp(getInteger(limit, DEFAULT_LIMIT), 1, MAX_LIMIT);
    Space[] spaces = spaceService.getVisibleSpacesWithListAccess(username, filter).load(0, MAX_LIMIT);
    if (spaces == null) {
      return Collections.emptyList();
    }
    return Stream.of(spaces)
                 .filter(Objects::nonNull)
                 .sorted(Comparator.comparingInt((Space space) -> nameMatch(space, query, words)).reversed())
                 .limit(size)
                 .map(space -> toHit(space, username))
                 .toList();
  }

  /**
   * Ranks how well a space name matches the query, so that the space the user
   * names comes before the spaces that only mention the words in their
   * description: the search engine scores a word in a short description
   * above the same word in the name.
   *
   * @param space a matching space
   * @param query the query
   * @param words the query words, without accents and in lower case
   * @return 2 when the name is the query, 1 when each word of the query
   *         starts a word of the name, 0 otherwise; ignoring case and accents
   */
  private int nameMatch(Space space, String query, List<String> words) {
    String name = fold(space.getDisplayName());
    if (name.equals(fold(query))) {
      return 2;
    } else if (!words.isEmpty() && words.stream().allMatch(word -> nameWords(name).anyMatch(n -> n.startsWith(word)))) {
      return 1;
    } else {
      return 0;
    }
  }

  /**
   * @param foldedName a space name, already folded
   * @return the words of the name
   */
  private Stream<String> nameWords(String foldedName) {
    return Stream.of(WORD_SEPARATOR.split(foldedName)).filter(StringUtils::isNotBlank);
  }

  /**
   * @param query the query
   * @return its words, without accents and in lower case
   */
  private List<String> words(String query) {
    return Stream.of(WORD_SEPARATOR.split(fold(query))).filter(StringUtils::isNotBlank).toList();
  }

  /**
   * @param text a text, possibly null
   * @return the text trimmed, in lower case and without accents
   */
  private String fold(String text) {
    return StringUtils.stripAccents(StringUtils.defaultString(text)).trim().toLowerCase(Locale.ROOT);
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

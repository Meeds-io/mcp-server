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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.model.ActivitySummaryModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

/**
 * The posts {@code get_user_activity_stream} returns for another user: social's
 * profile stream holds only that user's posts outside spaces, so the tool adds
 * their posts in the spaces both users are members of, and nothing from a
 * space the current user can't view (EXO-94931).
 */
class ActivityMcpToolUserStreamTest extends IntegrationTestBase {

  private static final String  POSTER   = "mary";

  private static final String  OUTSIDER = "demo";

  @Autowired
  private ActivityMcpTool      activityMcpTool;

  @Autowired
  private SpaceMcpTool         spaceMcpTool;

  @Autowired
  private SpaceTemplateMcpTool spaceTemplateMcpTool;

  @Autowired
  private SpaceService         spaceService;

  @Autowired
  private IdentityManager      identityManager;

  /**
   * Another user's newest post, made in a space both users are members of,
   * comes first, ahead of that user's older profile post, which stays listed.
   * Mutants: the common spaces not read (the space post is missing); the
   * merge sorted oldest first (the profile post comes first).
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void anotherUsersNewestPostInACommonSpaceComesFirst() throws Exception {
    SpaceModel space = createSpace();
    identityManager.getOrCreateUserIdentity(POSTER);
    spaceService.addMember(spaceService.getSpaceById(space.getSpaceId()), POSTER);

    actAs(POSTER);
    ActivityModel profilePost = activityMcpTool.createActivity(null, "Profile post " + UUID.randomUUID(), null);
    pause();
    ActivityModel spacePost = activityMcpTool.createActivity(space.getSpaceId(), "Space post " + UUID.randomUUID(), null);

    actAs(USERNAME);
    List<ActivitySummaryModel> posts = activityMcpTool.getUserActivityStream(POSTER, 0, 10);

    assertFalse(posts.isEmpty(), "the space post of " + POSTER + " is missing");
    assertEquals(spacePost.id(), posts.get(0).id());
    assertTrue(ids(posts).contains(profilePost.id()), "the profile post of " + POSTER + " is missing");
  }

  /**
   * A user whose only post is in a space the current user was merely invited
   * to has no post the current user can see: the list is empty. Social counts
   * an invitation as a common space, so this pins that its posts stay out.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void aPostInASpaceTheCurrentUserIsOnlyInvitedToIsLeftOut() throws Exception {
    identityManager.getOrCreateUserIdentity(OUTSIDER);
    actAs(OUTSIDER);
    SpaceModel space = createSpace();
    activityMcpTool.createActivity(space.getSpaceId(), "Members only " + UUID.randomUUID(), null);
    spaceService.addInvitedUser(spaceService.getSpaceById(space.getSpaceId()), USERNAME);

    actAs(USERNAME);
    List<ActivitySummaryModel> posts = activityMcpTool.getUserActivityStream(OUTSIDER, 0, 10);

    assertEquals(List.of(), ids(posts));
  }

  /**
   * A page of another user's posts is cut from the merged list, newest
   * first: offset 1 and limit 2 give the second and third newest. Mutant: the
   * page cut from each source before the merge.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void aPageOfAnotherUsersPostsIsCutFromTheMergedList() throws Exception {
    SpaceModel space = createSpace();
    identityManager.getOrCreateUserIdentity(POSTER);
    spaceService.addMember(spaceService.getSpaceById(space.getSpaceId()), POSTER);

    actAs(POSTER);
    String marker = UUID.randomUUID().toString();
    ActivityModel first = activityMcpTool.createActivity(space.getSpaceId(), "Page 1 " + marker, null);
    pause();
    ActivityModel second = activityMcpTool.createActivity(null, "Page 2 " + marker, null);
    pause();
    ActivityModel third = activityMcpTool.createActivity(space.getSpaceId(), "Page 3 " + marker, null);
    pause();
    ActivityModel fourth = activityMcpTool.createActivity(null, "Page 4 " + marker, null);

    actAs(USERNAME);
    List<Long> all = ids(activityMcpTool.getUserActivityStream(POSTER, 0, 100));
    List<Long> page = ids(activityMcpTool.getUserActivityStream(POSTER, 1, 2));

    assertEquals(List.of(fourth.id(), third.id(), second.id(), first.id()),
                 all.stream().filter(id -> List.of(first.id(), second.id(), third.id(), fourth.id()).contains(id)).toList());
    assertEquals(all.subList(1, 3), page);
  }

  /**
   * The current user's own posts keep their stream, spaces included.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void theCurrentUsersOwnPostsIncludeTheirSpacePosts() throws Exception {
    SpaceModel space = createSpace();
    ActivityModel spacePost = activityMcpTool.createActivity(space.getSpaceId(), "Own space post " + UUID.randomUUID(), null);

    List<ActivitySummaryModel> posts = activityMcpTool.getUserActivityStream(USERNAME, 0, 10);

    assertTrue(ids(posts).contains(spacePost.id()));
  }

  /**
   * Creates a space managed by the current user.
   *
   * @return the space
   * @throws Exception when the space can't be created
   */
  private SpaceModel createSpace() throws Exception {
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);
    assertFalse(templates.isEmpty(), "At least one space template is required");
    return spaceMcpTool.createSpace(templates.get(0).getId(),
                                    "mcp-user-stream-" + UUID.randomUUID(),
                                    "User stream test space",
                                    Visibility.LISTED,
                                    Registration.OPEN,
                                    List.of(),
                                    null);
  }

  /**
   * Makes a user the current user.
   *
   * @param username the user
   */
  private void actAs(String username) {
    ConversationState.setCurrent(new ConversationState(new Identity(username)));
  }

  /**
   * Waits long enough for the next post to get a later time than the last.
   *
   * @throws InterruptedException when interrupted
   */
  private void pause() throws InterruptedException {
    Thread.sleep(20); // NOSONAR
  }

  /**
   * @param posts a list of posts
   * @return their ids, in order
   */
  private List<Long> ids(List<ActivitySummaryModel> posts) {
    return posts.stream().map(ActivitySummaryModel::id).toList();
  }

}

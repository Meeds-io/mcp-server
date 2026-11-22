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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.activity.model.ExoSocialActivityImpl;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.model.ActivitySummaryModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

/**
 * The posts {@code get_user_activity_stream} returns for a user: only the ones
 * that user posted, on the profile stream and, for another user, in the spaces
 * both users share, and nothing from a space the current user can't view
 * (EXO-94931).
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

  @Autowired
  private ActivityManager      activityManager;

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
   * A newer post by someone else that the user commented on sits in the user's
   * profile stream, but it is not one of their posts: it is left out, the
   * user's own older post stays. Mutant: the poster filter on the profile
   * stream removed.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void aPostTheUserOnlyCommentedOnIsLeftOut() throws Exception {
    org.exoplatform.social.core.identity.model.Identity posterIdentity = identityManager.getOrCreateUserIdentity(POSTER);
    identityManager.getOrCreateUserIdentity(OUTSIDER);
    actAs(POSTER);
    ActivityModel ownPost = activityMcpTool.createActivity(null, "Own post " + UUID.randomUUID(), null);
    pause();
    actAs(OUTSIDER);
    ActivityModel otherPost = activityMcpTool.createActivity(null, "Someone else's post " + UUID.randomUUID(), null);
    pause();
    ExoSocialActivity comment = new ExoSocialActivityImpl();
    comment.setTitle("A comment " + UUID.randomUUID());
    comment.setUserId(posterIdentity.getId());
    activityManager.saveComment(activityManager.getActivity(String.valueOf(otherPost.id())), comment);

    actAs(USERNAME);
    List<Long> posts = ids(activityMcpTool.getUserActivityStream(POSTER, 0, 10));

    assertTrue(posts.contains(ownPost.id()), "the post of " + POSTER + " is missing");
    assertFalse(posts.contains(otherPost.id()), "a post " + POSTER + " only commented on is listed");
  }

  /**
   * An offset past the deepest page is refused with a message naming what to
   * use instead, and the deepest page itself is read. Mutant: the offset cap
   * removed.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void anOffsetPastTheDeepestPageIsRefused() throws Exception {
    identityManager.getOrCreateUserIdentity(POSTER);

    assertEquals(List.of(), activityMcpTool.getUserActivityStream(POSTER, 1000, 10));
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> activityMcpTool.getUserActivityStream(POSTER, Integer.MAX_VALUE, 10));
    assertTrue(refusal.getMessage().contains("search_activities"), refusal.getMessage());
  }

  /**
   * A user whose only post is in a space the current user was merely invited
   * to has no post the current user can see: the list is empty. Social counts
   * an invitation as a common space, so this pins that its posts stay out.
   * The viewer is mary, a plain user of the test organization, checked to be
   * neither a member of the space nor a space super manager.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void aPostInASpaceTheCurrentUserIsOnlyInvitedToIsLeftOut() throws Exception {
    InvitedOnly fixture = invitedOnlySpaceWithAPostOfTheOutsider(POSTER);
    assertFalse(spaceService.isMember(fixture.space(), POSTER), POSTER + " must be invited only");
    assertFalse(spaceService.isSuperManager(POSTER), POSTER + " must not be a space super manager for this case");

    actAs(POSTER);
    List<ActivitySummaryModel> posts = activityMcpTool.getUserActivityStream(OUTSIDER, 0, 100);

    assertFalse(ids(posts).contains(fixture.postId()), "the post of a space " + POSTER + " is only invited to is listed");
  }

  /**
   * A space super manager may view every space, so an invited-only space's
   * post reaches them: the known limit of the invited-only case. john is a
   * manager of /platform/administrators in the test organization.
   *
   * @throws Exception when a fixture can't be created
   */
  @Test
  void aSpaceSuperManagerSeesThePostOfASpaceTheyAreOnlyInvitedTo() throws Exception {
    InvitedOnly fixture = invitedOnlySpaceWithAPostOfTheOutsider(USERNAME);
    assertFalse(spaceService.isMember(fixture.space(), USERNAME), USERNAME + " must be invited only");
    assertTrue(spaceService.isSuperManager(USERNAME), USERNAME + " must be a space super manager for this case");

    actAs(USERNAME);
    List<ActivitySummaryModel> posts = activityMcpTool.getUserActivityStream(OUTSIDER, 0, 100);

    assertTrue(ids(posts).contains(fixture.postId()), "the super manager does not get the space's post");
  }

  /**
   * Has the outsider create a space, post in it and invite a user, who
   * neither joins nor is a member.
   *
   * @param invitee the invited user
   * @return the space and the id of the outsider's post in it
   * @throws Exception when a fixture can't be created
   */
  private InvitedOnly invitedOnlySpaceWithAPostOfTheOutsider(String invitee) throws Exception {
    identityManager.getOrCreateUserIdentity(OUTSIDER);
    identityManager.getOrCreateUserIdentity(invitee);
    actAs(OUTSIDER);
    SpaceModel spaceModel = createSpace();
    ActivityModel post = activityMcpTool.createActivity(spaceModel.getSpaceId(), "Members only " + UUID.randomUUID(), null);
    spaceService.addInvitedUser(spaceService.getSpaceById(spaceModel.getSpaceId()), invitee);
    return new InvitedOnly(spaceService.getSpaceById(spaceModel.getSpaceId()), post.id());
  }

  /**
   * A space the invitee is only invited to, and the outsider's post in it.
   *
   * @param space  the space
   * @param postId the post's id
   */
  private record InvitedOnly(Space space, long postId) {
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

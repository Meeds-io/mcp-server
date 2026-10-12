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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.ActivityCommentModel;
import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

/**
 * Pins the permission of {@link ActivityMcpTool#updateComment} against Social's
 * real {@link ActivityManager}: the comment's edit permission, the one Social's
 * comment update REST endpoint checks, never its view permission. Every refused
 * caller here can read the comment, so a tool that only asked whether the
 * comment is viewable would let them rewrite it.
 */
class ActivityMcpToolCommentEditAclTest extends IntegrationTestBase {

  private static final String  MEMBER = "mary";

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
   * A space member who can read another member's comment is refused when
   * asking to change it, and the stored comment keeps its text.
   *
   * @throws Exception when the fixture can't be built
   */
  @Test
  void memberWhoCanViewACommentCannotEditAnotherMembersComment() throws Exception {
    SpaceModel space = createSpaceWithMember();
    ActivityModel activity = activityMcpTool.createActivity(space.getSpaceId(), "A post in the space", null);
    ActivityCommentModel comment = activityMcpTool.createActivityComment(activity.id(), "Words written by John", null);

    actAs(MEMBER);
    assertNotNull(activityMcpTool.getActivityComment(comment.id()), "the member must be able to read the comment");

    IllegalAccessException refusal = assertThrows(IllegalAccessException.class,
                                                  () -> activityMcpTool.updateComment(comment.id(), "Rewritten by Mary"));

    assertEquals("Comment with id '%s' can't be edited by current user".formatted(comment.id()), refusal.getMessage());
    assertEquals("Words written by John", storedTitle(comment.id()));
  }

  /**
   * The space manager who did not write a comment is refused too, as in the
   * stream: Social lets only a comment's poster edit it.
   *
   * @throws Exception when the fixture can't be built
   */
  @Test
  void spaceManagerWhoIsNotTheAuthorCannotEditAMembersComment() throws Exception {
    SpaceModel space = createSpaceWithMember();
    ActivityModel activity = activityMcpTool.createActivity(space.getSpaceId(), "The manager's post", null);

    actAs(MEMBER);
    ActivityCommentModel comment = activityMcpTool.createActivityComment(activity.id(), "Words written by Mary", null);

    actAs(USERNAME);
    assertTrue(spaceService.isManager(spaceService.getSpaceById(space.getSpaceId()), USERNAME));
    assertNotNull(activityMcpTool.getActivityComment(comment.id()), "the manager must be able to read the comment");

    assertThrows(IllegalAccessException.class, () -> activityMcpTool.updateComment(comment.id(), "Rewritten by John"));
    assertEquals("Words written by Mary", storedTitle(comment.id()));
  }

  /**
   * The comment's author changes it through the tool, whether or not they
   * manage the space.
   *
   * @throws Exception when the fixture can't be built
   */
  @Test
  void authorEditsTheirOwnComment() throws Exception {
    SpaceModel space = createSpaceWithMember();
    ActivityModel activity = activityMcpTool.createActivity(space.getSpaceId(), "A post to comment on", null);

    actAs(MEMBER);
    ActivityCommentModel comment = activityMcpTool.createActivityComment(activity.id(), "First version", null);

    ActivityCommentModel updated = activityMcpTool.updateComment(comment.id(), "Second version");

    assertEquals(comment.id(), updated.id());
    assertEquals("Second version", storedTitle(comment.id()));
  }

  /**
   * Creates an open space managed by the default test user, with
   * {@link #MEMBER} as a plain member.
   *
   * @return the created space
   * @throws Exception when the space can't be created
   */
  private SpaceModel createSpaceWithMember() throws Exception {
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);
    assertFalse(templates.isEmpty(), "At least one space template is required");
    SpaceModel spaceModel = spaceMcpTool.createSpace(templates.get(0).getId(),
                                                     "mcp-comment-acl-" + UUID.randomUUID(),
                                                     "Comment edit ACL space",
                                                     Visibility.LISTED,
                                                     Registration.OPEN,
                                                     List.of(),
                                                     null);
    identityManager.getOrCreateUserIdentity(MEMBER);
    Space space = spaceService.getSpaceById(spaceModel.getSpaceId());
    spaceService.addMember(space, MEMBER);
    assertFalse(spaceService.isManager(spaceService.getSpaceById(spaceModel.getSpaceId()), MEMBER));
    return spaceModel;
  }

  /**
   * Makes the following tool calls run as the given user.
   *
   * @param username the user the tool acts as
   */
  private void actAs(String username) {
    ConversationState.setCurrent(new ConversationState(new Identity(username)));
  }

  /**
   * Reads the comment's text from Social's storage, bypassing the tool.
   *
   * @param commentId the comment identifier, without its prefix
   * @return the stored title of the comment
   */
  private String storedTitle(long commentId) {
    return activityManager.getActivity("comment" + commentId).getTitle();
  }

}

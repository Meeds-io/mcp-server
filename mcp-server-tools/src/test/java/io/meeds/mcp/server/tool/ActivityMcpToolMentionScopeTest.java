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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.social.core.utils.MentionUtils;

import io.meeds.mcp.server.model.ActingIdentity;
import io.meeds.mcp.server.util.McpToolUtils;

/**
 * Pins the mention check of the activity tools whose text names people: in a
 * call limited to a space, a post, an image post, a post edit, a comment or an
 * image comment that mentions a user who is neither a member nor a super
 * manager of that space is refused before anything is written or staged, and
 * so is one that mentions someone when the call's identity can't be read. A
 * call that isn't limited, or a text that mentions no one, goes on to the
 * tool's own steps, which these tests stop at a missing object (a 404) to show
 * the check let it through. Plain mocks, no Spring/Kernel context: the real
 * {@code MentionUtils} parses the texts, its user lookup given a mocked
 * {@code IdentityManager} that knows the enabled users of these tests, and
 * reset after each test so the container tests of this module look theirs up
 * again.
 */
class ActivityMcpToolMentionScopeTest {

  private static final long       SCOPE_SPACE_ID    = 12L;

  private static final long       POST_SPACE_ID     = 99L;

  private static final long       ACTIVITY_ID       = 34L;

  private static final long       MISSING_ACTIVITY  = 35L;

  private static final String     MEMBER_MENTION    = "Please check @mary and @paul";

  private static final String     OUTSIDER_MENTION  = "Please check @mary and @john";

  private static final String     NO_MENTION        = "Please check";

  private final ActivityManager   activityManager   = mock(ActivityManager.class);

  private final IdentityManager   identityManager   = mock(IdentityManager.class);

  private final SpaceService      spaceService      = mock(SpaceService.class);

  private final Space             scopeSpace        = mock(Space.class);

  private final ExoSocialActivity activity          = mock(ExoSocialActivity.class);

  private Long                    scopeSpaceId;

  private boolean                 scopeUnreadable;

  private final IdentityManager   mentionIdentityManager = mock(IdentityManager.class);

  private final ActivityMcpTool   tool              = new ActivityMcpTool() {
                                                      @Override
                                                      protected Long getScopeSpaceId() {
                                                        if (scopeUnreadable) {
                                                          throw new IllegalStateException("No acting identity");
                                                        }
                                                        return scopeSpaceId;
                                                      }
                                                    };

  @BeforeEach
  void setUp() {
    for (String login : new String[] { "mary", "paul", "john", "sam" }) {
      Identity identity = mock(Identity.class);
      when(identity.isEnable()).thenReturn(true);
      when(mentionIdentityManager.getOrCreateUserIdentity(login)).thenReturn(identity);
    }
    ReflectionTestUtils.setField(MentionUtils.class, "identityManager", mentionIdentityManager);
    ReflectionTestUtils.setField(tool, "activityManager", activityManager);
    ReflectionTestUtils.setField(tool, "identityManager", identityManager);
    ReflectionTestUtils.setField(tool, "spaceService", spaceService);
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("root")));
    when(spaceService.getSpaceById(SCOPE_SPACE_ID)).thenReturn(scopeSpace);
    when(spaceService.isMember(scopeSpace, "mary")).thenReturn(true);
    when(spaceService.isMember(scopeSpace, "paul")).thenReturn(true);
    when(spaceService.isMember(scopeSpace, "john")).thenReturn(false);
    when(activityManager.getActivity(String.valueOf(ACTIVITY_ID))).thenReturn(activity);
    when(activityManager.isActivityEditable(any(), any())).thenReturn(true);
    when(activity.getPublicationStartTime()).thenReturn(null);
  }

  @AfterEach
  void tearDown() {
    ReflectionTestUtils.setField(MentionUtils.class, "identityManager", null);
    ConversationState.setCurrent(null);
  }

  /**
   * A post, an image post and a post edit that mention a user outside the
   * space the call is limited to are refused, naming that user, and nothing
   * is written; the image is not even staged (the tool has no upload service
   * here, so staging would fail with another exception).
   * Mutants: the check removed from any of the three, or applied to the first
   * mention only.
   */
  @Test
  void postsOfACallLimitedToASpaceMentioningAnOutsiderAreRefused() {
    scopeSpaceId = SCOPE_SPACE_ID;

    IllegalAccessException refused = assertThrows(IllegalAccessException.class,
                                                  () -> tool.createActivity(POST_SPACE_ID, OUTSIDER_MENTION, null));
    assertTrue(refused.getMessage().contains("'john'"), refused.getMessage());
    assertThrows(IllegalAccessException.class,
                 () -> tool.createActivityWithImage(POST_SPACE_ID, OUTSIDER_MENTION, null, "aW1hZ2U=", null, null, null));
    assertThrows(IllegalAccessException.class, () -> tool.updateActivity(ACTIVITY_ID, OUTSIDER_MENTION, null));

    verify(activityManager, never()).saveActivityNoReturn(any(), any());
    verify(activityManager, never()).updateActivity(any(), anyBoolean());
  }

  /**
   * A comment and an image comment that mention a user outside the space the
   * call is limited to are refused, and nothing is read or saved.
   * Mutants: the check removed from either.
   */
  @Test
  void commentsOfACallLimitedToASpaceMentioningAnOutsiderAreRefused() {
    scopeSpaceId = SCOPE_SPACE_ID;

    assertThrows(IllegalAccessException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, OUTSIDER_MENTION, null));
    assertThrows(IllegalAccessException.class,
                 () -> tool.createActivityCommentWithImage(MISSING_ACTIVITY, OUTSIDER_MENTION, null, "aW1hZ2U=", null, null, null));

    verify(activityManager, never()).getActivity(String.valueOf(MISSING_ACTIVITY));
    verify(activityManager, never()).saveComment(any(), any());
  }

  /**
   * In a call limited to a space, a text that mentions members only, or no
   * one, goes on to the tool's own steps (here the missing post space or
   * activity), the space being loaded once per call for the text that
   * mentions someone (two members here) and not at all for the one that
   * mentions no one; a post edit with mentions of members updates the
   * activity.
   * Mutants: the membership test inverted, the space loaded per mention or
   * for a text without mentions.
   *
   * @throws Exception when the edit fails after the update, which is not the
   *           case under test
   */
  @Test
  void textsOfACallLimitedToASpaceMentioningMembersOnlyGoOn() throws Exception {
    scopeSpaceId = SCOPE_SPACE_ID;

    assertThrows(ObjectNotFoundException.class, () -> tool.createActivity(POST_SPACE_ID, MEMBER_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivity(POST_SPACE_ID, NO_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, MEMBER_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, NO_MENTION, null));
    updateQuietly(MEMBER_MENTION);

    verify(spaceService, times(3)).getSpaceById(SCOPE_SPACE_ID);
    verify(activityManager).updateActivity(activity, true);
  }

  /**
   * In a call limited to a space, a post edit is checked on every user its new
   * text mentions, like a new post: a mention of an outsider is refused even
   * when the stored text already carried it, and nothing is updated.
   * Mutant: the edit checked only on the mentions its text adds.
   */
  @Test
  void aPostEditOfACallLimitedToASpaceIsCheckedOnEveryMention() {
    scopeSpaceId = SCOPE_SPACE_ID;
    when(activity.getTitle()).thenReturn("<p>Hello @john</p>");

    assertThrows(IllegalAccessException.class, () -> tool.updateActivity(ACTIVITY_ID, "Hello @john typo fixed", null));

    verify(activityManager, never()).updateActivity(any(), anyBoolean());
  }

  /**
   * A super manager of the space the call is limited to counts as one of its
   * people, as for the platform's own space rules: a post, an edit or a
   * comment that mentions one goes on, though they aren't a member.
   * Mutant: the super-manager clause removed.
   *
   * @throws Exception when the edit fails after the update, which is not the
   *           case under test
   */
  @Test
  void aSuperManagerOfTheSpaceMayBeMentioned() throws Exception {
    scopeSpaceId = SCOPE_SPACE_ID;
    when(spaceService.isMember(scopeSpace, "sam")).thenReturn(false);
    when(spaceService.isSuperManager(scopeSpace, "sam")).thenReturn(true);

    assertThrows(ObjectNotFoundException.class, () -> tool.createActivity(POST_SPACE_ID, "Please check @sam", null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, "Please check @sam", null));
    updateQuietly("Please check @sam");

    verify(activityManager).updateActivity(activity, true);
  }

  /**
   * A role mention such as {@code @member:<space identity id>} is not a user:
   * the real parser keeps only enabled users, so a post edit naming the
   * space's members by role goes on in a call limited to that space.
   * Mutant: the role mention taken for a user (an outsider).
   *
   * @throws Exception when the edit fails after the update, which is not the
   *           case under test
   */
  @Test
  void aRoleMentionIsNotAUser() throws Exception {
    scopeSpaceId = SCOPE_SPACE_ID;

    updateQuietly("Please check @member:12");

    verify(activityManager).updateActivity(activity, true);
    verify(spaceService, never()).getSpaceById(SCOPE_SPACE_ID);
  }

  /**
   * A call limited to a space that no longer exists can't mention anyone,
   * members of no space: the text is refused.
   * Mutant: a missing space taken as no restriction.
   */
  @Test
  void aCallLimitedToAMissingSpaceMentionsNoOne() {
    scopeSpaceId = SCOPE_SPACE_ID + 1;

    assertThrows(IllegalAccessException.class, () -> tool.createActivity(POST_SPACE_ID, MEMBER_MENTION, null));
    assertThrows(IllegalAccessException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, MEMBER_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivity(POST_SPACE_ID, NO_MENTION, null));
  }

  /**
   * A call that isn't limited to a space may mention anyone, as before: it
   * goes on to the tool's own steps and never reads a membership.
   * Mutant: the check run on an unlimited call.
   *
   * @throws Exception when the edit fails after the update, which is not the
   *           case under test
   */
  @Test
  void anUnlimitedCallMayMentionAnyone() throws Exception {
    scopeSpaceId = null;

    assertThrows(ObjectNotFoundException.class, () -> tool.createActivity(POST_SPACE_ID, OUTSIDER_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, OUTSIDER_MENTION, null));
    updateQuietly(OUTSIDER_MENTION);

    verify(spaceService, never()).isMember(any(Space.class), any(String.class));
    verify(activityManager).updateActivity(activity, true);
  }

  /**
   * When the call's identity can't be read, a text that mentions someone is
   * refused with its own message, and one that mentions no one goes on.
   * Mutants: an unreadable identity taken as unlimited, or refusing a text
   * without mentions.
   */
  @Test
  void anUnreadableCallIdentityRefusesATextThatMentionsSomeone() {
    scopeUnreadable = true;

    IllegalAccessException refused = assertThrows(IllegalAccessException.class,
                                                  () -> tool.createActivity(POST_SPACE_ID, MEMBER_MENTION, null));
    assertTrue(refused.getMessage().contains("can't be read"), refused.getMessage());
    assertThrows(IllegalAccessException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, MEMBER_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivity(POST_SPACE_ID, NO_MENTION, null));
    assertThrows(ObjectNotFoundException.class, () -> tool.createActivityComment(MISSING_ACTIVITY, NO_MENTION, null));
  }

  /**
   * The space a call is limited to is the one its acting identity carries,
   * none for an identity without a scope or no identity, and an identity
   * that can't be read fails the read rather than passing for an unlimited
   * call. Runs the real {@code getScopeSpaceId}, which the other tests stub.
   * Mutants: the scope not read, or the read failure swallowed.
   */
  @Test
  void theScopeSpaceIsTheOneTheActingIdentityCarries() {
    ActivityMcpTool realTool = new ActivityMcpTool();
    ActingIdentity limited = mock(ActingIdentity.class);
    when(limited.spaceScope()).thenReturn(7L);
    ActingIdentity unlimited = mock(ActingIdentity.class);
    when(unlimited.spaceScope()).thenReturn(null);
    try (MockedStatic<McpToolUtils> mocked = mockStatic(McpToolUtils.class)) {
      mocked.when(McpToolUtils::getCurrentActingIdentity).thenReturn(limited);
      assertEquals(Long.valueOf(7L), realTool.getScopeSpaceId());
      mocked.when(McpToolUtils::getCurrentActingIdentity).thenReturn(unlimited);
      assertNull(realTool.getScopeSpaceId());
      mocked.when(McpToolUtils::getCurrentActingIdentity).thenReturn(null);
      assertNull(realTool.getScopeSpaceId());
      mocked.when(McpToolUtils::getCurrentActingIdentity).thenThrow(new IllegalStateException("refused"));
      assertThrows(IllegalStateException.class, realTool::getScopeSpaceId);
    }
  }

  /**
   * Edits the test activity's text, ignoring a failure after the update
   * (building the returned model needs collaborators these tests don't
   * stub).
   *
   * @param text the new text
   */
  private void updateQuietly(String text) {
    try {
      tool.updateActivity(ACTIVITY_ID, text, null);
    } catch (Exception e) { // NOSONAR - only the update before it is asserted
      // the model of the updated activity can't be built from the mocks
    }
  }

}

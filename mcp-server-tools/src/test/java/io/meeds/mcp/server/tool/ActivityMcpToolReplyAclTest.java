/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Pins the ACL path of {@link ActivityMcpTool#createActivityComment} now that
 * it takes an optional parent comment, in isolation (plain mocks, no
 * Spring/Kernel context): the decision is still the activity's own view check,
 * taken before the parent is even looked up, and a refused call never reaches
 * {@link ActivityManager#saveComment}. The integration test in
 * {@link ActivityMcpToolTest} covers the threading itself against the real
 * storage.
 */
class ActivityMcpToolReplyAclTest {

  private static final long      ACTIVITY_ID       = 12L;

  private static final long      OTHER_ACTIVITY_ID = 13L;

  private static final long      COMMENT_ID        = 45L;

  private final ActivityManager  activityManager   = mock(ActivityManager.class);

  private final IdentityManager  identityManager   = mock(IdentityManager.class);

  private final ActivityMcpTool  activityMcpTool   = new ActivityMcpTool();

  private final ExoSocialActivity activity         = mock(ExoSocialActivity.class);

  private final ExoSocialActivity parentComment    = mock(ExoSocialActivity.class);

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(activityMcpTool, "activityManager", activityManager);
    ReflectionTestUtils.setField(activityMcpTool, "identityManager", identityManager);
    ConversationState.setCurrent(new ConversationState(new Identity("john")));
    when(activityManager.getActivity(String.valueOf(ACTIVITY_ID))).thenReturn(activity);
    when(activityManager.getActivity("comment" + COMMENT_ID)).thenReturn(parentComment);
    when(parentComment.isComment()).thenReturn(true);
    when(parentComment.getId()).thenReturn("comment" + COMMENT_ID);
    when(parentComment.getParentId()).thenReturn(String.valueOf(ACTIVITY_ID));
  }

  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  @Test
  void replyOnAnActivityTheUserCannotViewIsRefusedBeforeTheParentIsRead() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(false);

    assertThrows(IllegalAccessException.class,
                 () -> activityMcpTool.createActivityComment(ACTIVITY_ID, "a reply", COMMENT_ID));

    // the parent is not even resolved: no information about it leaks through
    // the message, and nothing is saved
    verify(activityManager, never()).getActivity("comment" + COMMENT_ID);
    verify(activityManager, never()).saveComment(any(), any());
  }

  @Test
  void topLevelCommentOnAnActivityTheUserCannotViewIsStillRefused() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(false);

    assertThrows(IllegalAccessException.class,
                 () -> activityMcpTool.createActivityComment(ACTIVITY_ID, "a comment", null));

    verify(activityManager, never()).saveComment(any(), any());
  }

  @Test
  void replyToAParentOfAnotherActivityIsRefusedAndNothingIsSaved() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(true);
    when(parentComment.getParentId()).thenReturn(String.valueOf(OTHER_ACTIVITY_ID));

    assertThrows(IllegalArgumentException.class,
                 () -> activityMcpTool.createActivityComment(ACTIVITY_ID, "a misplaced reply", COMMENT_ID));

    // refused, not silently posted at top level
    verify(activityManager, never()).saveComment(any(), any());
  }

  @Test
  void replyToAnUnknownParentIsRefusedAndNothingIsSaved() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(true);
    when(activityManager.getActivity("comment" + COMMENT_ID)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class,
                 () -> activityMcpTool.createActivityComment(ACTIVITY_ID, "a reply to nothing", COMMENT_ID));

    verify(activityManager, never()).saveComment(any(), any());
    verify(identityManager, never()).getOrCreateUserIdentity(anyString());
  }

}

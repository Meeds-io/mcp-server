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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Pins the refusal of {@link ActivityMcpTool#createActivityComment} when the
 * parent comment belongs to another activity: the message must not reveal the
 * identifier of that other activity, which the caller may not be allowed to see.
 */
class ActivityMcpToolParentMismatchTest {

  private static final long       ACTIVITY_ID       = 12L;

  private static final long       OTHER_ACTIVITY_ID = 987654L;

  private static final long       COMMENT_ID        = 45L;

  private final ActivityManager   activityManager   = mock(ActivityManager.class);

  private final ActivityMcpTool   activityMcpTool   = new ActivityMcpTool();

  private final ExoSocialActivity activity          = mock(ExoSocialActivity.class);

  private final ExoSocialActivity parentComment     = mock(ExoSocialActivity.class);

  /**
   * Wires the tool with a viewable activity and a parent comment of another activity.
   */
  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(activityMcpTool, "activityManager", activityManager);
    ReflectionTestUtils.setField(activityMcpTool, "identityManager", mock(IdentityManager.class));
    ConversationState.setCurrent(new ConversationState(new Identity("john")));
    when(activityManager.getActivity(String.valueOf(ACTIVITY_ID))).thenReturn(activity);
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(true);
    when(activityManager.getActivity("comment" + COMMENT_ID)).thenReturn(parentComment);
    when(parentComment.isComment()).thenReturn(true);
    when(parentComment.getParentId()).thenReturn(String.valueOf(OTHER_ACTIVITY_ID));
  }

  /**
   * Clears the current user.
   */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * The refusal stays an {@link IllegalArgumentException} that tells the model
   * what is wrong, without the foreign activity identifier.
   */
  @Test
  void parentOfAnotherActivityIsRefusedWithoutRevealingTheOtherActivityId() {
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> activityMcpTool.createActivityComment(ACTIVITY_ID,
                                                                                                "a misplaced reply",
                                                                                                COMMENT_ID));

    assertFalse(refusal.getMessage().contains(String.valueOf(OTHER_ACTIVITY_ID)), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("does not belong to this activity"), refusal.getMessage());
    verify(activityManager, never()).saveComment(any(), any());
  }

}

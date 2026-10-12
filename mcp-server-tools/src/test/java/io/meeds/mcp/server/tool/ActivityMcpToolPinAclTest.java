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

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Pins the ACL order of {@link ActivityMcpTool#pinActivity} and
 * {@link ActivityMcpTool#unpinActivity}, in isolation (plain mocks): the view
 * check comes first, then the pin permission, and a refused call never reaches
 * the pin or unpin operation.
 */
class ActivityMcpToolPinAclTest {

  private static final long       ACTIVITY_ID     = 12L;

  private final ActivityManager   activityManager = mock(ActivityManager.class);

  private final IdentityManager   identityManager = mock(IdentityManager.class);

  private final ActivityMcpTool   activityMcpTool = new ActivityMcpTool();

  private final ExoSocialActivity activity        = mock(ExoSocialActivity.class);

  /**
   * Wires the tool with mock managers and a current user.
   */
  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(activityMcpTool, "activityManager", activityManager);
    ReflectionTestUtils.setField(activityMcpTool, "identityManager", identityManager);
    ConversationState.setCurrent(new ConversationState(new Identity("john")));
    when(activityManager.getActivity(String.valueOf(ACTIVITY_ID))).thenReturn(activity);
    when(activity.getId()).thenReturn(String.valueOf(ACTIVITY_ID));
    when(identityManager.getOrCreateUserIdentity(anyString())).thenReturn(new org.exoplatform.social.core.identity.model.Identity("7"));
  }

  /**
   * Clears the current user.
   */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * An activity the user cannot view is refused before the pin permission is read.
   */
  @Test
  void pinOnAnActivityTheUserCannotViewIsRefusedBeforeThePinPermission() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(false);
    when(activityManager.canPinActivity(any(), any())).thenReturn(true);

    assertThrows(IllegalAccessException.class, () -> activityMcpTool.pinActivity(ACTIVITY_ID));

    verify(activityManager, never()).canPinActivity(any(), any());
    verify(activityManager, never()).pinActivity(anyString(), anyString());
  }

  /**
   * A viewable activity the user may not pin is still refused.
   */
  @Test
  void pinOnAViewableActivityTheUserCannotPinIsRefused() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(true);
    when(activityManager.canPinActivity(any(), any())).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> activityMcpTool.pinActivity(ACTIVITY_ID));

    verify(activityManager, never()).pinActivity(anyString(), anyString());
  }

  /**
   * An activity the user cannot view is refused before the pin permission is read.
   */
  @Test
  void unpinOnAnActivityTheUserCannotViewIsRefusedBeforeThePinPermission() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(false);
    when(activityManager.canPinActivity(any(), any())).thenReturn(true);

    assertThrows(IllegalAccessException.class, () -> activityMcpTool.unpinActivity(ACTIVITY_ID));

    verify(activityManager, never()).canPinActivity(any(), any());
    verify(activityManager, never()).unpinActivity(anyString());
  }

  /**
   * A viewable activity the user may not unpin is still refused.
   */
  @Test
  void unpinOnAViewableActivityTheUserCannotPinIsRefused() {
    when(activityManager.isActivityViewable(eq(activity), any())).thenReturn(true);
    when(activityManager.canPinActivity(any(), any())).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> activityMcpTool.unpinActivity(ACTIVITY_ID));

    verify(activityManager, never()).unpinActivity(anyString());
  }

}

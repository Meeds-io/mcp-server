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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;

import io.meeds.mcp.server.tool.plugin.ActivityReadScopeResolver;

/**
 * Pins which space an activity belongs to for the read scope decision.
 */
@ExtendWith(MockitoExtension.class)
class ActivityReadScopeResolverTest {

  @Mock
  private ActivityManager           activityManager;

  @InjectMocks
  private ActivityReadScopeResolver resolver;

  /**
   * An activity of a space's stream, a shared one included, belongs to that
   * space. Mutant: the space id not read.
   */
  @Test
  void spaceActivityBelongsToItsSpace() {
    ExoSocialActivity activity = activity(false, "12");
    when(activityManager.getActivity("7")).thenReturn(activity);

    assertThat(resolver.getName()).isEqualTo("activity");
    assertThat(resolver.spaceOf("7")).isEqualTo(12L);
  }

  /**
   * A comment, an activity of a user's stream, a missing activity and a
   * failing lookup belong to no space. Mutants: the comment check removed;
   * the exception let through.
   */
  @Test
  void otherActivitiesBelongToNoSpace() {
    ExoSocialActivity comment = activity(true, "12");
    ExoSocialActivity userStream = activity(false, null);
    when(activityManager.getActivity("1")).thenReturn(comment);
    when(activityManager.getActivity("2")).thenReturn(userStream);
    when(activityManager.getActivity("3")).thenReturn(null);
    when(activityManager.getActivity("4")).thenThrow(new IllegalStateException("database down"));

    assertThat(resolver.spaceOf("1")).isNull();
    assertThat(resolver.spaceOf("2")).isNull();
    assertThat(resolver.spaceOf("3")).isNull();
    assertThat(resolver.spaceOf("4")).isNull();
  }

  /**
   * @param comment whether the activity is a comment
   * @param spaceId the id of the space whose stream holds it, null for none
   * @return a mocked activity
   */
  private static ExoSocialActivity activity(boolean comment, String spaceId) {
    ExoSocialActivity activity = mock(ExoSocialActivity.class);
    when(activity.isComment()).thenReturn(comment);
    org.mockito.Mockito.lenient().when(activity.getSpaceId()).thenReturn(spaceId);
    return activity;
  }

}

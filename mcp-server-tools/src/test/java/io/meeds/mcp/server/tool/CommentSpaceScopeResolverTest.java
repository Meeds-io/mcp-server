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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;

import io.meeds.mcp.server.tool.plugin.CommentSpaceScopeResolver;

/**
 * Pins which space a comment belongs to for the space scope decision.
 */
@ExtendWith(MockitoExtension.class)
class CommentSpaceScopeResolverTest {

  @Mock
  private ActivityManager           activityManager;

  @InjectMocks
  private CommentSpaceScopeResolver resolver;

  /**
   * A comment, and a reply to a comment, belong to the space of the activity
   * they comment on: social gives a reply the top activity as its parent.
   * Mutants: the parent not read; the comment's own space id read instead.
   */
  @Test
  void commentBelongsToTheSpaceOfItsActivity() {
    ExoSocialActivity post = activity(false, "12", null);
    ExoSocialActivity comment = activity(true, null, "7");
    ExoSocialActivity reply = activity(true, "99", "7");
    when(activityManager.getActivity("7")).thenReturn(post);
    when(activityManager.getActivity("21")).thenReturn(comment);
    when(activityManager.getActivity("22")).thenReturn(reply);

    assertThat(resolver.getName()).isEqualTo("comment");
    assertThat(resolver.spaceOf("21")).isEqualTo(12L);
    assertThat(resolver.spaceOf("22")).isEqualTo(12L);
  }

  /**
   * An activity that isn't a comment belongs to no space here, so a comment
   * tool never reaches a post by its id, even one that names a parent.
   * Mutant: the comment check removed.
   */
  @Test
  void activityIsNotAComment() {
    ExoSocialActivity post = activity(false, "12", null);
    ExoSocialActivity withParent = activity(false, "12", "7");
    when(activityManager.getActivity("7")).thenReturn(post);
    when(activityManager.getActivity("8")).thenReturn(withParent);

    assertThat(resolver.spaceOf("7")).isNull();
    assertThat(resolver.spaceOf("8")).isNull();
  }

  /**
   * A comment on a user stream's activity, a comment whose parent is missing,
   * is itself a comment or isn't named, a missing comment and a failing
   * lookup belong to no space. Mutants: the parent's comment or space check
   * removed; the exception let through.
   */
  @Test
  void otherCommentsBelongToNoSpace() {
    ExoSocialActivity userStreamPost = activity(false, null, null);
    ExoSocialActivity commentAsParent = activity(true, "12", "8");
    ExoSocialActivity onUserStream = activity(true, null, "1");
    ExoSocialActivity parentMissing = activity(true, null, "404");
    ExoSocialActivity parentIsComment = activity(true, null, "2");
    ExoSocialActivity noParent = activity(true, null, null);
    when(activityManager.getActivity("1")).thenReturn(userStreamPost);
    when(activityManager.getActivity("2")).thenReturn(commentAsParent);
    when(activityManager.getActivity("11")).thenReturn(onUserStream);
    when(activityManager.getActivity("12")).thenReturn(parentMissing);
    when(activityManager.getActivity("13")).thenReturn(parentIsComment);
    when(activityManager.getActivity("14")).thenReturn(noParent);
    when(activityManager.getActivity("15")).thenThrow(new IllegalStateException("database down"));

    assertThat(resolver.spaceOf("11")).isNull();
    assertThat(resolver.spaceOf("12")).isNull();
    assertThat(resolver.spaceOf("13")).isNull();
    assertThat(resolver.spaceOf("14")).isNull();
    assertThat(resolver.spaceOf("15")).isNull();
    assertThat(resolver.spaceOf("16")).isNull();
    verify(activityManager, never()).getActivity((String) null);
  }

  /**
   * @param comment whether the activity is a comment
   * @param spaceId the id of the space whose stream holds it, null for none
   * @param parentId the activity a comment comments on, null for none
   * @return a mocked activity
   */
  private static ExoSocialActivity activity(boolean comment, String spaceId, String parentId) {
    ExoSocialActivity activity = mock(ExoSocialActivity.class);
    lenient().when(activity.isComment()).thenReturn(comment);
    lenient().when(activity.getSpaceId()).thenReturn(spaceId);
    lenient().when(activity.getParentId()).thenReturn(parentId);
    return activity;
  }

}

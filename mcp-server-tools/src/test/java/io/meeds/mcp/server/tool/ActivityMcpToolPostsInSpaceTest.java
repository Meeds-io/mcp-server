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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.services.security.Identity;
import org.exoplatform.social.common.RealtimeListAccess;
import org.exoplatform.social.core.activity.ActivityFilter;
import org.exoplatform.social.core.activity.ActivityStreamType;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.model.Space;

/**
 * How {@link ActivityMcpTool#getPostsInSpace} reads another user's posts in
 * one space for {@code get_user_activity_stream} (EXO-94931).
 */
class ActivityMcpToolPostsInSpaceTest {

  private final ActivityManager                                activityManager = mock(ActivityManager.class);

  private final IdentityManager                                identityManager = mock(IdentityManager.class);

  @SuppressWarnings("unchecked")
  private final RealtimeListAccess<ExoSocialActivity>          listAccess      = mock(RealtimeListAccess.class);

  private final ActivityMcpTool                                activityMcpTool = new ActivityMcpTool();

  private final Identity                                       viewer          = new Identity("john");

  private final org.exoplatform.social.core.identity.model.Identity viewerIdentity  =
                                                                               new org.exoplatform.social.core.identity.model.Identity("1");

  private final org.exoplatform.social.core.identity.model.Identity ownerIdentity   =
                                                                               new org.exoplatform.social.core.identity.model.Identity("2");

  private final Space                                          space           = new Space();

  /**
   * Wires the tool with mock managers and a space whose identity is 3.
   *
   * @throws Exception never, the mock declares it
   */
  @BeforeEach
  void setUp() throws Exception {
    ReflectionTestUtils.setField(activityMcpTool, "activityManager", activityManager);
    ReflectionTestUtils.setField(activityMcpTool, "identityManager", identityManager);
    space.setId("30");
    space.setPrettyName("marketing");
    when(identityManager.getOrCreateSpaceIdentity("marketing")).thenReturn(new org.exoplatform.social.core.identity.model.Identity("3"));
    when(activityManager.getActivitiesByFilterWithListAccess(eq(viewerIdentity), any())).thenReturn(listAccess);
    when(activityManager.isActivityViewable(any(), eq(viewer))).thenReturn(true);
  }

  /**
   * The space's list is asked for the posts of the user whose stream is read,
   * in that space. Mutants: the poster left out of the filter; the space left
   * out.
   *
   * @throws Exception never, the mock declares it
   */
  @Test
  void theSpaceListIsFilteredOnTheSpaceAndThePoster() throws Exception {
    when(listAccess.loadAsList(0, 1)).thenReturn(List.of());

    activityMcpTool.getPostsInSpace(viewer, viewerIdentity, ownerIdentity, space, 1);

    ArgumentCaptor<ActivityFilter> filter = ArgumentCaptor.forClass(ActivityFilter.class);
    verify(activityManager).getActivitiesByFilterWithListAccess(eq(viewerIdentity), filter.capture());
    assertEquals(ActivityStreamType.ANY_SPACE_ACTIVITY, filter.getValue().getStreamType());
    assertEquals(3L, filter.getValue().getSpaceIdentityId());
    assertEquals(2L, filter.getValue().getUserId());
  }

  /**
   * A space lists its pinned posts first: when the newest post is behind a
   * pinned one, the load grows by the pinned count, so the newest post is
   * read. Mutant: no second load (only the pinned post comes back).
   */
  @Test
  void aPinnedPostDoesNotHideTheNewestOne() {
    ExoSocialActivity pinned = activity("10", true);
    ExoSocialActivity newest = activity("11", false);
    when(listAccess.loadAsList(0, 1)).thenReturn(List.of(pinned));
    when(listAccess.loadAsList(0, 2)).thenReturn(List.of(pinned, newest));

    List<ExoSocialActivity> posts = activityMcpTool.getPostsInSpace(viewer, viewerIdentity, ownerIdentity, space, 1);

    assertEquals(List.of(pinned, newest), posts);
  }

  /**
   * A post the current user can't view is left out, the others kept. Mutant:
   * the viewable check removed.
   */
  @Test
  void aPostTheCurrentUserCannotViewIsLeftOut() {
    ExoSocialActivity hidden = activity("12", false);
    ExoSocialActivity shown = activity("13", false);
    when(listAccess.loadAsList(0, 5)).thenReturn(List.of(hidden, shown));
    when(activityManager.isActivityViewable(hidden, viewer)).thenReturn(false);

    List<ExoSocialActivity> posts = activityMcpTool.getPostsInSpace(viewer, viewerIdentity, ownerIdentity, space, 5);

    assertEquals(List.of(shown), posts);
  }

  /**
   * A space social refuses to the current user is skipped, not an error.
   * Mutant: the refusal rethrown.
   *
   * @throws Exception never, the mock declares it
   */
  @Test
  void aSpaceTheCurrentUserCannotViewIsSkipped() throws Exception {
    when(activityManager.getActivitiesByFilterWithListAccess(eq(viewerIdentity), any())).thenThrow(new IllegalAccessException("refused"));

    List<ExoSocialActivity> posts = activityMcpTool.getPostsInSpace(viewer, viewerIdentity, ownerIdentity, space, 5);

    assertEquals(List.of(), posts);
  }

  /**
   * @param id     the activity id
   * @param pinned whether the activity is pinned
   * @return a mock activity
   */
  private ExoSocialActivity activity(String id, boolean pinned) {
    ExoSocialActivity activity = mock(ExoSocialActivity.class);
    when(activity.getId()).thenReturn(id);
    when(activity.isPinned()).thenReturn(pinned);
    return activity;
  }

}

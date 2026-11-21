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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
 * How {@link ActivityMcpTool#getPostsInSpace} reads a user's posts in one
 * space, and {@link ActivityMcpTool#getPostsOnProfileStream} on their profile
 * stream, for {@code get_user_activity_stream} (EXO-94931).
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
   * A post the current user can't view does not take the place of a newer
   * one they can: the load grows past it. Mutant: no reload after the
   * viewable filter.
   */
  @Test
  void aHiddenPostDoesNotHideTheNewestViewableOne() {
    ExoSocialActivity hidden = activity("14", false);
    ExoSocialActivity shown = activity("15", false);
    when(listAccess.loadAsList(0, 1)).thenReturn(List.of(hidden));
    when(listAccess.loadAsList(0, 2)).thenReturn(List.of(hidden, shown));
    when(activityManager.isActivityViewable(hidden, viewer)).thenReturn(false);

    List<ExoSocialActivity> posts = activityMcpTool.getPostsInSpace(viewer, viewerIdentity, ownerIdentity, space, 1);

    assertEquals(List.of(shown), posts);
  }

  /**
   * A space whose posts are all hidden from the current user stops growing
   * its load once 500 more posts than needed were read. Mutant: the cap
   * removed (the load never ends).
   */
  @Test
  void aSpaceOfHiddenPostsStopsAtTheLoadCap() {
    List<Integer> sizes = new ArrayList<>();
    when(listAccess.loadAsList(eq(0), anyInt())).thenAnswer(invocation -> {
      int size = invocation.getArgument(1);
      sizes.add(size);
      assertTrue(sizes.size() < 50, "the load does not stop");
      List<ExoSocialActivity> page = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        page.add(activity("h" + i, false));
      }
      return page;
    });
    when(activityManager.isActivityViewable(any(), eq(viewer))).thenReturn(false);

    List<ExoSocialActivity> posts = activityMcpTool.getPostsInSpace(viewer, viewerIdentity, ownerIdentity, space, 1);

    assertEquals(List.of(), posts);
    assertEquals(501, sizes.get(sizes.size() - 1));
  }

  /**
   * On the profile stream, a post by someone else the user reacted to does not
   * take the place of the user's own newest post: it is left out and the load
   * grows past it. Mutants: the poster filter removed; no reload.
   */
  @Test
  void aProfilePostBySomeoneElseDoesNotHideTheUsersNewestOne() {
    ExoSocialActivity reacted = activity("20", false);
    ExoSocialActivity own = activity("21", false);
    when(reacted.getPosterId()).thenReturn("9");
    when(own.getPosterId()).thenReturn("2");
    when(activityManager.getActivity("20")).thenReturn(reacted);
    when(activityManager.getActivity("21")).thenReturn(own);
    when(listAccess.loadIdsAsList(0, 1)).thenReturn(List.of("20"));
    when(listAccess.loadIdsAsList(0, 2)).thenReturn(List.of("20", "21"));

    List<ExoSocialActivity> posts = activityMcpTool.getPostsOnProfileStream(listAccess, ownerIdentity, 1);

    assertEquals(List.of(own), posts);
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

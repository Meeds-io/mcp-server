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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.common.RealtimeListAccess;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.util.ActivityToolUtils;

/**
 * Pins {@link ActivityMcpTool#getUserActivityStream}: only the entries the
 * caller can view are returned, from the page loaded at the caller's
 * offset and limit.
 */
class ActivityMcpToolUserStreamAclTest {

  private final ActivityManager           activityManager = mock(ActivityManager.class);

  private final IdentityManager           identityManager = mock(IdentityManager.class);

  private final ActivityMcpTool           activityMcpTool = new ActivityMcpTool();

  private final List<String>              streamIds       = new ArrayList<>();

  private final Set<String>               hiddenIds       = new HashSet<>();

  private MockedStatic<ActivityToolUtils> toolUtils;

  /**
   * Wires the tool with mock managers, a current user and a stream.
   */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ReflectionTestUtils.setField(activityMcpTool, "activityManager", activityManager);
    ReflectionTestUtils.setField(activityMcpTool, "identityManager", identityManager);
    ConversationState.setCurrent(new ConversationState(new Identity("john")));
    when(identityManager.getOrCreateUserIdentity(anyString())).thenAnswer(inv -> new org.exoplatform.social.core.identity.model.Identity(inv.<String>getArgument(0)));
    RealtimeListAccess<ExoSocialActivity> listAccess = mock(RealtimeListAccess.class);
    when(listAccess.loadIdsAsList(anyInt(), anyInt())).thenAnswer(inv -> {
      int from = inv.getArgument(0);
      int to = Math.min(streamIds.size(), from + inv.<Integer>getArgument(1));
      return from >= to ? new ArrayList<String>() : new ArrayList<>(streamIds.subList(from, to));
    });
    when(activityManager.getActivitiesWithListAccess(any(), any())).thenReturn(listAccess);
    when(activityManager.getActivity(anyString())).thenAnswer(inv -> {
      ExoSocialActivity activity = mock(ExoSocialActivity.class);
      when(activity.getId()).thenReturn(inv.<String>getArgument(0));
      return activity;
    });
    // Only the caller ("john") sees the entries not hidden from him; any other
    // identity, such as the stream owner's, sees nothing
    when(activityManager.isActivityViewable(any(), any())).thenAnswer(inv -> {
      Identity asker = inv.getArgument(1);
      return asker != null && "john".equals(asker.getUserId())
          && !hiddenIds.contains(inv.<ExoSocialActivity>getArgument(0).getId());
    });
    toolUtils = Mockito.mockStatic(ActivityToolUtils.class);
    toolUtils.when(() -> ActivityToolUtils.toActivityModel(any(),
                                                           any(),
                                                           any(),
                                                           any(),
                                                           any(),
                                                           any(),
                                                           any(),
                                                           any(),
                                                           any(),
                                                           anyString(),
                                                           any(),
                                                           any()))
             .thenAnswer(inv -> {
               ActivityModel model = mock(ActivityModel.class);
               when(model.id()).thenReturn(Long.parseLong(inv.<String>getArgument(9)));
               return model;
             });
  }

  /**
   * Closes the static mock and clears the current user.
   */
  @AfterEach
  void tearDown() {
    toolUtils.close();
    ConversationState.setCurrent(null);
  }

  private void stream(int count) {
    for (int i = 1; i <= count; i++) {
      streamIds.add(String.valueOf(i));
    }
  }

  private List<Long> ids(List<ActivityModel> models) {
    return models.stream().map(ActivityModel::id).toList();
  }

  /**
   * An entry the viewer cannot see is absent, a visible one is present.
   */
  @Test
  void entryTheViewerCannotSeeIsAbsentAndVisibleOneIsPresent() throws Exception { // NOSONAR
    stream(2);
    hiddenIds.add("2");

    List<Long> result = ids(activityMcpTool.getUserActivityStream("mary", 0, 10));

    assertEquals(List.of(1L), result);
  }

  /**
   * Consecutive pages follow the raw stream positions: no entry is repeated
   * and only visible entries are returned.
   */
  @Test
  void consecutivePagesNeverRepeatAnEntryAndHoldOnlyVisibleOnes() throws Exception { // NOSONAR
    stream(12);
    hiddenIds.addAll(List.of("1", "3", "4", "6", "8"));

    List<Long> first = ids(activityMcpTool.getUserActivityStream("mary", 0, 5));
    List<Long> second = ids(activityMcpTool.getUserActivityStream("mary", 5, 5));

    assertEquals(List.of(2L, 5L), first);
    assertEquals(List.of(7L, 9L, 10L), second);
    Set<Long> all = new HashSet<>(first);
    all.addAll(second);
    assertEquals(first.size() + second.size(), all.size());
  }

  /**
   * The visibility check is made for the caller, never for the stream owner.
   */
  @Test
  void visibilityIsCheckedForTheCallerAndNotTheStreamOwner() throws Exception { // NOSONAR
    stream(2);

    List<Long> result = ids(activityMcpTool.getUserActivityStream("mary", 0, 10));

    assertEquals(List.of(1L, 2L), result);
  }

}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.ActivityCommentModel;
import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class ActivityMcpToolTest extends IntegrationTestBase {

  @Autowired
  private ActivityMcpTool      activityMcpTool;

  @Autowired
  private SpaceMcpTool         spaceMcpTool;

  @Autowired
  private SpaceTemplateMcpTool spaceTemplateMcpTool;

  @Test
  void createGetUpdateAndDeleteActivity() throws Exception { // NOSONAR
    ActivityModel created = activityMcpTool.createActivity(null, "Initial **activity** content");

    assertNotNull(created);
    assertTrue(created.id() > 0);

    ActivityModel loaded = activityMcpTool.getActivity(created.id());

    assertNotNull(loaded);
    assertEquals(created.id(), loaded.id());

    ActivityModel updated = activityMcpTool.updateActivity(created.id(), "Updated activity content");

    assertNotNull(updated);
    assertEquals(created.id(), updated.id());
    assertTrue(updated.content().contains("Updated"));

    activityMcpTool.deleteActivity(created.id());

    assertThrows(Exception.class, () -> activityMcpTool.getActivity(created.id()));
  }

  @Test
  void createActivityInSpace() throws Exception {
    SpaceModel space = createTestSpace();

    ActivityModel activity = activityMcpTool.createActivity(space.getSpaceId(), "Space activity content");

    assertNotNull(activity);
    assertEquals(space.getSpaceId(), activity.space().getSpaceId());
  }

  @Test
  void createActivityWithBlankContent() {
    assertThrows(IllegalArgumentException.class,
                 () -> activityMcpTool.createActivity(null, " "));
  }

  @Test
  void getActivityWhenNotFound() {
    assertThrows(Exception.class, () -> activityMcpTool.getActivity(-1L));
  }

  @Test
  void commentsLifecycle() throws Exception { // NOSONAR
    ActivityModel activity = activityMcpTool.createActivity(null, "Activity with comments");

    ActivityCommentModel comment = activityMcpTool.createActivityComment(activity.id(), "First comment");

    assertNotNull(comment);
    assertEquals(activity.id(), comment.activityId());

    ActivityCommentModel loaded = activityMcpTool.getActivityComment(comment.id());

    assertNotNull(loaded);
    assertEquals(comment.id(), loaded.id());

    ActivityCommentModel updated = activityMcpTool.updateComment(comment.id(), "Updated comment");

    assertNotNull(updated);
    assertEquals(comment.id(), updated.id());
    assertTrue(updated.content().contains("Updated"));

    List<ActivityCommentModel> comments = activityMcpTool.getActivityComments(activity.id(), 0, 10);

    assertNotNull(comments);
    assertTrue(comments.stream().anyMatch(c -> c.id() == comment.id()));
  }

  @Test
  void likeUnlikeAndListLikers() throws Exception { // NOSONAR
    ActivityModel activity = activityMcpTool.createActivity(null, "Activity to like");

    activityMcpTool.likeTheActivity(activity.id());

    List<UserModel> likers = activityMcpTool.getActivityLikers(activity.id(), 0, 10);

    assertNotNull(likers);
    assertFalse(likers.isEmpty());

    activityMcpTool.unlikeTheActivity(activity.id());
  }

  @Test
  void pinAndUnpinActivity() throws Exception { // NOSONAR
    ActivityModel activity = activityMcpTool.createActivity(null, "Activity to pin");

    activityMcpTool.pinActivity(activity.id());
    activityMcpTool.unpinActivity(activity.id());
  }

  @Test
  void searchActivitiesFallsBackToGetActivitiesWhenNoFilter() throws Exception { // NOSONAR
    activityMcpTool.createActivity(null, "Search fallback activity " + UUID.randomUUID());

    List<ActivityModel> activities = activityMcpTool.searchActivities(null, null, false, 0, 10);

    assertNotNull(activities);
  }

  @Test
  void searchActivitiesByQuery() throws Exception { // NOSONAR
    String marker = "mcp-search-" + UUID.randomUUID();
    activityMcpTool.createActivity(null, marker);

    List<ActivityModel> activities = activityMcpTool.searchActivities(marker, null, false, 0, 10);

    assertNotNull(activities);
  }

  @Test
  void getUserActivityStream() throws Exception { // NOSONAR
    List<ActivityModel> activities = activityMcpTool.getUserActivityStream(USERNAME, 0, 10);

    assertNotNull(activities);
  }

  @Test
  void getActivities() throws Exception { // NOSONAR
    activityMcpTool.createActivity(null, "Activity list item");

    List<ActivityModel> activities = activityMcpTool.getActivities(null, 0, 10);

    assertNotNull(activities);
  }

  @Test
  void getActivitiesSinceDays() throws Exception { // NOSONAR
    activityMcpTool.createActivity(null, "Recent activity");

    List<ActivityModel> activities = activityMcpTool.getActivitiesSinceDays(null, 1L);

    assertNotNull(activities);
  }

  @Test
  void shareActivityToSpace() throws Exception {
    ActivityModel activity = activityMcpTool.createActivity(null, "Activity to share");
    SpaceModel space = createTestSpace();

    ActivityModel shared = activityMcpTool.shareActivityToSpace(activity.id(), space.getSpaceId());

    assertNotNull(shared);
  }

  private SpaceModel createTestSpace() throws Exception { // NOSONAR
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);
    assertFalse(templates.isEmpty(), "At least one space template is required");

    return spaceMcpTool.createSpace(templates.get(0).getId(),
                                    "mcp-activity-space-" + UUID.randomUUID(),
                                    "Activity test space",
                                    Visibility.LISTED,
                                    Registration.OPEN,
                                    List.of(),
                                    null);
  }

}

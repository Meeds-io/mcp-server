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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.commons.exception.ObjectNotFoundException;

import io.meeds.mcp.server.tool.model.NotificationCountModel;
import io.meeds.mcp.server.tool.model.NotificationModel;
import io.meeds.mcp.server.tool.model.NotificationSettingsModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class NotificationMcpToolTest extends IntegrationTestBase {

  private static final String    WEB_CHANNEL = "WEB_CHANNEL";

  @Autowired
  private NotificationMcpTool    notificationMcpTool;

  @Test
  void listNotifications() {
    List<NotificationModel> notifications = notificationMcpTool.listNotifications(true, null, 0, 10);

    assertNotNull(notifications);
  }

  @Test
  void getUnreadNotificationsCount() {
    NotificationCountModel count = notificationMcpTool.getUnreadNotificationsCount();

    assertNotNull(count);
    assertTrue(count.unreadCount() >= 0);
  }

  @Test
  void markNotificationReadWhenNotFound() {
    assertThrows(ObjectNotFoundException.class,
                 () -> notificationMcpTool.markNotificationRead("999999999"));
  }

  @Test
  void hideNotificationWhenNotFound() {
    assertThrows(ObjectNotFoundException.class,
                 () -> notificationMcpTool.hideNotification("999999999"));
  }

  @Test
  void markAllNotificationsRead() {
    // No notifications for the test user: should be a no-op without error
    notificationMcpTool.markAllNotificationsRead(null);
    notificationMcpTool.markAllNotificationsRead("SomePlugin");
  }

  @Test
  void getNotificationSettings() {
    NotificationSettingsModel settings = notificationMcpTool.getNotificationSettings();

    assertNotNull(settings);
    assertNotNull(settings.channels());
    assertNotNull(settings.plugins());
  }

  @Test
  void setNotificationChannelEnabled() {
    notificationMcpTool.setNotificationChannelEnabled(WEB_CHANNEL, false);
    notificationMcpTool.setNotificationChannelEnabled(WEB_CHANNEL, true);
  }

  @Test
  void setNotificationPluginEnabled() {
    notificationMcpTool.setNotificationPluginEnabled("SomePlugin", WEB_CHANNEL, true);
    notificationMcpTool.setNotificationPluginEnabled("SomePlugin", WEB_CHANNEL, false);
  }

  @Test
  void muteAndUnmuteSpace() {
    long spaceId = 987654L;

    notificationMcpTool.muteSpaceNotifications(spaceId, true);
    NotificationSettingsModel muted = notificationMcpTool.getNotificationSettings();
    assertNotNull(muted.mutedSpaceIds());
    assertTrue(muted.mutedSpaceIds().contains(spaceId));

    notificationMcpTool.muteSpaceNotifications(spaceId, false);
    NotificationSettingsModel unmuted = notificationMcpTool.getNotificationSettings();
    assertTrue(unmuted.mutedSpaceIds() == null || !unmuted.mutedSpaceIds().contains(spaceId));
  }

}

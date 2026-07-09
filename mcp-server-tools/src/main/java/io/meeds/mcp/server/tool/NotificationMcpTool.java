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

import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.notification.channel.AbstractChannel;
import org.exoplatform.commons.api.notification.channel.ChannelManager;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.PluginInfo;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.model.UserSetting;
import org.exoplatform.commons.api.notification.model.WebNotificationFilter;
import org.exoplatform.commons.api.notification.service.WebNotificationService;
import org.exoplatform.commons.api.notification.service.setting.PluginSettingService;
import org.exoplatform.commons.api.notification.service.setting.UserSettingService;
import org.exoplatform.commons.exception.ObjectNotFoundException;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.NotificationCountModel;
import io.meeds.mcp.server.tool.model.NotificationModel;
import io.meeds.mcp.server.tool.model.NotificationSettingsModel;
import io.meeds.mcp.server.tool.model.NotificationSettingsModel.NotificationChannelModel;
import io.meeds.mcp.server.tool.model.NotificationSettingsModel.NotificationPluginModel;
import io.meeds.mcp.server.tool.util.NotificationToolUtils;

@Service
public class NotificationMcpTool implements McpToolPlugin {

  private static final String    NOTIFICATION_NOT_FOUND    =
                                                       "Notification with id '%s' doesn't exist. Use list_notifications to retrieve the current user's notifications.";

  private static final String    NOTIFICATION_ACCESS_DENIED =
                                                        "Notification with id '%s' doesn't belong to the current user";

  @Autowired
  private WebNotificationService webNotificationService;

  @Autowired
  private UserSettingService     userSettingService;

  @Autowired
  private PluginSettingService   pluginSettingService;

  @Autowired
  private ChannelManager         channelManager;

  public List<NotificationModel> listNotifications(Boolean onlyUnread,
                                                   String plugin,
                                                   Integer offset,
                                                   Integer limit) {
    String currentUsername = getCurrentUserName();
    List<PluginKey> pluginKeys = StringUtils.isBlank(plugin) ? Collections.emptyList()
                                                             : List.of(PluginKey.key(plugin));
    WebNotificationFilter filter = new WebNotificationFilter(currentUsername, pluginKeys, true);
    if (Boolean.TRUE.equals(onlyUnread)) {
      filter.setIsRead(false);
    }
    List<NotificationInfo> notificationInfos = webNotificationService.getNotificationInfos(filter,
                                                                                           getInteger(offset, DEFAULT_OFFSET),
                                                                                           getInteger(limit, DEFAULT_LIMIT));
    return notificationInfos.stream()
                            .map(notification -> NotificationToolUtils.toNotificationModel(webNotificationService, notification))
                            .toList();
  }

  public NotificationCountModel getUnreadNotificationsCount() {
    String currentUsername = getCurrentUserName();
    int badge = webNotificationService.getNumberOnBadge(currentUsername);
    return new NotificationCountModel(badge, webNotificationService.countUnreadByPlugin(currentUsername));
  }

  public void markNotificationRead(String notificationId) throws ObjectNotFoundException, IllegalAccessException {
    checkOwnership(notificationId);
    webNotificationService.markRead(notificationId);
  }

  public void markAllNotificationsRead(String plugin) {
    String currentUsername = getCurrentUserName();
    if (StringUtils.isBlank(plugin)) {
      webNotificationService.markAllRead(currentUsername);
      webNotificationService.resetNumberOnBadge(currentUsername);
    } else {
      List<String> plugins = List.of(plugin);
      webNotificationService.markAllRead(plugins, currentUsername);
      webNotificationService.resetNumberOnBadge(plugins, currentUsername);
    }
  }

  public void hideNotification(String notificationId) throws ObjectNotFoundException, IllegalAccessException {
    checkOwnership(notificationId);
    webNotificationService.hidePopover(notificationId);
  }

  public NotificationSettingsModel getNotificationSettings() {
    String currentUsername = getCurrentUserName();
    UserSetting userSetting = userSettingService.get(currentUsername);
    List<String> channelIds = channelManager.getChannels()
                                             .stream()
                                             .map(AbstractChannel::getId)
                                             .toList();
    List<NotificationChannelModel> channels = channelIds.stream()
                                                        .map(channelId -> new NotificationChannelModel(channelId,
                                                                                                       userSetting.isChannelGloballyActive(channelId)))
                                                        .toList();
    List<NotificationPluginModel> plugins = pluginSettingService.getAllPlugins()
                                                                .stream()
                                                                .map(PluginInfo::getType)
                                                                .distinct()
                                                                .map(pluginId -> new NotificationPluginModel(pluginId,
                                                                                                             channelIds.stream()
                                                                                                                       .filter(channelId -> userSetting.isActive(channelId,
                                                                                                                                                                 pluginId))
                                                                                                                       .toList()))
                                                                .toList();
    return new NotificationSettingsModel(channels, plugins, userSetting.getMutedSpaces());
  }

  public void setNotificationChannelEnabled(String channelId, boolean enabled) {
    UserSetting userSetting = userSettingService.get(getCurrentUserName());
    if (enabled) {
      userSetting.setChannelActive(channelId);
    } else {
      userSetting.removeChannelActive(channelId);
    }
    userSettingService.save(userSetting);
  }

  public void setNotificationPluginEnabled(String pluginId, String channelId, boolean enabled) {
    UserSetting userSetting = userSettingService.get(getCurrentUserName());
    if (enabled) {
      userSetting.addChannelPlugin(channelId, pluginId);
    } else {
      userSetting.removeChannelPlugin(channelId, pluginId);
    }
    userSettingService.save(userSetting);
  }

  public void muteSpaceNotifications(long spaceId, boolean muted) {
    UserSetting userSetting = userSettingService.get(getCurrentUserName());
    if (muted) {
      userSetting.addMutedSpace(spaceId);
    } else {
      userSetting.removeMutedSpace(spaceId);
    }
    userSettingService.save(userSetting);
  }

  private void checkOwnership(String notificationId) throws ObjectNotFoundException, IllegalAccessException {
    NotificationInfo notification = webNotificationService.getNotificationInfo(notificationId);
    if (notification == null) {
      throw new ObjectNotFoundException(NOTIFICATION_NOT_FOUND.formatted(notificationId));
    } else if (!getCurrentUserName().equals(notification.getTo())) {
      throw new IllegalAccessException(NOTIFICATION_ACCESS_DENIED.formatted(notificationId));
    }
  }

}

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
package io.meeds.mcp.server.tool.util;

import static io.meeds.mcp.server.util.McpToolUtils.formatDate;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.service.WebNotificationService;

import io.meeds.mcp.server.tool.model.NotificationModel;

public class NotificationToolUtils {

  /**
   * Owner parameter keys used, in order, to resolve the sender username of a
   * notification when {@link NotificationInfo#getFrom()} is blank. Mirrors the
   * social WebNotificationRestEntityBuilder resolution.
   */
  private static final String[] SENDER_OWNER_PARAMETERS = {
    "poster", "username", "profile", "sender", "modifier", "MODIFIER_ID", "SENDER_ID", "request_from", "creator", "creatorId"
  };

  private NotificationToolUtils() {
    // Utils class
  }

  @SuppressWarnings("removal") // getNotificationMessage is the only message-rendering API; still used by social's own REST layer
  public static NotificationModel toNotificationModel(WebNotificationService webNotificationService,
                                                      NotificationInfo notification) {
    return new NotificationModel(notification.getId(),
                                 notification.getTitle(),
                                 notification.getKey() == null ? null : notification.getKey().getId(),
                                 webNotificationService.getNotificationMessage(notification, true),
                                 getSender(notification),
                                 notification.isRead(),
                                 formatDate(notification.getLastModifiedDate()),
                                 notification.getOwnerParameter());
  }

  private static String getSender(NotificationInfo notification) {
    if (StringUtils.isNotBlank(notification.getFrom())) {
      return notification.getFrom();
    }
    Map<String, String> ownerParameters = notification.getOwnerParameter();
    if (ownerParameters == null) {
      return null;
    }
    for (String parameter : SENDER_OWNER_PARAMETERS) {
      String username = ownerParameters.get(parameter);
      if (StringUtils.isNotBlank(username)) {
        return username;
      }
    }
    return null;
  }

}

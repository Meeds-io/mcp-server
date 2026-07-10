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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.service.WebNotificationService;

import io.meeds.mcp.server.tool.model.NotificationModel;

class NotificationToolUtilsTest {

  private final WebNotificationService webNotificationService = mock(WebNotificationService.class);

  @Test
  void toNotificationModelMapsAllFields() {
    when(webNotificationService.getNotificationMessage(any(), anyBoolean())).thenReturn("<p>Hello</p>");

    NotificationInfo notification = NotificationInfo.instance()
                                                    .setId("42")
                                                    .setTitle("A title")
                                                    .key(new PluginKey("RelationshipReceivedRequestPlugin"))
                                                    .setFrom("mary")
                                                    .setRead(true)
                                                    .setLastModifiedDate(1_700_000_000_000L);

    NotificationModel model = NotificationToolUtils.toNotificationModel(webNotificationService, notification);

    assertNotNull(model);
    assertEquals("42", model.id());
    assertEquals("A title", model.title());
    assertEquals("RelationshipReceivedRequestPlugin", model.plugin());
    assertEquals("<p>Hello</p>", model.message());
    assertEquals("mary", model.from());
    assertTrue(model.read());
    assertNotNull(model.createdDate());
  }

  @Test
  void toNotificationModelWithNullKeyHasNullPlugin() {
    when(webNotificationService.getNotificationMessage(any(), anyBoolean())).thenReturn("message");

    NotificationInfo notification = NotificationInfo.instance()
                                                    .setId("1")
                                                    .setFrom("john")
                                                    .setRead(false);

    NotificationModel model = NotificationToolUtils.toNotificationModel(webNotificationService, notification);

    assertNull(model.plugin());
    assertFalse(model.read());
    assertEquals("john", model.from());
  }

  @Test
  void senderFallsBackToOwnerParameterWhenFromIsBlank() {
    when(webNotificationService.getNotificationMessage(any(), anyBoolean())).thenReturn("message");

    Map<String, String> ownerParameters = new HashMap<>();
    ownerParameters.put("poster", "alice");

    NotificationInfo notification = NotificationInfo.instance()
                                                    .setId("2")
                                                    .setFrom("")
                                                    .setOwnerParameter(ownerParameters);

    NotificationModel model = NotificationToolUtils.toNotificationModel(webNotificationService, notification);

    assertEquals("alice", model.from());
  }

  @Test
  void senderResolvesLaterOwnerParameterKeyWhenEarlierKeysAreMissing() {
    when(webNotificationService.getNotificationMessage(any(), anyBoolean())).thenReturn("message");

    Map<String, String> ownerParameters = new HashMap<>();
    // none of the earlier keys (poster/username/...) are present, only "creator"
    ownerParameters.put("creator", "bob");

    NotificationInfo notification = NotificationInfo.instance()
                                                    .setId("3")
                                                    .setFrom(null)
                                                    .setOwnerParameter(ownerParameters);

    NotificationModel model = NotificationToolUtils.toNotificationModel(webNotificationService, notification);

    assertEquals("bob", model.from());
  }

  @Test
  void senderIsNullWhenNoOwnerParameterMatches() {
    when(webNotificationService.getNotificationMessage(any(), anyBoolean())).thenReturn("message");

    Map<String, String> ownerParameters = new HashMap<>();
    ownerParameters.put("unrelated", "value");

    NotificationInfo notification = NotificationInfo.instance()
                                                    .setId("4")
                                                    .setFrom(" ")
                                                    .setOwnerParameter(ownerParameters);

    NotificationModel model = NotificationToolUtils.toNotificationModel(webNotificationService, notification);

    assertNull(model.from());
  }

  @Test
  void senderIsNullWhenFromBlankAndNoOwnerParameters() {
    when(webNotificationService.getNotificationMessage(any(), anyBoolean())).thenReturn("message");

    NotificationInfo notification = NotificationInfo.instance()
                                                    .setId("5")
                                                    .setFrom("");

    NotificationModel model = NotificationToolUtils.toNotificationModel(webNotificationService, notification);

    assertNull(model.from());
  }

}

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.manager.RelationshipManager;

import io.meeds.mcp.server.tool.model.OnlineStatusModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class UserMcpToolTest extends IntegrationTestBase {

  @Autowired
  private IdentityManager     identityManager;

  @Autowired
  private RelationshipManager relationshipManager;

  @Autowired
  private UserMcpTool         userMcpTool;

  @Test
  void getMyUserInformation() {
    UserModel user = userMcpTool.getMyUserInformation();

    assertNotNull(user);
    assertNotNull(user.getUsername());
    assertEquals(user.getUsername(), user.getLoginId());
  }

  @Test
  void getUserByUsername() {
    UserModel user = userMcpTool.getUserByUsername(USERNAME);

    assertNotNull(user);
    assertEquals(USERNAME, user.getUsername());
  }

  @Test
  void searchUsers() {
    String user = "mary";
    String userIdentityId = identityManager.getOrCreateUserIdentity(user).getId();

    when(profileSearchConnector.search(any(),
                                       any(),
                                       any(),
                                       anyLong(),
                                       anyLong())).thenReturn(List.of(userIdentityId));

    List<UserModel> users = userMcpTool.searchUsers(user, 0, 10);

    assertNotNull(users);
    assertFalse(users.isEmpty());
    assertTrue(users.stream().anyMatch(u -> user.equals(u.getUsername())));
  }

  @Test
  void getUsersCount() {
    assertTrue(userMcpTool.getUsersCount() > 0);
  }

  // --- connections ---------------------------------------------------------

  @Test
  void listConnectionsAndRequestsAreEmptyForFreshUser() {
    assertNotNull(userMcpTool.listConnectionRequests(0, 10));
    assertNotNull(userMcpTool.listSentConnectionRequests(0, 10));
    assertNotNull(userMcpTool.listMyConnections(null, 0, 10));
    assertNotNull(userMcpTool.listConnectionSuggestions(10));
  }

  @Test
  void sendConnectionRequestThenStatusIsOutgoing() {
    identityManager.getOrCreateUserIdentity("demo");

    userMcpTool.sendConnectionRequest("demo");

    assertEquals("OUTGOING", userMcpTool.getConnectionStatus("demo"));
  }

  @Test
  void sendConnectionRequestToSelfIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> userMcpTool.sendConnectionRequest(USERNAME));
  }

  @Test
  void acceptIncomingConnectionRequest() {
    Identity mary = identityManager.getOrCreateUserIdentity("mary");
    Identity john = identityManager.getOrCreateUserIdentity(USERNAME);
    // mary invites john
    relationshipManager.inviteToConnect(mary, john);

    List<UserModel> requests = userMcpTool.listConnectionRequests(0, 10);
    assertTrue(requests.stream().anyMatch(u -> "mary".equals(u.getUsername())));
    assertEquals("INCOMING", userMcpTool.getConnectionStatus("mary"));

    userMcpTool.acceptConnectionRequest("mary");

    assertEquals("CONFIRMED", userMcpTool.getConnectionStatus("mary"));
  }

  @Test
  void acceptWithoutPendingRequestFails() {
    identityManager.getOrCreateUserIdentity("james");
    assertThrows(IllegalStateException.class, () -> userMcpTool.acceptConnectionRequest("james"));
  }

  // --- profile -------------------------------------------------------------

  @Test
  void updateMyProfileWithoutFieldsFails() {
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.updateMyProfile(null, null, null, null, null, null, null));
  }

  @Test
  void updateMyProfileSetsFields() {
    UserModel updated = userMcpTool.updateMyProfile("Hello, I test MCP tools", "Platform Engineer", "Meeds",
                                                    null, null, null, null);

    assertNotNull(updated);
    assertEquals("Platform Engineer", updated.getPosition());
    assertEquals("Meeds", updated.getCompany());
  }

  // --- online status -------------------------------------------------------

  @Test
  void getUserOnlineStatusReflectsService() {
    when(userStateService.isOnline(USERNAME)).thenReturn(true);
    when(userStateService.getUserState(USERNAME)).thenReturn(new UserStateModel(USERNAME, 1000000000000L, "available"));

    OnlineStatusModel status = userMcpTool.getUserOnlineStatus(USERNAME);

    assertNotNull(status);
    assertTrue(status.online());
    assertEquals("available", status.status());
  }

  @Test
  void listOnlineUsersMapsService() {
    when(userStateService.online()).thenReturn(List.of(new UserStateModel("mary", 1000000000000L, "available")));

    List<OnlineStatusModel> online = userMcpTool.listOnlineUsers(0, 10);

    assertNotNull(online);
    assertTrue(online.stream().anyMatch(o -> "mary".equals(o.username())));
  }

}

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

import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class UserMcpToolTest extends IntegrationTestBase {

  @Autowired
  private IdentityManager identityManager;

  @Autowired
  private UserMcpTool     userMcpTool;

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

}

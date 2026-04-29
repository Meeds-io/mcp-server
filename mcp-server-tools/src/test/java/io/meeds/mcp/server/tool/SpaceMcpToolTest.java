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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class SpaceMcpToolTest extends IntegrationTestBase {

  @Autowired
  private SpaceMcpTool         spaceMcpTool;

  @Autowired
  private SpaceTemplateMcpTool spaceTemplateMcpTool;

  @Test
  void createUpdateAndGetSpace() throws Exception {// NOSONAR
    long templateId = resolveTemplateId();
    String name = "mcp-test-space-" + UUID.randomUUID();

    SpaceModel created = spaceMcpTool.createSpace(templateId,
                                                  name,
                                                  "Initial description",
                                                  Visibility.LISTED,
                                                  Registration.OPEN,
                                                  List.of());

    assertNotNull(created);
    assertTrue(created.getSpaceId() > 0);
    assertEquals(name, created.getName());

    SpaceModel updated = spaceMcpTool.updateSpaceSettings(created.getSpaceId(),
                                                          name + "-updated",
                                                          "Updated description",
                                                          Visibility.UNLISTED,
                                                          Registration.REQUEST_JOIN);

    assertNotNull(updated);
    assertEquals(name + "-updated", updated.getName());
    assertEquals("Updated description", updated.getDescription());

    SpaceModel loaded = spaceMcpTool.getSpaceById(created.getSpaceId());

    assertNotNull(loaded);
    assertEquals(created.getSpaceId(), loaded.getSpaceId());
  }

  @Test
  void getAllSpaces() {
    List<SpaceModel> spaces = spaceMcpTool.getAllSpaces(null, 0, 10);

    assertNotNull(spaces);
  }

  @Test
  void getMySpaces() {
    List<SpaceModel> spaces = spaceMcpTool.getMySpaces(null, 0, 10);

    assertNotNull(spaces);
  }

  @Test
  void countMySpaces() {
    long count = spaceMcpTool.countMySpaces(null);

    assertTrue(count >= 0);
  }

  @Test
  void getManagedSpaces() {
    List<SpaceModel> spaces = spaceMcpTool.getManagedSpaces(null, 0, 10);

    assertNotNull(spaces);
  }

  @Test
  void getSpaceByIdWhenNotFound() {
    SpaceModel space = spaceMcpTool.getSpaceById(-1L);

    assertNull(space);
  }

  @Test
  void listUsersOfSpaceByRole() throws Exception {
    SpaceModel space = createTestSpace();

    List<UserModel> members = spaceMcpTool.listUsersOfSpaceByRole(space.getSpaceId(),
                                                                  SpaceRole.MEMBER,
                                                                  0,
                                                                  10);

    assertNotNull(members);
  }

  @Test
  void inviteUsersToSpaceWhenSpaceDoesNotExist() {
    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.inviteUsersToSpace(-1L, List.of(USERNAME)));
  }

  @Test
  void removeUsersFromSpaceWhenSpaceDoesNotExist() {
    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUsersFromSpace(-1L, List.of(USERNAME)));
  }

  @Test
  void setUserRoleInSpaceWhenSpaceDoesNotExist() {
    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.setUserRoleInSpace(-1L, USERNAME, SpaceRole.MANAGER));
  }

  @Test
  void removeUserRoleInSpaceWhenSpaceDoesNotExist() {
    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUserRoleInSpace(-1L, USERNAME, SpaceRole.MANAGER));
  }

  private SpaceModel createTestSpace() throws Exception {// NOSONAR
    return spaceMcpTool.createSpace(resolveTemplateId(),
                                    "mcp-test-space-" + UUID.randomUUID(),
                                    "Test space",
                                    Visibility.LISTED,
                                    Registration.OPEN,
                                    List.of());
  }

  private long resolveTemplateId() {
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);
    assertFalse(templates.isEmpty(), "At least one space template is required");
    return templates.get(0).getId();
  }

}

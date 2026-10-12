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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

/**
 * Exercises the permission gates of {@link SpaceMcpTool} in isolation (plain
 * mocks, no Spring/Kernel context), so that a refusal is pinned independently
 * of the space ACL setup of the integration container.
 */
class SpaceMcpToolAclTest {

  private static final String USERNAME = "john";

  private static final long   SPACE_ID = 7L;

  private final SpaceService  spaceService = mock(SpaceService.class);

  private final SpaceMcpTool  spaceMcpTool = new SpaceMcpTool();

  private final Space         space        = new Space();

  /**
   * Wires the mocked space service and the current user.
   */
  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(spaceMcpTool, "spaceService", spaceService);
    ConversationState.setCurrent(new ConversationState(new Identity(USERNAME)));
    space.setId(String.valueOf(SPACE_ID));
    when(spaceService.getSpaceById(SPACE_ID)).thenReturn(space);
  }

  /**
   * Clears the current user.
   */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * A space manager who doesn't hold the delete permission can't delete the
   * space.
   */
  @Test
  void deleteSpaceDeniedWhenUserCannotDelete() throws Exception {
    when(spaceService.canManageSpace(space, USERNAME)).thenReturn(true);
    when(spaceService.canDeleteSpace(space, USERNAME)).thenReturn(false);

    assertThrows(IllegalStateException.class, () -> spaceMcpTool.deleteSpace(SPACE_ID));

    verify(spaceService, never()).deleteSpace(any(Space.class));
  }

  /**
   * A user holding the delete permission deletes the space, with themselves as
   * the editor of the deletion.
   */
  @Test
  void deleteSpaceSetsEditorWhenUserCanDelete() throws Exception {
    when(spaceService.canDeleteSpace(space, USERNAME)).thenReturn(true);

    spaceMcpTool.deleteSpace(SPACE_ID);

    assertEquals(USERNAME, space.getEditor());
    verify(spaceService).deleteSpace(space);
  }

}

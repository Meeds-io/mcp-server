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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.SpaceRole;

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

  /**
   * Removing the only manager of a space from it is refused.
   */
  @Test
  void removeUsersFromSpaceRefusesOnlyManager() {
    allowManagement();
    when(spaceService.isOnlyManager(space, "mary")).thenReturn(true);

    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("@mary")));

    verify(spaceService, never()).removeMember(any(Space.class), any());
  }

  /**
   * A refusal for one user of the list removes none of them.
   */
  @Test
  void removeUsersFromSpaceRemovesNoneWhenOneIsOnlyManager() {
    allowManagement();
    when(spaceService.isOnlyManager(space, "mary")).thenReturn(true);

    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("paul", "mary")));

    verify(spaceService, never()).removeMember(any(Space.class), any());
  }

  /**
   * A list holding every manager of the space is refused before any removal,
   * though none of them is the only manager. Mutant: the up-front check left
   * out, which removed both managers.
   */
  @Test
  void removeUsersFromSpaceRefusesRemovingEveryManager() {
    allowManagement();
    space.setManagers(new String[] { "paul", "mary" });

    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("@paul", "mary")));

    verify(spaceService, never()).removeMember(any(Space.class), any());
  }

  /**
   * A manager who became the only one after the list was checked, through a
   * change made elsewhere, is not removed: each user is checked against the
   * space as stored right before its removal. Mutant: the check made on the
   * space read at the start, which removed the last manager.
   */
  @Test
  void removeUsersFromSpaceRechecksTheStoredSpaceBeforeEachRemoval() {
    allowManagement();
    space.setManagers(new String[] { "paul", "mary", "jack" });
    Space afterPaul = new Space();
    afterPaul.setId(String.valueOf(SPACE_ID));
    afterPaul.setManagers(new String[] { "mary" });
    when(spaceService.getSpaceById(SPACE_ID)).thenReturn(space, space, afterPaul);
    // as SpaceServiceImpl#isOnlyManager reads it: from the managers of the given object
    when(spaceService.isOnlyManager(any(Space.class), anyString())).thenAnswer(invocation -> {
      String[] managers = invocation.<Space> getArgument(0).getManagers();
      return managers != null && managers.length == 1 && managers[0].equals(invocation.getArgument(1));
    });

    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("paul", "mary")));

    verify(spaceService).removeMember(any(Space.class), eq("paul"));
    verify(spaceService, never()).removeMember(any(Space.class), eq("mary"));
  }

  /**
   * A space deleted while the list is removed is reported as missing, not as a
   * null pointer failure. Mutant: the null guard removed, which fails with a
   * NullPointerException when the removal sets its editor.
   */
  @Test
  void removeUsersFromSpaceRefusesASpaceDeletedMeanwhile() {
    allowManagement();
    space.setManagers(new String[] { "paul", "mary" });
    when(spaceService.getSpaceById(SPACE_ID)).thenReturn(space, null);

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("paul")));

    assertEquals(String.format("Space with Id '%s' doesn't exist", SPACE_ID), refusal.getMessage());
    verify(spaceService, never()).removeMember(any(Space.class), any());
  }

  /**
   * A list that keeps a manager removes the managers it names.
   */
  @Test
  void removeUsersFromSpaceRemovesManagersWhenOneRemains() {
    allowManagement();
    space.setManagers(new String[] { "paul", "mary", "jack" });

    spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("paul", "mary"));

    verify(spaceService).removeMember(space, "paul");
    verify(spaceService).removeMember(space, "mary");
  }

  /**
   * Members who aren't the only manager are removed, the "@" prefix being
   * ignored.
   */
  @Test
  void removeUsersFromSpaceRemovesNonOnlyManagers() {
    allowManagement();

    spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("@paul", "mary"));

    verify(spaceService).removeMember(space, "paul");
    verify(spaceService).removeMember(space, "mary");
  }

  /**
   * The space passed to each removal carries the current user as its editor,
   * on the instance re-read right before that removal.
   */
  @Test
  void removeUsersFromSpaceSetsEditorOnTheSpaceOfEachRemoval() {
    allowManagement();
    Space stored = new Space();
    stored.setId(String.valueOf(SPACE_ID));
    when(spaceService.getSpaceById(SPACE_ID)).thenReturn(space, space, stored, stored);
    List<String> editors = new ArrayList<>();
    doAnswer(invocation -> editors.add(invocation.<Space> getArgument(0).getEditor())).when(spaceService)
                                                                                    .removeMember(any(Space.class), anyString());

    spaceMcpTool.removeUsersFromSpace(SPACE_ID, List.of("paul", "mary"));

    assertEquals(List.of(USERNAME, USERNAME), editors);
    assertEquals(USERNAME, stored.getEditor());
  }

  /**
   * Removing the manager role of the only manager of a space is refused.
   */
  @Test
  void removeUserRoleInSpaceRefusesOnlyManagerRole() {
    allowManagement();
    when(spaceService.isMember(space, "mary")).thenReturn(true);
    when(spaceService.isOnlyManager(space, "mary")).thenReturn(true);

    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.removeUserRoleInSpace(SPACE_ID, "mary", SpaceRole.MANAGER));

    verify(spaceService, never()).setManager(any(Space.class), any(), anyBoolean());
  }

  /**
   * Removing the manager role of a manager who isn't the only one succeeds.
   */
  @Test
  void removeUserRoleInSpaceRemovesManagerRoleWhenNotOnlyManager() {
    allowManagement();
    when(spaceService.isMember(space, "mary")).thenReturn(true);

    spaceMcpTool.removeUserRoleInSpace(SPACE_ID, "mary", SpaceRole.MANAGER);

    verify(spaceService).setManager(space, "mary", false);
  }

  /**
   * Other roles aren't concerned by the last manager guard, as in the Social
   * REST endpoint.
   */
  @Test
  void removeUserRoleInSpaceRemovesRedactorRoleOfOnlyManager() {
    allowManagement();
    when(spaceService.isMember(space, "mary")).thenReturn(true);
    when(spaceService.isOnlyManager(space, "mary")).thenReturn(true);

    spaceMcpTool.removeUserRoleInSpace(SPACE_ID, "mary", SpaceRole.REDACTOR);

    verify(spaceService).removeRedactor(space, "mary");
  }

  /**
   * Whatever the role removed, the space passed to the service carries the
   * current user as its editor.
   */
  @Test
  void removeUserRoleInSpaceSetsEditor() {
    allowManagement();
    when(spaceService.isMember(space, "mary")).thenReturn(true);
    List<String> editors = new ArrayList<>();
    Answer<Object> recordEditor = invocation -> editors.add(invocation.<Space> getArgument(0).getEditor());
    doAnswer(recordEditor).when(spaceService).setManager(any(Space.class), eq("mary"), eq(false));
    doAnswer(recordEditor).when(spaceService).removeRedactor(any(Space.class), eq("mary"));
    doAnswer(recordEditor).when(spaceService).removePublisher(any(Space.class), eq("mary"));

    spaceMcpTool.removeUserRoleInSpace(SPACE_ID, "mary", SpaceRole.MANAGER);
    spaceMcpTool.removeUserRoleInSpace(SPACE_ID, "mary", SpaceRole.REDACTOR);
    spaceMcpTool.removeUserRoleInSpace(SPACE_ID, "mary", SpaceRole.PUBLISHER);

    assertEquals(List.of(USERNAME, USERNAME, USERNAME), editors);
  }

  /**
   * The space passed to the update carries the current user as its editor. The
   * update ends the call there, the mapping of its result being out of scope.
   */
  @Test
  void updateSpaceSettingsSetsEditor() {
    allowManagement();
    ArgumentCaptor<Space> updated = ArgumentCaptor.forClass(Space.class);
    when(spaceService.updateSpace(updated.capture())).thenThrow(new IllegalStateException("stop"));

    assertThrows(IllegalStateException.class,
                 () -> spaceMcpTool.updateSpaceSettings(SPACE_ID, "New name", null, null, null));

    assertEquals(USERNAME, updated.getValue().getEditor());
  }

  /**
   * Lets the current user manage the space.
   */
  private void allowManagement() {
    when(spaceService.canManageSpace(space, USERNAME)).thenReturn(true);
  }

}

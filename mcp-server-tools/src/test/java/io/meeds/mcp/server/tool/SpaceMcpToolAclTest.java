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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SubSpacesPageModel;

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
   * A call limited to a space lists the sub-spaces the user can access but
   * the hidden ones, which the members of the space its answer is posted in
   * may not know of; a call limited to no space lists them all. Mutants: the
   * hidden filter removed, which lists the hidden one under a scope; the
   * filter applied whatever the scope, which drops it without one.
   */
  @Test
  void listSubSpacesLeavesOutHiddenOnesUnderASpaceScope() throws Exception {
    Space hidden = subSpace(21L, Space.HIDDEN);
    Space listed = subSpace(22L, Space.PRIVATE);
    space.setVisibility(Space.PRIVATE);
    @SuppressWarnings("unchecked")
    ListAccess<Space> subSpaces = mock(ListAccess.class);
    when(subSpaces.load(0, 10)).thenReturn(new Space[] { hidden, listed });
    when(spaceService.getAccessibleSpacesByFilter(eq(USERNAME), any())).thenReturn(subSpaces);
    Long[] scope = { SPACE_ID };
    SpaceMcpTool tool = new SpaceMcpTool() {
      @Override
      protected Long getScopeSpaceId() {
        return scope[0];
      }
    };
    ReflectionTestUtils.setField(tool, "spaceService", spaceService);

    try (MockedStatic<CommonsUtils> commonsUtils = mockStatic(CommonsUtils.class)) {
      commonsUtils.when(CommonsUtils::getCurrentDomain).thenReturn("https://meeds.io");

      assertEquals(List.of(22L), tool.listSubSpaces(SPACE_ID, 0, 10).subSpaces().stream().map(SpaceModel::getSpaceId).toList());

      scope[0] = null;
      assertEquals(List.of(21L, 22L), tool.listSubSpaces(SPACE_ID, 0, 10).subSpaces().stream().map(SpaceModel::getSpaceId).toList());
    }
  }

  /**
   * Under a space scope, a page whose loaded sub-spaces were partly hidden
   * loads on from where it stopped until it holds {@code limit} sub-spaces,
   * and the next page starts right after the last sub-space it read, so the
   * one after it is neither skipped nor repeated. Mutants: a single load under
   * a scope, which returns one sub-space; the next offset set to
   * {@code offset + limit} or to the end of the last load, which skips 25.
   */
  @Test
  void listSubSpacesUnderASpaceScopeLoadsOnUntilThePageIsFull() throws Exception {
    ListAccess<Space> subSpaces = mockSubSpaces();
    when(subSpaces.load(0, 2)).thenReturn(new Space[] { subSpace(21L, Space.HIDDEN), subSpace(22L, Space.PRIVATE) });
    when(subSpaces.load(2, 2)).thenReturn(new Space[] { subSpace(24L, Space.PUBLIC), subSpace(25L, Space.PRIVATE) });
    when(subSpaces.load(3, 2)).thenReturn(new Space[] { subSpace(25L, Space.PRIVATE) });
    SpaceMcpTool tool = toolLimitedTo(SPACE_ID);

    try (MockedStatic<CommonsUtils> commonsUtils = mockCurrentDomain()) {
      SubSpacesPageModel page = tool.listSubSpaces(SPACE_ID, 0, 2);
      assertEquals(List.of(22L, 24L), spaceIds(page));
      assertEquals(3, page.nextOffset());

      SubSpacesPageModel nextPage = tool.listSubSpaces(SPACE_ID, page.nextOffset(), 2);
      assertEquals(List.of(25L), spaceIds(nextPage));
      assertNull(nextPage.nextOffset());
    }
  }

  /**
   * Under a space scope, a first load whose sub-spaces are all hidden doesn't
   * end the page: the next load is read, and a load shorter than the limit
   * ends the list, which the page says with no next offset. Mutants: a single
   * load under a scope, which returns an empty page; the end of the list not
   * detected, which reports a next offset.
   */
  @Test
  void listSubSpacesUnderASpaceScopeReadsPastAnAllHiddenLoadToTheEnd() throws Exception {
    ListAccess<Space> subSpaces = mockSubSpaces();
    when(subSpaces.load(4, 2)).thenReturn(new Space[] { subSpace(21L, Space.HIDDEN), subSpace(22L, Space.HIDDEN) });
    when(subSpaces.load(6, 2)).thenReturn(new Space[] { subSpace(23L, Space.PRIVATE) });
    SpaceMcpTool tool = toolLimitedTo(SPACE_ID);

    try (MockedStatic<CommonsUtils> commonsUtils = mockCurrentDomain()) {
      SubSpacesPageModel page = tool.listSubSpaces(SPACE_ID, 4, 2);

      assertEquals(List.of(23L), spaceIds(page));
      assertNull(page.nextOffset());
    }
  }

  /**
   * Under a space scope, a parent whose sub-spaces are all hidden is read
   * {@value SpaceMcpTool#MAX_SCOPED_SUB_SPACES_LOADS} loads at most per call;
   * the page then comes back empty with the offset the next call resumes at,
   * past every sub-space read. Mutants: no bound on the loads, which reads a
   * sixth one; the next offset left at the call's offset, which makes the
   * model read the same sub-spaces again.
   */
  @Test
  void listSubSpacesUnderASpaceScopeStopsAfterItsMaximumLoads() throws Exception {
    ListAccess<Space> subSpaces = mockSubSpaces();
    when(subSpaces.load(anyInt(), eq(2))).thenReturn(new Space[] { subSpace(21L, Space.HIDDEN), subSpace(22L, Space.HIDDEN) });
    SpaceMcpTool tool = toolLimitedTo(SPACE_ID);

    SubSpacesPageModel page = tool.listSubSpaces(SPACE_ID, 0, 2);

    assertEquals(List.of(), page.subSpaces());
    assertEquals(2 * SpaceMcpTool.MAX_SCOPED_SUB_SPACES_LOADS, page.nextOffset());
    verify(subSpaces, times(SpaceMcpTool.MAX_SCOPED_SUB_SPACES_LOADS)).load(anyInt(), eq(2));
    verify(subSpaces, never()).load(2 * SpaceMcpTool.MAX_SCOPED_SUB_SPACES_LOADS, 2);
  }

  /**
   * A call limited to no space keeps the hidden sub-spaces in a page of one
   * load, and reports the next offset when the page is full, none when the
   * load came back short. Mutants: the hidden ones left out without a scope;
   * no next offset on a full page; a next offset on a short one.
   */
  @Test
  void listSubSpacesWithoutScopeMakesOneLoad() throws Exception {
    ListAccess<Space> subSpaces = mockSubSpaces();
    when(subSpaces.load(0, 2)).thenReturn(new Space[] { subSpace(21L, Space.HIDDEN), subSpace(22L, Space.PRIVATE) });
    when(subSpaces.load(2, 2)).thenReturn(new Space[] { subSpace(23L, Space.PRIVATE) });
    SpaceMcpTool tool = toolLimitedTo(null);

    try (MockedStatic<CommonsUtils> commonsUtils = mockCurrentDomain()) {
      SubSpacesPageModel page = tool.listSubSpaces(SPACE_ID, 0, 2);
      assertEquals(List.of(21L, 22L), spaceIds(page));
      assertEquals(2, page.nextOffset());
      verify(subSpaces, never()).load(2, 2);

      SubSpacesPageModel lastPage = tool.listSubSpaces(SPACE_ID, 2, 2);
      assertEquals(List.of(23L), spaceIds(lastPage));
      assertNull(lastPage.nextOffset());
    }
  }

  /**
   * A negative limit is refused before any sub-space is loaded: such a page
   * could never be full. A limit of 0, like none, takes the default page
   * size. Mutants: the raw limit refused when not positive, which refuses 0;
   * the refusal after the list is asked for.
   */
  @Test
  void listSubSpacesRefusesANegativeLimitAndDefaultsAZeroOne() throws Exception {
    ListAccess<Space> subSpaces = mockSubSpaces();
    SpaceMcpTool tool = toolLimitedTo(SPACE_ID);

    assertThrows(IllegalArgumentException.class, () -> tool.listSubSpaces(SPACE_ID, 0, -1));
    verify(spaceService, never()).getAccessibleSpacesByFilter(anyString(), any());

    assertNull(tool.listSubSpaces(SPACE_ID, 0, 0).nextOffset());
    verify(subSpaces).load(0, McpToolPlugin.DEFAULT_LIMIT);
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

  /**
   * @return the mocked list of the space's sub-spaces the current user can
   *         access, returned by the space service
   */
  private ListAccess<Space> mockSubSpaces() {
    space.setVisibility(Space.PRIVATE);
    @SuppressWarnings("unchecked")
    ListAccess<Space> subSpaces = mock(ListAccess.class);
    when(spaceService.getAccessibleSpacesByFilter(eq(USERNAME), any())).thenReturn(subSpaces);
    return subSpaces;
  }

  /**
   * @param scopeSpaceId the space the calls are limited to, null for none
   * @return a tool whose calls are limited to that space
   */
  private SpaceMcpTool toolLimitedTo(Long scopeSpaceId) {
    SpaceMcpTool tool = new SpaceMcpTool() {
      @Override
      protected Long getScopeSpaceId() {
        return scopeSpaceId;
      }
    };
    ReflectionTestUtils.setField(tool, "spaceService", spaceService);
    return tool;
  }

  /**
   * @return the static mock answering the domain the space models are built
   *         with
   */
  private static MockedStatic<CommonsUtils> mockCurrentDomain() {
    MockedStatic<CommonsUtils> commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(CommonsUtils::getCurrentDomain).thenReturn("https://meeds.io");
    return commonsUtils;
  }

  /**
   * @param page a page of sub-spaces
   * @return the ids of its sub-spaces
   */
  private static List<Long> spaceIds(SubSpacesPageModel page) {
    return page.subSpaces().stream().map(SpaceModel::getSpaceId).toList();
  }

  /**
   * @param id         the sub-space id
   * @param visibility its visibility
   * @return a sub-space of the space, with no member
   */
  private static Space subSpace(long id, String visibility) {
    Space subSpace = new Space();
    subSpace.setId(String.valueOf(id));
    subSpace.setVisibility(visibility);
    subSpace.setRegistration(Space.OPEN);
    subSpace.setParentSpaceId(SPACE_ID);
    subSpace.setTemplateId(1L);
    subSpace.setMembers(new String[0]);
    subSpace.setManagers(new String[0]);
    return subSpace;
  }

}

/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2026 Meeds Association contact@meeds.io
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceSearchHitModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

/**
 * Runs {@code search_spaces} against the real space storage of the test
 * container, where the search falls back to the database, to pin which spaces
 * a user gets: those of the spaces directory, where a hidden space is
 * hidden from a user who neither belongs to it nor manages its template.
 */
class SpaceSearchMcpToolTest extends IntegrationTestBase {

  private static final String  OTHER_USER = "mary";

  @Autowired
  private SpaceSearchMcpTool   spaceSearchMcpTool;

  @Autowired
  private SpaceMcpTool         spaceMcpTool;

  @Autowired
  private SpaceTemplateMcpTool spaceTemplateMcpTool;

  @Autowired
  private SpaceService         spaceService;

  /**
   * A member finds a listed space by a word of its name, whatever its case,
   * and the hit says they are a member with their roles.
   */
  @Test
  void searchSpacesFindsAListedSpaceByAWordOfItsName() throws Exception {
    String word = uniqueWord();
    SpaceModel listed = createSpace("Client " + word.toUpperCase(), Visibility.LISTED);

    List<SpaceSearchHitModel> hits = spaceSearchMcpTool.searchSpaces(word, null);

    SpaceSearchHitModel hit = hits.stream().filter(h -> h.spaceId() == listed.getSpaceId()).findFirst().orElseThrow();
    assertEquals("Client " + word.toUpperCase(), hit.name());
    assertTrue(hit.member());
    assertTrue(hit.myRoles().contains(SpaceRole.MANAGER));
  }

  /**
   * A user outside a hidden space doesn't get it, while they get the listed
   * space of the same name, as a non-member.
   */
  @Test
  void searchSpacesHidesAHiddenSpaceFromANonMember() throws Exception {
    String word = uniqueWord();
    SpaceModel listed = createSpace("Client " + word, Visibility.LISTED);
    SpaceModel hidden = createSpace("Client " + word + " internal", Visibility.UNLISTED);

    ConversationState.setCurrent(new ConversationState(new Identity(OTHER_USER)));
    List<SpaceSearchHitModel> hits = spaceSearchMcpTool.searchSpaces(word, null);

    assertFalse(hits.stream().anyMatch(h -> h.spaceId() == hidden.getSpaceId()), "a hidden space reached a non-member");
    SpaceSearchHitModel hit = hits.stream().filter(h -> h.spaceId() == listed.getSpaceId()).findFirst().orElseThrow();
    assertFalse(hit.member());
    assertTrue(hit.myRoles().isEmpty());
    assertEquals(null, hit.lastActivityDate());
  }

  /**
   * A user only invited to a hidden space doesn't get it either: the
   * database fallback drops the invited status ({@code XSpaceFilter}), and
   * the Elasticsearch permissions of a hidden space name no invited user.
   */
  @Test
  void searchSpacesHidesAHiddenSpaceFromAnInvitedUser() throws Exception {
    String word = uniqueWord();
    SpaceModel hidden = createSpace("Client " + word, Visibility.UNLISTED);
    spaceMcpTool.inviteUsersToSpace(hidden.getSpaceId(), List.of(OTHER_USER));
    assertTrue(spaceService.isInvitedUser(spaceService.getSpaceById(hidden.getSpaceId()), OTHER_USER));

    ConversationState.setCurrent(new ConversationState(new Identity(OTHER_USER)));
    List<SpaceSearchHitModel> hits = spaceSearchMcpTool.searchSpaces(word, null);

    assertFalse(hits.stream().anyMatch(h -> h.spaceId() == hidden.getSpaceId()), "a hidden space reached an invited user");
  }

  /**
   * The hidden space stays found by its own member.
   */
  @Test
  void searchSpacesFindsAHiddenSpaceForItsMember() throws Exception {
    String word = uniqueWord();
    SpaceModel hidden = createSpace("Client " + word, Visibility.UNLISTED);

    List<SpaceSearchHitModel> hits = spaceSearchMcpTool.searchSpaces(word, null);

    assertTrue(hits.stream().anyMatch(h -> h.spaceId() == hidden.getSpaceId()));
  }

  /**
   * A search with no word is refused rather than listing every space.
   */
  @Test
  void searchSpacesRefusesABlankQuery() {
    assertThrows(IllegalArgumentException.class, () -> spaceSearchMcpTool.searchSpaces("  ", null));
  }

  /**
   * Creates a space managed by the current user.
   *
   * @param name the space name
   * @param visibility the space visibility
   * @return the created space
   */
  private SpaceModel createSpace(String name, Visibility visibility) throws Exception {// NOSONAR
    return spaceMcpTool.createSpace(resolveTemplateId(),
                                    name,
                                    "Search test space",
                                    visibility,
                                    Registration.OPEN,
                                    List.of(),
                                    null);
  }

  /**
   * @return a word no other test space holds, letters only
   */
  private String uniqueWord() {
    return "schiever" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
  }

  /**
   * @return the identifier of the first space template the current user may
   *         use
   */
  private long resolveTemplateId() {
    List<SpaceTemplateModel> templates = spaceTemplateMcpTool.listSpaceTemplates(null);
    assertFalse(templates.isEmpty(), "At least one space template is required");
    return templates.get(0).getId();
  }

}

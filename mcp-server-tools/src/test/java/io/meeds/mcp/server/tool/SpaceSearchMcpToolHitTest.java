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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.common.RealtimeListAccess;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.SpaceFilter;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.model.SpaceSearchHitModel;
import io.meeds.mcp.server.util.McpToolUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * Pins what {@code search_spaces} asks the space service and what each hit
 * carries, with plain mocks: the visibility rule it delegates to, its limit
 * bounds, the order it keeps, and which hits get a latest-activity date.
 */
class SpaceSearchMcpToolHitTest {

  private static final String               USERNAME        = "john";

  private final SpaceService                spaceService    = mock(SpaceService.class);

  private final IdentityManager             identityManager = mock(IdentityManager.class);

  private final ActivityManager             activityManager = mock(ActivityManager.class);

  @SuppressWarnings("unchecked")
  private final ListAccess<Space>           spacesAccess    = mock(ListAccess.class);

  private final SpaceSearchMcpTool          tool            = new SpaceSearchMcpTool();

  private MockedStatic<McpToolUtils>        mcpToolUtils;

  /**
   * Wires the mocks, the current user and a fixed time zone for the dates.
   */
  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(tool, "spaceService", spaceService);
    ReflectionTestUtils.setField(tool, "identityManager", identityManager);
    ReflectionTestUtils.setField(tool, "activityManager", activityManager);
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity(USERNAME)));
    when(spaceService.getVisibleSpacesWithListAccess(eq(USERNAME), any(SpaceFilter.class))).thenReturn(spacesAccess);
    mcpToolUtils = mockStatic(McpToolUtils.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
    mcpToolUtils.when(McpToolUtils::getUserTimeZone).thenReturn(TimeZone.getTimeZone("UTC"));
  }

  /**
   * Clears the current user and the static mock.
   */
  @AfterEach
  void tearDown() {
    mcpToolUtils.close();
    ConversationState.setCurrent(null);
  }

  /**
   * A query with no word, blank or punctuation only, is refused before any
   * space is read.
   */
  @Test
  void blankQueryIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> tool.searchSpaces(" ", null));
    assertThrows(IllegalArgumentException.class, () -> tool.searchSpaces(null, 5));
    // no letter nor digit: "~" would reach the database as no condition at all
    assertThrows(IllegalArgumentException.class, () -> tool.searchSpaces("~", null));
    assertThrows(IllegalArgumentException.class, () -> tool.searchSpaces("!!!", null));
    // its words cut by the unified search syntax, "~abc" would be no condition either
    assertThrows(IllegalArgumentException.class, () -> tool.searchSpaces("~abc", null));
    assertThrows(IllegalArgumentException.class, () -> tool.searchSpaces("~abc ~def", null));
    verify(spaceService, never()).getVisibleSpacesWithListAccess(anyString(), any());
  }

  /**
   * The search runs on the spaces directory's visibility rule for the current
   * user, with the query's words, reads the engine's first
   * {@value SpaceSearchMcpTool#MAX_LIMIT} hits to rank them, and returns the
   * default number of them.
   */
  @Test
  void searchUsesTheDirectoryVisibilityAndTheDefaultLimit() throws Exception {
    when(spacesAccess.load(anyInt(), anyInt())).thenReturn(spaces(60));

    assertEquals(10, tool.searchSpaces("  schiever ", null).size());

    ArgumentCaptor<SpaceFilter> filter = ArgumentCaptor.forClass(SpaceFilter.class);
    verify(spaceService).getVisibleSpacesWithListAccess(eq(USERNAME), filter.capture());
    assertEquals(new SpaceFilter("schiever").getSpaceNameSearchCondition(), filter.getValue().getSpaceNameSearchCondition());
    assertTrue(filter.getValue().isSortingEmpty(), "a sorting would replace the relevance order");
    verify(spacesAccess).load(0, SpaceSearchMcpTool.MAX_LIMIT);
  }

  /**
   * A limit above the maximum is lowered to it, a negative one raised to 1,
   * and 0 reads as absent, as for the other listing tools.
   */
  @Test
  void limitIsBounded() throws Exception {
    when(spacesAccess.load(anyInt(), anyInt())).thenReturn(spaces(60));

    assertEquals(SpaceSearchMcpTool.MAX_LIMIT, tool.searchSpaces("client", 500).size());
    assertEquals(1, tool.searchSpaces("client", -3).size());
    assertEquals(10, tool.searchSpaces("client", 0).size());
    assertEquals(3, tool.searchSpaces("client", 3).size());
  }

  /**
   * A space with a name word starting with each of the query's words comes
   * before one that only mentions them in its description, whatever the engine's order, and the
   * space named exactly by the query comes first; case and accents are
   * ignored, and the engine's order is kept within each rank.
   */
  @Test
  void aNameMatchComesBeforeADescriptionMatch() throws Exception {
    Space marketing = space(1, "Marketing");
    Space tender = space(2, "Schiever tender 2026");
    Space internal = space(3, "Client Schiever internal");
    Space client = space(4, "Client SCHIEVER");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { marketing, tender, internal, client });

    assertEquals(List.of(2L, 3L, 4L, 1L),
                 tool.searchSpaces("schiever", null).stream().map(SpaceSearchHitModel::spaceId).toList());
    assertEquals(List.of(4L, 3L, 1L, 2L),
                 tool.searchSpaces("Client Schièver", null).stream().map(SpaceSearchHitModel::spaceId).toList());
    // ranked on the condition searched: "schiever~0.8" searches "schiever"
    assertEquals(List.of(2L, 3L, 4L, 1L),
                 tool.searchSpaces("schiever~0.8", null).stream().map(SpaceSearchHitModel::spaceId).toList());
    assertEquals(List.of(4L, 3L, 1L, 2L),
                 tool.searchSpaces("Client~0.8 SCHIEVER~0.8", null).stream().map(SpaceSearchHitModel::spaceId).toList());
    // a word inside a name's word is no name match: "ting" is in "Marketing" but doesn't start it
    Space ting = space(5, "Ting club");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { marketing, ting });
    assertEquals(List.of(5L, 1L), tool.searchSpaces("ting", null).stream().map(SpaceSearchHitModel::spaceId).toList());
  }

  /**
   * Hits of the same rank keep the order the search returned, the best match
   * first.
   */
  @Test
  void hitsKeepTheSearchOrder() throws Exception {
    Space first = space(1, "Client SCHIEVER");
    Space second = space(2, "Schiever tender");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { first, second });

    List<SpaceSearchHitModel> hits = tool.searchSpaces("schiever", null);

    assertEquals(List.of(1L, 2L), hits.stream().map(SpaceSearchHitModel::spaceId).toList());
    assertEquals("Client SCHIEVER", hits.getFirst().name());
  }

  /**
   * A member's hit says so, with their roles and the date of the latest
   * activity of the space stream.
   */
  @Test
  void memberHitCarriesRolesAndLatestActivityDate() throws Exception {
    Space space = space(1, "Client SCHIEVER");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { space });
    when(spaceService.isMember(space, USERNAME)).thenReturn(true);
    when(spaceService.isManager(space, USERNAME)).thenReturn(true);
    when(spaceService.canViewSpace(space, USERNAME)).thenReturn(true);
    stubLatestActivity();

    SpaceSearchHitModel hit = tool.searchSpaces("schiever", null).getFirst();

    assertTrue(hit.member());
    assertTrue(hit.myRoles().contains(SpaceRole.MEMBER));
    assertTrue(hit.myRoles().contains(SpaceRole.MANAGER));
    assertNotNull(hit.lastActivityDate());
    assertTrue(hit.lastActivityDate().startsWith("2025-10-09T08:53:20"), hit.lastActivityDate());
  }

  /**
   * A user who can't read the space stream gets no latest-activity date, and
   * the stream is not read for them.
   */
  @Test
  void nonMemberHitHasNoActivityDate() throws Exception {
    Space space = space(1, "Client SCHIEVER");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { space });
    when(spaceService.canViewSpace(space, USERNAME)).thenReturn(false);
    stubLatestActivity();

    SpaceSearchHitModel hit = tool.searchSpaces("schiever", null).getFirst();

    assertFalse(hit.member());
    assertTrue(hit.myRoles().isEmpty());
    assertNull(hit.lastActivityDate());
    verify(activityManager, never()).getActivitiesOfSpaceWithListAccess(any(Identity.class));
  }

  /**
   * A space whose stream can't be read gets no date and still comes back.
   */
  @Test
  void unreadableStreamLeavesTheDateOut() throws Exception {
    Space space = space(1, "Client SCHIEVER");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { space });
    when(spaceService.canViewSpace(space, USERNAME)).thenReturn(true);
    when(identityManager.getOrCreateSpaceIdentity("client_schiever")).thenThrow(new IllegalStateException("boom"));

    List<SpaceSearchHitModel> hits = tool.searchSpaces("schiever", null);

    assertEquals(1, hits.size());
    assertNull(hits.getFirst().lastActivityDate());
  }

  /**
   * A readable space whose stream is empty gets no date and still comes back.
   */
  @Test
  void emptyStreamLeavesTheDateOut() throws Exception {
    Space space = space(1, "Client SCHIEVER");
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { space });
    when(spaceService.canViewSpace(space, USERNAME)).thenReturn(true);
    Identity spaceIdentity = new Identity("space", "client_schiever");
    when(identityManager.getOrCreateSpaceIdentity("client_schiever")).thenReturn(spaceIdentity);
    @SuppressWarnings("unchecked")
    RealtimeListAccess<ExoSocialActivity> stream = mock(RealtimeListAccess.class);
    when(stream.loadAsList(0, 1)).thenReturn(List.of());
    when(activityManager.getActivitiesOfSpaceWithListAccess(spaceIdentity)).thenReturn(stream);
    Logger logger = (Logger) LoggerFactory.getLogger(SpaceSearchMcpTool.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    Level level = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    List<SpaceSearchHitModel> hits;
    try {
      hits = tool.searchSpaces("schiever", null);
    } finally {
      logger.setLevel(level);
      logger.detachAppender(appender);
    }

    assertEquals(1, hits.size());
    assertNull(hits.getFirst().lastActivityDate());
    assertTrue(appender.list.isEmpty(), () -> "An empty stream is no read failure: " + appender.list);
  }

  /**
   * A non-member hit says so explicitly in the JSON the assistant reads, and
   * a long description is shortened.
   */
  @Test
  void hitJsonStatesNonMembershipAndShortensTheDescription() throws Exception {
    Space space = space(1, "Client SCHIEVER");
    space.setDescription("x".repeat(1000));
    when(spacesAccess.load(0, SpaceSearchMcpTool.MAX_LIMIT)).thenReturn(new Space[] { space });

    SpaceSearchHitModel hit = tool.searchSpaces("schiever", null).getFirst();
    String json = JsonMapper.builder().build().writeValueAsString(hit);

    assertTrue(json.contains("\"is_member\":false"), json);
    assertTrue(json.contains("\"space_id\":1"), json);
    assertFalse(json.contains("last_activity_date"), json);
    assertEquals(SpaceSearchMcpTool.DESCRIPTION_MAX_LENGTH, hit.description().length());
  }

  /**
   * Gives the {@code client_schiever} space a stream whose latest activity was
   * updated on 2025-10-09 at 08:53:20 UTC.
   */
  private void stubLatestActivity() {
    Identity spaceIdentity = new Identity("space", "client_schiever");
    when(identityManager.getOrCreateSpaceIdentity("client_schiever")).thenReturn(spaceIdentity);
    ExoSocialActivity latest = mock(ExoSocialActivity.class);
    when(latest.getUpdated()).thenReturn(new Date(1_760_000_000_000L));
    @SuppressWarnings("unchecked")
    RealtimeListAccess<ExoSocialActivity> stream = mock(RealtimeListAccess.class);
    when(stream.loadAsList(0, 1)).thenReturn(List.of(latest));
    when(activityManager.getActivitiesOfSpaceWithListAccess(spaceIdentity)).thenReturn(stream);
  }

  /**
   * @param count how many spaces to build
   * @return that many listed spaces, with ids from 1
   */
  private Space[] spaces(int count) {
    return IntStream.rangeClosed(1, count).mapToObj(id -> space(id, "Client " + id)).toArray(Space[]::new);
  }

  /**
   * Builds a listed, open space.
   *
   * @param id the space id
   * @param name the space name
   * @return the space
   */
  private Space space(long id, String name) {
    Space space = new Space();
    space.setId(String.valueOf(id));
    space.setDisplayName(name);
    space.setPrettyName("client_schiever");
    space.setVisibility(Space.PRIVATE);
    space.setRegistration(Space.OPEN);
    return space;
  }

}

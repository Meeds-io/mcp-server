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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.metadata.MetadataService;
import org.exoplatform.social.metadata.favorite.FavoriteService;
import org.exoplatform.social.metadata.model.MetadataItem;
import org.exoplatform.social.metadata.tag.TagService;

import io.meeds.mcp.server.tool.model.TaggedContentModel;

/**
 * Exercises the ACL gating added to {@link TagMcpTool} in isolation (plain
 * mocks, no Spring/Kernel context), since the real favorite ACL plugins
 * aren't wired in the lighter integration-test container used by
 * {@link TagMcpToolTest}.
 */
class TagMcpToolAclTest {

  private final TagService      tagService      = mock(TagService.class);

  private final MetadataService metadataService = mock(MetadataService.class);

  private final FavoriteService favoriteService = mock(FavoriteService.class);

  private final TagMcpTool      tagMcpTool      = new TagMcpTool();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(tagMcpTool, "tagService", tagService);
    ReflectionTestUtils.setField(tagMcpTool, "metadataService", metadataService);
    ReflectionTestUtils.setField(tagMcpTool, "favoriteService", favoriteService);
    ConversationState.setCurrent(new ConversationState(new Identity("john")));
  }

  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  @Test
  void getContentTagsSucceedsWhenAccessAllowed() throws Exception {
    when(favoriteService.canCreateFavorite(any(), eq("activity"), eq("1"))).thenReturn(true);

    assertNotNull(tagMcpTool.getContentTags("activity", "1"));
  }

  @Test
  void getContentTagsDeniedWhenAccessNotAllowed() {
    when(favoriteService.canCreateFavorite(any(), eq("activity"), eq("2"))).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> tagMcpTool.getContentTags("activity", "2"));
  }

  @Test
  void getContentTagsDeniedWhenAclPluginCantResolveContent() {
    // some object-type ACL plugins throw (rather than return false) when the object doesn't exist
    when(favoriteService.canCreateFavorite(any(), eq("activity"), eq("3"))).thenThrow(new IllegalStateException("not found"));

    assertThrows(IllegalAccessException.class, () -> tagMcpTool.getContentTags("activity", "3"));
  }

  @Test
  void getContentsByTagFiltersOutInaccessibleItems() {
    MetadataItem visible = mock(MetadataItem.class);
    when(visible.getObjectType()).thenReturn("activity");
    when(visible.getObjectId()).thenReturn("1");
    when(visible.getSpaceId()).thenReturn(0L);

    MetadataItem hidden = mock(MetadataItem.class);
    when(hidden.getObjectType()).thenReturn("activity");
    when(hidden.getObjectId()).thenReturn("2");

    when(metadataService.getMetadataItemsByMetadataNameAndTypeAndObject(eq("road"),
                                                                        eq(TagService.METADATA_TYPE.getName()),
                                                                        eq("activity"),
                                                                        anyLong(),
                                                                        anyLong())).thenReturn(List.of(visible, hidden));
    when(favoriteService.canCreateFavorite(any(), eq("activity"), eq("1"))).thenReturn(true);
    when(favoriteService.canCreateFavorite(any(), eq("activity"), eq("2"))).thenReturn(false);

    List<TaggedContentModel> contents = tagMcpTool.getContentsByTag("road", "activity", 0, 10);

    assertEquals(1, contents.size());
    assertEquals("1", contents.get(0).contentId());
  }

  @Test
  void getContentsByTagIsEmptyWhenNoItems() {
    when(metadataService.getMetadataItemsByMetadataNameAndTypeAndObject(any(), any(), any(), anyLong(), anyLong()))
                                                                                                                  .thenReturn(Collections.emptyList());

    assertNotNull(tagMcpTool.getContentsByTag("nonexistent-tag", "activity", 0, 10));
  }

}

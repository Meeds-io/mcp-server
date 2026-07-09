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

import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.metadata.MetadataService;
import org.exoplatform.social.metadata.model.MetadataItem;
import org.exoplatform.social.metadata.tag.TagService;
import org.exoplatform.social.metadata.tag.model.TagFilter;
import org.exoplatform.social.metadata.tag.model.TagName;
import org.exoplatform.social.metadata.tag.model.TagObject;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.TaggedContentModel;

@Service
public class TagMcpTool implements McpToolPlugin {

  private static final String DEFAULT_TAGGED_TYPE = "activity";

  @Autowired
  private TagService          tagService;

  @Autowired
  private MetadataService     metadataService;

  @Autowired
  private IdentityManager     identityManager;

  public List<String> getContentTags(String contentType, String contentId) {
    if (StringUtils.isBlank(contentType) || StringUtils.isBlank(contentId)) {
      throw new IllegalArgumentException("Both 'content_type' and 'content_id' are mandatory.");
    }
    Set<TagName> tagNames = tagService.getTagNames(new TagObject(contentType, contentId));
    return tagNames == null ? Collections.emptyList() : tagNames.stream().map(TagName::getName).toList();
  }

  public List<String> searchTags(String query, Integer limit) throws IllegalAccessException {
    List<TagName> tagNames = tagService.findTags(new TagFilter(StringUtils.defaultString(query), getInteger(limit, DEFAULT_LIMIT)),
                                                 currentUserIdentityId());
    return tagNames == null ? Collections.emptyList() : tagNames.stream().map(TagName::getName).toList();
  }

  public List<TaggedContentModel> getContentsByTag(String tag,
                                                   String contentType,
                                                   Integer offset,
                                                   Integer limit) {
    if (StringUtils.isBlank(tag)) {
      throw new IllegalArgumentException("'tag' is mandatory.");
    }
    String tagName = tag.startsWith("#") ? tag.substring(1) : tag;
    String objectType = StringUtils.isBlank(contentType) ? DEFAULT_TAGGED_TYPE : contentType;
    List<MetadataItem> items = metadataService.getMetadataItemsByMetadataNameAndTypeAndObject(tagName,
                                                                                             TagService.METADATA_TYPE.getName(),
                                                                                             objectType,
                                                                                             getInteger(offset, DEFAULT_OFFSET),
                                                                                             getInteger(limit, DEFAULT_LIMIT));
    return items == null ? Collections.emptyList()
                         : items.stream()
                                .map(item -> new TaggedContentModel(item.getObjectType(),
                                                                    item.getObjectId(),
                                                                    item.getSpaceId() > 0 ? item.getSpaceId() : null))
                                .toList();
  }

  private long currentUserIdentityId() {
    return Long.parseLong(identityManager.getOrCreateUserIdentity(getCurrentUserName()).getId());
  }

}

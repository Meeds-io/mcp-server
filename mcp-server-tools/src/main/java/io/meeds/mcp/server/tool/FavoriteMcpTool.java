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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.metadata.favorite.FavoriteService;
import org.exoplatform.social.metadata.favorite.model.Favorite;
import org.exoplatform.social.metadata.model.MetadataItem;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.FavoriteModel;

@Service
public class FavoriteMcpTool implements McpToolPlugin {

  @Autowired
  private FavoriteService favoriteService;

  @Autowired
  private IdentityManager identityManager;

  public void addToFavorites(String contentType, String contentId) throws IllegalAccessException {
    checkArguments(contentType, contentId);
    org.exoplatform.services.security.Identity userAclIdentity = getCurrentUserAclIdentity();
    if (!favoriteService.canCreateFavorite(userAclIdentity, contentType, contentId)) {
      throw new IllegalAccessException("You can't add this '%s' to your favorites: it either doesn't exist or you don't have access to it.".formatted(contentType));
    }
    try {
      favoriteService.createFavorite(new Favorite(contentType, contentId, null, currentUserIdentityId()));
    } catch (ObjectAlreadyExistsException e) {
      // already a favorite: no-op, keep the tool idempotent
    }
  }

  public void removeFromFavorites(String contentType, String contentId) throws ObjectNotFoundException {
    checkArguments(contentType, contentId);
    favoriteService.deleteFavorite(new Favorite(contentType, contentId, null, currentUserIdentityId()));
  }

  public boolean isContentFavorite(String contentType, String contentId) {
    checkArguments(contentType, contentId);
    return favoriteService.isFavorite(new Favorite(contentType, contentId, null, currentUserIdentityId()));
  }

  public List<FavoriteModel> listMyFavorites(String contentType, Integer offset, Integer limit) {
    long userIdentityId = currentUserIdentityId();
    List<MetadataItem> items = StringUtils.isBlank(contentType)
                                                                ? favoriteService.getFavoriteItemsByCreator(userIdentityId,
                                                                                                            getInteger(offset,
                                                                                                                       DEFAULT_OFFSET),
                                                                                                            getInteger(limit,
                                                                                                                       DEFAULT_LIMIT))
                                                                : favoriteService.getFavoriteItemsByCreatorAndType(contentType,
                                                                                                                   userIdentityId,
                                                                                                                   getInteger(offset,
                                                                                                                              DEFAULT_OFFSET),
                                                                                                                   getInteger(limit,
                                                                                                                              DEFAULT_LIMIT));
    if (items == null) {
      return Collections.emptyList();
    }
    return items.stream()
                .map(item -> new FavoriteModel(item.getObjectType(),
                                               item.getObjectId(),
                                               item.getSpaceId() > 0 ? item.getSpaceId() : null))
                .toList();
  }

  private long currentUserIdentityId() {
    return Long.parseLong(identityManager.getOrCreateUserIdentity(getCurrentUserName()).getId());
  }

  private void checkArguments(String contentType, String contentId) {
    if (StringUtils.isBlank(contentType) || StringUtils.isBlank(contentId)) {
      throw new IllegalArgumentException("Both 'content_type' and 'content_id' are mandatory.");
    }
  }

}

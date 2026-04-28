/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2025 Meeds Association contact@meeds.io
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
import static io.meeds.mcp.server.tool.util.UserToolUtils.toUserModel;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profile.ProfileFilter;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.social.translation.service.TranslationService;

import lombok.SneakyThrows;

@Service
public class UserMcpTool implements McpToolPlugin {

  private IdentityManager         identityManager;

  private ProfilePropertyService  profilePropertyService;

  private UserACL                 userAcl;

  private TranslationService      translationService;

  private UserPortalConfigService portalConfigService;

  public UserMcpTool(IdentityManager identityManager,
                          ProfilePropertyService profilePropertyService,
                          UserACL userAcl,
                          TranslationService translationService,
                          UserPortalConfigService portalConfigService) {
    this.identityManager = identityManager;
    this.profilePropertyService = profilePropertyService;
    this.userAcl = userAcl;
    this.translationService = translationService;
    this.portalConfigService = portalConfigService;
  }

  public UserModel getMyUserInformation() {
    String username = getCurrentUserName();
    return toUserModel(identityManager,
                       profilePropertyService,
                       userAcl,
                       translationService,
                       portalConfigService,
                       username,
                       username,
                       getCurrentUserLocale(username),
                       false);
  }

  public UserModel getUserByUsername(String username) {
    return toUserModel(identityManager,
                       profilePropertyService,
                       userAcl,
                       translationService,
                       portalConfigService,
                       username,
                       getCurrentUserName(),
                       getCurrentUserLocale(username),
                       false);
  }

  @SneakyThrows
  public List<UserModel> searchUsers(String query,
                                     Integer offset,
                                     Integer limit) {
    String username = getCurrentUserName();

    ProfileFilter filter = new ProfileFilter();
    filter.setName(StringUtils.isBlank(query) ? "" : query);
    filter.setSearchUserName(true);
    filter.setSearchEmail(false);
    filter.setEnabled(true);
    filter.setViewerIdentity(identityManager.getOrCreateUserIdentity(username));
    ListAccess<Identity> identityListAccess = identityManager.getIdentitiesByProfileFilter(OrganizationIdentityProvider.NAME,
                                                                                           filter,
                                                                                           true);
    Identity[] identities = identityListAccess.load(getInteger(offset, DEFAULT_OFFSET),
                                                    getInteger(limit, DEFAULT_LIMIT));
    if (ArrayUtils.isEmpty(identities)) {
      return Collections.emptyList();
    } else {
      return Stream.of(identities)
                   .map(Identity::getRemoteId)
                   .map(u -> toUserModel(identityManager,
                                         profilePropertyService,
                                         userAcl,
                                         translationService,
                                         portalConfigService,
                                         u,
                                         username,
                                         getCurrentUserLocale(username),
                                         false))
                   .toList();
    }
  }

  @SuppressWarnings("deprecation")
  public long getUsersCount() {
    String username = getCurrentUserName();
    if (userAcl.getUserIdentity(username).isMemberOf("/platform/users")) {
      return identityManager.getIdentitiesCount(OrganizationIdentityProvider.NAME);
    } else {
      throw new IllegalStateException("Guest users can't access this information");
    }
  }

}

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
import static io.meeds.mcp.server.tool.util.UserToolUtils.toUserModel;
import static io.meeds.mcp.server.util.McpToolUtils.formatDate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.services.user.UserStateService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.manager.RelationshipManager;
import org.exoplatform.social.core.profile.ProfileFilter;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.relationship.model.Relationship;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.OnlineStatusModel;
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

  private RelationshipManager     relationshipManager;

  private UserStateService        userStateService;

  public UserMcpTool(IdentityManager identityManager,
                     ProfilePropertyService profilePropertyService,
                     UserACL userAcl,
                     TranslationService translationService,
                     UserPortalConfigService portalConfigService,
                     RelationshipManager relationshipManager,
                     UserStateService userStateService) {
    this.identityManager = identityManager;
    this.profilePropertyService = profilePropertyService;
    this.userAcl = userAcl;
    this.translationService = translationService;
    this.portalConfigService = portalConfigService;
    this.relationshipManager = relationshipManager;
    this.userStateService = userStateService;
  }

  public UserModel getMyUserInformation() {
    String username = getCurrentUserName();
    return user(username);
  }

  public UserModel getUserByUsername(String username) {
    return user(username);
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
    return toUserModels(identities);
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

  @SneakyThrows
  public List<UserModel> listConnectionRequests(Integer offset, Integer limit) {
    ListAccess<Identity> listAccess = relationshipManager.getIncomingWithListAccess(me());
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  @SneakyThrows
  public List<UserModel> listSentConnectionRequests(Integer offset, Integer limit) {
    ListAccess<Identity> listAccess = relationshipManager.getOutgoing(me());
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  @SneakyThrows
  public List<UserModel> listMyConnections(String query, Integer offset, Integer limit) {
    Identity currentIdentity = me();
    ListAccess<Identity> listAccess;
    if (StringUtils.isBlank(query)) {
      listAccess = relationshipManager.getConnections(currentIdentity);
    } else {
      ProfileFilter filter = new ProfileFilter();
      filter.setName(query);
      filter.setViewerIdentity(currentIdentity);
      listAccess = relationshipManager.getConnectionsByFilter(currentIdentity, filter);
    }
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  @SneakyThrows
  public List<UserModel> listUserConnections(String username, Integer offset, Integer limit) {
    ListAccess<Identity> listAccess = relationshipManager.getConnections(requireIdentity(username));
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  public String getConnectionStatus(String username) {
    Relationship.Type type = relationshipManager.getStatus(me(), requireIdentity(username));
    return type == null ? "NOT_CONNECTED" : type.name();
  }

  public List<UserModel> listConnectionSuggestions(Integer limit) {
    Map<Identity, Integer> suggestions = relationshipManager.getSuggestions(me(), 0, 0, getInteger(limit, DEFAULT_LIMIT));
    return suggestions.keySet()
                      .stream()
                      .map(Identity::getRemoteId)
                      .map(this::user)
                      .toList();
  }

  @SneakyThrows
  public void sendConnectionRequest(String username) {
    Identity target = requireIdentity(username);
    Identity currentIdentity = me();
    if (StringUtils.equals(target.getRemoteId(), currentIdentity.getRemoteId())) {
      throw new IllegalArgumentException("You can't send a connection request to yourself.");
    }
    Relationship.Type status = relationshipManager.getStatus(currentIdentity, target);
    if (status == Relationship.Type.CONFIRMED) {
      throw new IllegalStateException("You are already connected with '%s'.".formatted(username));
    } else if (status == Relationship.Type.PENDING) {
      throw new IllegalStateException("A connection request with '%s' is already pending.".formatted(username));
    }
    relationshipManager.inviteToConnect(currentIdentity, target);
  }

  @SneakyThrows
  public void acceptConnectionRequest(String username) {
    Identity sender = requireIdentity(username);
    Identity currentIdentity = me();
    Relationship relationship = relationshipManager.get(sender, currentIdentity);
    if (relationship == null || relationship.getStatus() != Relationship.Type.PENDING) {
      throw new IllegalStateException("There is no pending connection request from '%s'. Use list_connection_requests to see incoming requests.".formatted(username));
    }
    relationshipManager.confirm(currentIdentity, sender);
  }

  @SneakyThrows
  public void refuseConnectionRequest(String username) {
    Identity sender = requireIdentity(username);
    Identity currentIdentity = me();
    Relationship relationship = relationshipManager.get(sender, currentIdentity);
    if (relationship == null || relationship.getStatus() != Relationship.Type.PENDING) {
      throw new IllegalStateException("There is no pending connection request from '%s'.".formatted(username));
    }
    relationshipManager.deny(currentIdentity, sender);
  }

  @SneakyThrows
  public void removeConnection(String username) {
    Identity other = requireIdentity(username);
    Relationship relationship = relationshipManager.get(me(), other);
    if (relationship == null) {
      throw new IllegalStateException("You have no relationship with '%s' to remove.".formatted(username));
    }
    relationshipManager.delete(relationship);
  }

  public UserModel updateMyProfile(String aboutMe, // NOSONAR
                                   String position,
                                   String company,
                                   String department,
                                   String team,
                                   String city,
                                   String country) {
    String username = getCurrentUserName();
    Identity identity = identityManager.getOrCreateUserIdentity(username);
    Profile profile = identityManager.getProfile(identity);
    boolean changed = setIfPresent(profile, Profile.ABOUT_ME, aboutMe);
    changed = setIfPresent(profile, Profile.POSITION, position) || changed;
    changed = setIfPresent(profile, Profile.COMPANY, company) || changed;
    changed = setIfPresent(profile, Profile.DEPARTMENT, department) || changed;
    changed = setIfPresent(profile, Profile.TEAM, team) || changed;
    changed = setIfPresent(profile, Profile.CITY, city) || changed;
    changed = setIfPresent(profile, Profile.COUNTRY, country) || changed;
    if (!changed) {
      throw new IllegalArgumentException("No profile field provided to update. Provide at least one of: about_me, position, company, department, team, city, country.");
    }
    identityManager.updateProfile(profile, username, true);
    return user(username);
  }

  @SuppressWarnings("deprecation") // UserStateModel.getLastActivity has no non-deprecated replacement yet
  public OnlineStatusModel getUserOnlineStatus(String username) {
    Identity identity = requireIdentity(username);
    String remoteId = identity.getRemoteId();
    boolean online = userStateService.isOnline(remoteId);
    UserStateModel state = userStateService.getUserState(remoteId);
    return new OnlineStatusModel(remoteId,
                                 online,
                                 state == null ? null : state.getStatus(),
                                 state == null || state.getLastActivity() <= 0 ? null : formatDate(state.getLastActivity()));
  }

  @SuppressWarnings("deprecation") // UserStateModel.getLastActivity has no non-deprecated replacement yet
  public List<OnlineStatusModel> listOnlineUsers(Integer offset, Integer limit) {
    List<UserStateModel> online = userStateService.online();
    if (online == null) {
      return Collections.emptyList();
    }
    return online.stream()
                 .skip(getInteger(offset, DEFAULT_OFFSET))
                 .limit(getInteger(limit, DEFAULT_LIMIT))
                 .map(state -> new OnlineStatusModel(state.getUserId(),
                                                     true,
                                                     state.getStatus(),
                                                     state.getLastActivity() <= 0 ? null : formatDate(state.getLastActivity())))
                 .toList();
  }

  private Identity me() {
    return identityManager.getOrCreateUserIdentity(getCurrentUserName());
  }

  private Identity requireIdentity(String username) {
    if (StringUtils.isBlank(username)) {
      throw new IllegalArgumentException("'username' is mandatory");
    }
    String remoteId = username.startsWith("@") ? username.substring(1) : username;
    Identity identity = identityManager.getOrCreateUserIdentity(remoteId);
    if (identity == null) {
      throw new IllegalArgumentException("User '%s' doesn't exist. Use search_users to find a valid username.".formatted(username));
    }
    return identity;
  }

  private UserModel user(String username) {
    String viewer = getCurrentUserName();
    return toUserModel(identityManager,
                       profilePropertyService,
                       userAcl,
                       translationService,
                       portalConfigService,
                       username,
                       viewer,
                       getCurrentUserLocale(viewer),
                       false);
  }

  private List<UserModel> toUserModels(Identity[] identities) {
    if (ArrayUtils.isEmpty(identities)) {
      return Collections.emptyList();
    }
    return Stream.of(identities)
                 .map(Identity::getRemoteId)
                 .map(this::user)
                 .toList();
  }

  private boolean setIfPresent(Profile profile, String propertyName, String value) {
    if (value == null) {
      return false;
    }
    profile.setProperty(propertyName, value);
    return true;
  }

}

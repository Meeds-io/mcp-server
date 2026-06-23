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

import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceModel;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceRegistration;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceVisibility;
import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;
import static io.meeds.mcp.server.tool.util.UserToolUtils.getUserIdentities;
import static io.meeds.mcp.server.tool.util.UserToolUtils.toUserModel;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.space.SpaceException;
import org.exoplatform.social.core.space.SpaceFilter;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.social.space.constant.SpaceRegistration;
import io.meeds.social.space.constant.SpaceVisibility;
import io.meeds.social.translation.service.TranslationService;

import lombok.SneakyThrows;

@Service
public class SpaceMcpTool implements McpToolPlugin {

  private static final String     MSG_SPACE_DOESN_T_EXIST  = "Space with Id '%s' doesn't exist";

  private static final String     MSG_SPACE_USER_NOT_ADMIN = "Space with Id '%s' can't be managed by current user";

  @Autowired
  private SpaceService            spaceService;

  @Autowired
  private TranslationService      translationService;

  @Autowired
  private IdentityManager         identityManager;

  @Autowired
  private ProfilePropertyService  profilePropertyService;

  @Autowired
  private UserACL                 userAcl;

  @Autowired
  private UserPortalConfigService portalConfigService;

  public SpaceModel createSpace(long spaceTemplateId,
                                String name,
                                String description,
                                Visibility visibility,
                                Registration registration,
                                List<String> usernamesToInvite) throws SpaceException {
    Space spaceToCreate = new Space();
    spaceToCreate.setTemplateId(spaceTemplateId);
    spaceToCreate.setDisplayName(name);
    spaceToCreate.setDescription(description);
    SpaceVisibility spaceVisibility = toSpaceVisibility(visibility);
    spaceToCreate.setVisibility(spaceVisibility == null ? null : spaceVisibility.name().toLowerCase());
    SpaceRegistration spaceRegistration = toSpaceRegistration(registration);
    spaceToCreate.setRegistration(spaceRegistration == null ? null : spaceRegistration.name().toLowerCase());
    String currentUsername = getCurrentUserName();
    Space createdSpace = spaceService.createSpace(spaceToCreate,
                                                  currentUsername,
                                                  getUserIdentities(identityManager, usernamesToInvite));
    return toSpaceModel(spaceService, createdSpace, currentUsername);
  }

  @SneakyThrows
  public SpaceModel updateSpaceSettings(long spaceId,
                                        String name,
                                        String description,
                                        Visibility visibility,
                                        Registration registration) {
    Space spaceToUpdate = spaceService.getSpaceById(spaceId);
    if (spaceToUpdate == null) {
      throw new ObjectNotFoundException("Space with id '%s' doesn't exists. Use 'get_managed_spaces' (using 'query' param for searched keyword) Tool to retrieve a space which the user will be able to update.");
    } else if (!spaceService.canManageSpace(spaceToUpdate, getCurrentUserName())) {
      throw new IllegalAccessException("Space with id '%s' isn't editable by current user.");
    }
    if (StringUtils.isNotBlank(name)) {
      spaceToUpdate.setDisplayName(name);
    }
    if (StringUtils.isNotBlank(description)) {
      spaceToUpdate.setDescription(description);
    }
    SpaceVisibility spaceVisibility = toSpaceVisibility(visibility);
    if (spaceVisibility != null) {
      spaceToUpdate.setVisibility(spaceVisibility.name().toLowerCase());
    }
    SpaceRegistration spaceRegistration = toSpaceRegistration(registration);
    if (spaceRegistration != null) {
      spaceToUpdate.setRegistration(spaceRegistration.name().toLowerCase());
    }
    String currentUsername = getCurrentUserName();
    Space updatedSpace = spaceService.updateSpace(spaceToUpdate);
    return toSpaceModel(spaceService, updatedSpace, currentUsername);
  }

  @SneakyThrows
  public List<SpaceModel> getAllSpaces(String query,
                                       Integer offset,
                                       Integer limit) {

    String currentUsername = getCurrentUserName();
    ListAccess<Space> spacesListAccess = spaceService.getAccessibleSpacesByFilter(currentUsername, new SpaceFilter(query));
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT));
    return spaces == null ? Collections.emptyList() :
                          Stream.of(spaces)
                                .map(s -> toSpaceModel(spaceService, s, currentUsername))
                                .toList();
  }

  @SneakyThrows
  public List<SpaceModel> getMySpaces(String query,
                                      Integer offset,
                                      Integer limit) {
    String currentUsername = getCurrentUserName();
    ListAccess<Space> spacesListAccess = spaceService.getMemberSpacesByFilter(currentUsername, new SpaceFilter(query));
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT));
    return spaces == null ? Collections.emptyList() :
                          Stream.of(spaces)
                                .map(s -> toSpaceModel(spaceService, s, currentUsername))
                                .toList();
  }

  @SneakyThrows
  public long countMySpaces(String query) {
    String currentUsername = getCurrentUserName();
    ListAccess<Space> spacesListAccess = spaceService.getMemberSpacesByFilter(currentUsername, new SpaceFilter(query));
    return spacesListAccess.getSize();
  }

  @SneakyThrows
  public List<SpaceModel> getManagedSpaces(String query,
                                           Integer offset,
                                           Integer limit) {
    String currentUsername = getCurrentUserName();
    ListAccess<Space> spacesListAccess = spaceService.getManagerSpacesByFilter(currentUsername, new SpaceFilter(query));
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT));
    return spaces == null ? Collections.emptyList() :
                          Stream.of(spaces)
                                .map(s -> toSpaceModel(spaceService, s, currentUsername))
                                .toList();
  }

  @SneakyThrows
  public SpaceModel getSpaceById(long spaceId) {
    String currentUsername = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null
        || (Space.HIDDEN.equals(space.getVisibility())
            && Space.CLOSED.equals(space.getRegistration())
            && !spaceService.isInvitedUser(space, currentUsername)
            && !spaceService.canViewSpace(space, currentUsername))) {
      return null;
    } else {
      return toSpaceModel(spaceService, space, currentUsername);
    }
  }

  public List<UserModel> listUsersOfSpaceByRole(long spaceId,
                                                SpaceRole spaceRole,
                                                Integer offset,
                                                Integer limit) {
    String currentUsername = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space != null && !(Space.HIDDEN.equals(space.getVisibility()) && Space.CLOSED.equals(space.getRegistration())
        && !spaceService.isInvitedUser(space, currentUsername)) || spaceService.canViewSpace(space, currentUsername)) {
      String[] usernames = switch (spaceRole) {
      case MANAGER:
        yield space.getManagers();
      case REDACTOR:
        yield space.getRedactors();
      case PUBLISHER:
        yield space.getPublishers();
      case INVITED:
        yield space.getInvitedUsers();
      case PENDING:
        yield space.getPendingUsers();
      default:
        yield space.getMembers();
      };
      if (ArrayUtils.isNotEmpty(usernames)) {
        return Stream.of(usernames)
                     .skip(getInteger(offset, DEFAULT_OFFSET))
                     .limit(getInteger(limit, DEFAULT_LIMIT))
                     .map(u -> toUserModel(identityManager,
                                           profilePropertyService,
                                           userAcl,
                                           translationService,
                                           portalConfigService,
                                           u,
                                           currentUsername,
                                           getLocale(null),
                                           false))
                     .filter(Objects::nonNull)
                     .toList();
      }
    }
    return Collections.emptyList();
  }

  public void inviteUsersToSpace(long spaceId,
                                 List<String> usernamesToInvite) {
    String currentUsername = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new IllegalStateException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, currentUsername)) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    } else if (CollectionUtils.isNotEmpty(usernamesToInvite)) {
      usernamesToInvite.forEach(u -> spaceService.addInvitedUser(space, u.startsWith("@") ? u.substring(1) : u));
    }
  }

  public void removeUsersFromSpace(long spaceId,
                                   List<String> usernamesToRemove) {
    String currentUsername = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new IllegalStateException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, currentUsername)) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    } else if (CollectionUtils.isNotEmpty(usernamesToRemove)) {
      usernamesToRemove.forEach(u -> spaceService.removeMember(space, u.startsWith("@") ? u.substring(1) : u));
    }
  }

  public void setUserRoleInSpace(long spaceId,
                                 String username,
                                 SpaceRole spaceRole) {
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new IllegalStateException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, getCurrentUserName())) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    } else if (!spaceService.isMember(space, username)) {
      throw new IllegalStateException("User '%s' isn't member of the space '%s' yet. Invite it before changing its role inside the space.".formatted(username,
                                                                                                                                                     spaceId));
    }
    switch (spaceRole) {
    case MANAGER: {
      spaceService.setManager(space, username, true);
      break;
    }
    case REDACTOR: {
      spaceService.addRedactor(space, username);
      break;
    }
    case PUBLISHER: {
      spaceService.addPublisher(space, username);
      break;
    }
    default:
      throw new IllegalArgumentException("Unexpected value: " + spaceRole);
    }
  }

  public void removeUserRoleInSpace(long spaceId,
                                    String username,
                                    SpaceRole spaceRole) {
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new IllegalStateException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, getCurrentUserName())) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    } else if (!spaceService.isMember(space, username)) {
      throw new IllegalStateException("User '%s' isn't member of the space '%s' yet, thus it doesn't have any role inside it yet.".formatted(username,
                                                                                                                                             spaceId));
    }
    switch (spaceRole) {
    case MANAGER: {
      spaceService.setManager(space, username, false);
      break;
    }
    case REDACTOR: {
      spaceService.removeRedactor(space, username);
      break;
    }
    case PUBLISHER: {
      spaceService.removePublisher(space, username);
      break;
    }
    default:
      throw new IllegalArgumentException("Unexpected value: " + spaceRole);
    }
  }

}

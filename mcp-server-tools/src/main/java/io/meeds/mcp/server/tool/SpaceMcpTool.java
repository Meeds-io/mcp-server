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

import static io.meeds.mcp.server.tool.util.SpaceToolUtils.getUserRoles;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceModel;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceRegistration;
import static io.meeds.mcp.server.tool.util.SpaceToolUtils.toSpaceVisibility;
import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;
import static io.meeds.mcp.server.tool.util.UserToolUtils.getUserIdentities;
import static io.meeds.mcp.server.tool.util.UserToolUtils.toUserModel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.attachment.AttachmentService;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.model.AvatarAttachment;
import org.exoplatform.social.core.model.BannerAttachment;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.space.SpaceException;
import org.exoplatform.social.core.space.SpaceFilter;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.SpaceMemberModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.util.UploadToolUtils;
import io.meeds.social.space.constant.SpaceRegistration;
import io.meeds.social.space.constant.SpaceVisibility;
import io.meeds.social.space.template.model.SpaceTemplate;
import io.meeds.social.space.template.service.SpaceTemplateService;
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

  @Autowired
  private SpaceTemplateService    spaceTemplateService;

  @Autowired
  private AttachmentService       attachmentService;

  @Autowired
  private FileService              fileService;

  public SpaceModel createSpace(long spaceTemplateId, // NOSONAR
                                String name,
                                String description,
                                Visibility visibility,
                                Registration registration,
                                List<String> usernamesToInvite,
                                Long parentSpaceId) throws SpaceException {
    Space spaceToCreate = new Space();
    spaceToCreate.setTemplateId(spaceTemplateId);
    spaceToCreate.setDisplayName(name);
    spaceToCreate.setDescription(description);
    SpaceVisibility spaceVisibility = toSpaceVisibility(visibility);
    spaceToCreate.setVisibility(spaceVisibility == null ? null : spaceVisibility.name().toLowerCase());
    SpaceRegistration spaceRegistration = toSpaceRegistration(registration);
    spaceToCreate.setRegistration(spaceRegistration == null ? null : spaceRegistration.name().toLowerCase());
    String currentUsername = getCurrentUserName();
    Space createdSpace;
    if (parentSpaceId != null && parentSpaceId > 0) {
      Space parentSpace = spaceService.getSpaceById(parentSpaceId);
      if (parentSpace == null) {
        throw new IllegalArgumentException("Parent space with id '%s' doesn't exist. Use get_my_spaces or get_all_spaces to find a valid parent space.".formatted(parentSpaceId));
      }
      checkSubspaceAllowed(parentSpace, spaceTemplateId);
      createdSpace = spaceService.createSpace(spaceToCreate,
                                              currentUsername,
                                              getUserIdentities(identityManager, usernamesToInvite),
                                              parentSpaceId);
    } else {
      createdSpace = spaceService.createSpace(spaceToCreate,
                                              currentUsername,
                                              getUserIdentities(identityManager, usernamesToInvite));
    }
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
    if (space != null
        && (!Space.HIDDEN.equals(space.getVisibility())
            || spaceService.canViewSpace(space, currentUsername))) {
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

  public SpaceModel joinSpace(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (spaceService.isMember(space, currentUsername)) {
      throw new IllegalStateException("You are already a member of space '%s'.".formatted(space.getDisplayName()));
    }
    if (!Space.OPEN.equals(space.getRegistration())) {
      throw new IllegalStateException("Space '%s' can't be joined directly. If it requires validation use request_to_join_space, otherwise you need an invitation.".formatted(space.getDisplayName()));
    }
    spaceService.addMember(space, currentUsername);
    return toSpaceModel(spaceService, space, currentUsername);
  }

  // Sets a space's avatar from an image provided as exactly one of an http(s)
  // URL, a base64 string, or an ACL-checked reference to a readable platform
  // attachment. Only a manager of the space may change it.
  public SpaceModel setSpaceAvatar(long spaceId,
                                   String imageUrl,
                                   String imageBase64,
                                   String attachmentObjectType,
                                   String attachmentObjectId) throws IllegalAccessException, ObjectNotFoundException {
    return setSpaceImage(spaceId, true, imageUrl, imageBase64, attachmentObjectType, attachmentObjectId);
  }

  // Sets a space's banner from an image provided as exactly one of an http(s)
  // URL, a base64 string, or an ACL-checked reference to a readable platform
  // attachment. Only a manager of the space may change it.
  public SpaceModel setSpaceBanner(long spaceId,
                                   String imageUrl,
                                   String imageBase64,
                                   String attachmentObjectType,
                                   String attachmentObjectId) throws IllegalAccessException, ObjectNotFoundException {
    return setSpaceImage(spaceId, false, imageUrl, imageBase64, attachmentObjectType, attachmentObjectId);
  }

  public SpaceModel requestToJoinSpace(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (spaceService.isMember(space, currentUsername)) {
      throw new IllegalStateException("You are already a member of space '%s'.".formatted(space.getDisplayName()));
    } else if (spaceService.isPendingUser(space, currentUsername)) {
      throw new IllegalStateException("You already have a pending join request for space '%s'.".formatted(space.getDisplayName()));
    }
    if (Space.OPEN.equals(space.getRegistration())) {
      spaceService.addMember(space, currentUsername);
    } else if (Space.VALIDATION.equals(space.getRegistration())) {
      spaceService.addPendingUser(space, currentUsername);
    } else {
      throw new IllegalStateException("Space '%s' is invitation-only; you can't request to join it.".formatted(space.getDisplayName()));
    }
    return toSpaceModel(spaceService, space, currentUsername);
  }

  public SpaceModel cancelJoinRequest(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.isPendingUser(space, currentUsername)) {
      throw new IllegalStateException("You have no pending join request for space '%s'.".formatted(space.getDisplayName()));
    }
    spaceService.removePendingUser(space, currentUsername);
    return toSpaceModel(spaceService, space, currentUsername);
  }

  public SpaceModel leaveSpace(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.isMember(space, currentUsername)) {
      throw new IllegalStateException("You are not a member of space '%s'.".formatted(space.getDisplayName()));
    }
    if (spaceService.isManager(space, currentUsername) && space.getManagers().length <= 1) {
      throw new IllegalStateException("You are the only manager of space '%s'. Promote another member to manager before leaving.".formatted(space.getDisplayName()));
    }
    spaceService.removeMember(space, currentUsername);
    return toSpaceModel(spaceService, space, currentUsername);
  }

  public SpaceModel acceptSpaceInvitation(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.isInvitedUser(space, currentUsername)) {
      throw new IllegalStateException("You have no invitation to space '%s'.".formatted(space.getDisplayName()));
    }
    spaceService.addMember(space, currentUsername);
    return toSpaceModel(spaceService, space, currentUsername);
  }

  public SpaceModel declineSpaceInvitation(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.isInvitedUser(space, currentUsername)) {
      throw new IllegalStateException("You have no invitation to space '%s'.".formatted(space.getDisplayName()));
    }
    spaceService.removeInvitedUser(space, currentUsername);
    return toSpaceModel(spaceService, space, currentUsername);
  }

  public List<UserModel> listSpaceJoinRequests(long spaceId,
                                               Integer offset,
                                               Integer limit) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.canManageSpace(space, currentUsername)) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    }
    String[] pendingUsers = space.getPendingUsers();
    if (ArrayUtils.isEmpty(pendingUsers)) {
      return Collections.emptyList();
    }
    return Stream.of(pendingUsers)
                 .skip(getInteger(offset, DEFAULT_OFFSET))
                 .limit(getInteger(limit, DEFAULT_LIMIT))
                 .map(u -> user(u, currentUsername))
                 .filter(Objects::nonNull)
                 .toList();
  }

  public void acceptSpaceJoinRequest(long spaceId, String username) throws ObjectNotFoundException {
    Space space = manageableSpace(spaceId);
    String target = normalize(username);
    if (!spaceService.isPendingUser(space, target)) {
      throw new IllegalStateException("User '%s' has no pending join request for space '%s'.".formatted(username, space.getDisplayName()));
    }
    spaceService.addMember(space, target);
  }

  public void declineSpaceJoinRequest(long spaceId, String username) throws ObjectNotFoundException {
    Space space = manageableSpace(spaceId);
    String target = normalize(username);
    if (!spaceService.isPendingUser(space, target)) {
      throw new IllegalStateException("User '%s' has no pending join request for space '%s'.".formatted(username, space.getDisplayName()));
    }
    spaceService.removePendingUser(space, target);
  }

  public List<SpaceMemberModel> getSpaceMembers(long spaceId,
                                                Integer offset,
                                                Integer limit) throws ObjectNotFoundException, IllegalAccessException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (Space.HIDDEN.equals(space.getVisibility()) && !spaceService.canViewSpace(space, currentUsername)) {
      throw new IllegalAccessException("You can't view members of space '%s'.".formatted(spaceId));
    }
    String[] members = space.getMembers();
    if (ArrayUtils.isEmpty(members)) {
      return Collections.emptyList();
    }
    return Stream.of(members)
                 .skip(getInteger(offset, DEFAULT_OFFSET))
                 .limit(getInteger(limit, DEFAULT_LIMIT))
                 .map(u -> new SpaceMemberModel(user(u, currentUsername), getUserRoles(spaceService, space, u)))
                 .filter(m -> m.user() != null)
                 .toList();
  }

  @SneakyThrows
  public List<SpaceModel> listSubSpaces(long parentSpaceId,
                                        Integer offset,
                                        Integer limit) throws ObjectNotFoundException, IllegalAccessException {
    String currentUsername = getCurrentUserName();
    Space parentSpace = requireSpace(parentSpaceId);
    if (Space.HIDDEN.equals(parentSpace.getVisibility()) && !spaceService.canViewSpace(parentSpace, currentUsername)) {
      throw new IllegalAccessException("You can't view space '%s'.".formatted(parentSpaceId));
    }
    SpaceFilter filter = new SpaceFilter((String) null);
    filter.setParentSpaceId(parentSpaceId);
    ListAccess<Space> spacesListAccess = spaceService.getAccessibleSpacesByFilter(currentUsername, filter);
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT));
    return spaces == null ? Collections.emptyList()
                          : Stream.of(spaces)
                                  .map(s -> toSpaceModel(spaceService, s, currentUsername))
                                  .toList();
  }

  public void deleteSpace(long spaceId) throws ObjectNotFoundException, SpaceException {
    Space space = manageableSpace(spaceId);
    spaceService.deleteSpace(space);
  }

  private SpaceModel setSpaceImage(long spaceId,
                                   boolean avatar,
                                   String imageUrl,
                                   String imageBase64,
                                   String attachmentObjectType,
                                   String attachmentObjectId) throws IllegalAccessException, ObjectNotFoundException {
    String username = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new ObjectNotFoundException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, username)) {
      throw new IllegalAccessException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    }
    UploadToolUtils.FetchedContent image = UploadToolUtils.resolveImage(attachmentService,
                                                                      fileService,
                                                                      getCurrentUserAclIdentity(),
                                                                      imageUrl,
                                                                      imageBase64,
                                                                      attachmentObjectType,
                                                                      attachmentObjectId,
                                                                      UploadToolUtils.DEFAULT_MAX_BYTES);
    try (InputStream inputStream = new ByteArrayInputStream(image.bytes())) {
      if (avatar) {
        space.setAvatarAttachment(new AvatarAttachment(null,
                                                       image.fileName(),
                                                       image.mimeType(),
                                                       inputStream,
                                                       System.currentTimeMillis()));
        space = spaceService.updateSpaceAvatar(space, username);
      } else {
        space.setBannerAttachment(new BannerAttachment(null,
                                                       image.fileName(),
                                                       image.mimeType(),
                                                       inputStream,
                                                       System.currentTimeMillis()));
        space = spaceService.updateSpaceBanner(space, username);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not update the space image: " + e.getMessage());
    }
    return toSpaceModel(spaceService, space, username);
  }

  private Space requireSpace(long spaceId) throws ObjectNotFoundException {
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new ObjectNotFoundException("Space with id '%s' doesn't exist. Use get_all_spaces (with 'query') to find a space by name.".formatted(spaceId));
    }
    return space;
  }

  /**
   * Verifies a sub-space with the given child template can actually be created
   * under the parent space, since the platform silently allows creation when the
   * parent template declares no sub-space templates. Per-template and global
   * subspace count limits are NOT re-checked here: SpaceService#createSpace(...,
   * parentSpaceId) already enforces both (SpaceTemplate#getAllowedSubspaceTemplates
   * rule limits and #getSubspacesMaxLimit) and throws SpaceException on its own.
   */
  private void checkSubspaceAllowed(Space parentSpace, long childTemplateId) {
    SpaceTemplate parentTemplate = spaceTemplateService.getSpaceTemplate(parentSpace.getTemplateId());
    List<String> allowedSubspaceTemplates = parentTemplate == null ? null : parentTemplate.getAllowedSubspaceTemplates();
    if (allowedSubspaceTemplates == null || allowedSubspaceTemplates.isEmpty()) {
      throw new IllegalStateException("Space '%s' doesn't allow sub-spaces: its template (id %s) declares no allowed sub-space templates.".formatted(parentSpace.getDisplayName(),
                                                                                                                                                    parentSpace.getTemplateId()));
    }
    // rules are formatted as "<childTemplateId>:<maxLimit>"
    boolean childAllowed = allowedSubspaceTemplates.stream()
                                                   .filter(Objects::nonNull)
                                                   .anyMatch(rule -> rule.startsWith(childTemplateId + ":"));
    if (!childAllowed) {
      List<String> allowedTemplateIds = allowedSubspaceTemplates.stream()
                                                                .filter(Objects::nonNull)
                                                                .map(rule -> rule.split(":")[0])
                                                                .toList();
      throw new IllegalArgumentException("Template '%s' can't be used to create a sub-space under '%s'. Allowed sub-space template ids: %s. Use list_space_templates to pick a valid one.".formatted(childTemplateId,
                                                                                                                                                                                                    parentSpace.getDisplayName(),
                                                                                                                                                                                                    allowedTemplateIds));
    }
  }

  private Space manageableSpace(long spaceId) throws ObjectNotFoundException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.canManageSpace(space, currentUsername)) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    }
    return space;
  }

  private String normalize(String username) {
    return username != null && username.startsWith("@") ? username.substring(1) : username;
  }

  private UserModel user(String username, String viewer) {
    return toUserModel(identityManager,
                       profilePropertyService,
                       userAcl,
                       translationService,
                       portalConfigService,
                       normalize(username),
                       viewer,
                       getLocale(null),
                       false);
  }

}

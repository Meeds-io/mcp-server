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
import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.clampLimit;
import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;
import static io.meeds.mcp.server.tool.util.UserToolUtils.getUserIdentities;
import static io.meeds.mcp.server.tool.util.UserToolUtils.toUserModel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
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
import org.exoplatform.upload.UploadService;

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

  private static final String     MSG_SPACE_ONLY_MANAGER   = "User '%s' is the only manager of space '%s'. Promote another member to manager first.";

  private static final String     MSG_SPACE_ALL_MANAGERS   = "Removing these users would leave space '%s' without a manager. Promote another member to manager first.";

  private static final String     MSG_SPACE_USER_NOT_DELETER = "Space with Id '%s' can't be deleted by current user";

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
  private FileService             fileService;

  @Autowired
  private UploadService           uploadService;

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

  /**
   * Updates the settings of a space the current user manages. The current user
   * is the editor of the update, as in the Social REST endpoint.
   *
   * @param spaceId the space
   * @param name the new display name, ignored when blank
   * @param description the new description, ignored when blank
   * @param visibility the new visibility, ignored when null
   * @param registration the new registration, ignored when null
   * @return the updated space
   */
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
    spaceToUpdate.setEditor(currentUsername);
    Space updatedSpace = spaceService.updateSpace(spaceToUpdate);
    return toSpaceModel(spaceService, updatedSpace, currentUsername);
  }

  @SneakyThrows
  public List<SpaceModel> getAllSpaces(String query,
                                       Integer offset,
                                       Integer limit) {

    String currentUsername = getCurrentUserName();
    ListAccess<Space> spacesListAccess = spaceService.getAccessibleSpacesByFilter(currentUsername, new SpaceFilter(query));
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), clampLimit(limit, DEFAULT_LIMIT));
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
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), clampLimit(limit, DEFAULT_LIMIT));
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
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), clampLimit(limit, DEFAULT_LIMIT));
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
                     .limit(clampLimit(limit, DEFAULT_LIMIT))
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

  /**
   * Removes users from a space the current user manages. Refused before any
   * removal when one of them is the space's only manager, or when the list
   * holds every manager; each user is then checked again against the space as
   * stored, right before its removal, so that a change made elsewhere
   * meanwhile never leaves the space without a manager. A refusal at that
   * point leaves the users removed before it removed. The current user is the
   * editor of each removal, as in the Social REST endpoint.
   *
   * @param spaceId the space
   * @param usernamesToRemove the users, possibly prefixed with "@"
   * @throws IllegalStateException when the space doesn't exist, the current
   *           user can't manage it, or a removal would leave it without a
   *           manager
   */
  public void removeUsersFromSpace(long spaceId,
                                   List<String> usernamesToRemove) {
    String currentUsername = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new IllegalStateException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, currentUsername)) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    } else if (CollectionUtils.isNotEmpty(usernamesToRemove)) {
      List<String> usernames = usernamesToRemove.stream().map(this::normalize).distinct().toList();
      usernames.forEach(u -> checkNotOnlyManager(space, u));
      checkKeepsAManager(space, usernames);
      // the managers may change elsewhere while the list is removed: each
      // user is checked against the space as stored right before its removal
      usernames.forEach(u -> {
        Space current = spaceService.getSpaceById(spaceId);
        if (current == null) {
          throw new IllegalStateException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
        }
        checkNotOnlyManager(current, u);
        current.setEditor(currentUsername);
        spaceService.removeMember(current, u);
      });
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

  /**
   * Removes a role of a member of a space the current user manages. The
   * current user is the editor of the change, as in the Social REST endpoint.
   *
   * @param spaceId the space
   * @param username the member
   * @param spaceRole the role to remove
   * @throws IllegalStateException when the space doesn't exist, the current
   *           user can't manage it, the user isn't a member of it, or the role
   *           is the manager one of its only manager
   */
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
    space.setEditor(getCurrentUserName());
    switch (spaceRole) {
    case MANAGER: {
      checkNotOnlyManager(space, username);
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

  /**
   * Sets a space's avatar from an image provided as exactly one of an http(s)
   * URL, a base64 string, an ACL-checked reference to a readable platform
   * attachment, or the upload id of a file the current user sent with
   * {@code start_upload}. Only a manager of the space may change it.
   *
   * @param spaceId the space
   * @param imageUrl the public http(s) URL of the image, or null
   * @param imageBase64 the base64 bytes of the image, or null
   * @param attachmentObjectType the type of the object holding an attachment
   *          to use, or null
   * @param attachmentObjectId the id of the object holding an attachment to
   *          use, or null
   * @param uploadId the upload id of an image the current user uploaded, or
   *          null; consumed by the call
   * @return the updated space
   * @throws IllegalAccessException when the user does not manage the space, or
   *           may not read the attachment
   * @throws ObjectNotFoundException when the space, the attachment or the
   *           upload does not exist
   */
  public SpaceModel setSpaceAvatar(long spaceId,
                                   String imageUrl,
                                   String imageBase64,
                                   String attachmentObjectType,
                                   String attachmentObjectId,
                                   String uploadId) throws IllegalAccessException, ObjectNotFoundException {
    return setSpaceImage(spaceId,
                         true,
                         new UploadToolUtils.ImageSource(imageUrl, imageBase64, attachmentObjectType, attachmentObjectId, uploadId));
  }

  /**
   * Sets a space's banner from an image provided as exactly one of an http(s)
   * URL, a base64 string, an ACL-checked reference to a readable platform
   * attachment, or the upload id of a file the current user sent with
   * {@code start_upload}. Only a manager of the space may change it.
   *
   * @param spaceId the space
   * @param imageUrl the public http(s) URL of the image, or null
   * @param imageBase64 the base64 bytes of the image, or null
   * @param attachmentObjectType the type of the object holding an attachment
   *          to use, or null
   * @param attachmentObjectId the id of the object holding an attachment to
   *          use, or null
   * @param uploadId the upload id of an image the current user uploaded, or
   *          null; consumed by the call
   * @return the updated space
   * @throws IllegalAccessException when the user does not manage the space, or
   *           may not read the attachment
   * @throws ObjectNotFoundException when the space, the attachment or the
   *           upload does not exist
   */
  public SpaceModel setSpaceBanner(long spaceId,
                                   String imageUrl,
                                   String imageBase64,
                                   String attachmentObjectType,
                                   String attachmentObjectId,
                                   String uploadId) throws IllegalAccessException, ObjectNotFoundException {
    return setSpaceImage(spaceId,
                         false,
                         new UploadToolUtils.ImageSource(imageUrl, imageBase64, attachmentObjectType, attachmentObjectId, uploadId));
  }

  // ---------------------------------------------------------------------------
  // Self-service membership (acting as the current user)
  // ---------------------------------------------------------------------------

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
                 .limit(clampLimit(limit, DEFAULT_LIMIT))
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
                 .limit(clampLimit(limit, DEFAULT_LIMIT))
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
    Space[] spaces = spacesListAccess.load(getInteger(offset, DEFAULT_OFFSET), clampLimit(limit, DEFAULT_LIMIT));
    return spaces == null ? Collections.emptyList()
                          : Stream.of(spaces)
                                  .map(s -> toSpaceModel(spaceService, s, currentUsername))
                                  .toList();
  }

  /**
   * Deletes a space the current user holds the delete permission on, with the
   * current user as the editor of the deletion, as the Social REST endpoint
   * does.
   *
   * @param spaceId technical id of the space
   * @throws ObjectNotFoundException when the space doesn't exist
   * @throws SpaceException when the space service refuses the deletion
   */
  public void deleteSpace(long spaceId) throws ObjectNotFoundException, SpaceException {
    String currentUsername = getCurrentUserName();
    Space space = requireSpace(spaceId);
    if (!spaceService.canDeleteSpace(space, currentUsername)) {
      throw new IllegalStateException(MSG_SPACE_USER_NOT_DELETER.formatted(spaceId));
    }
    space.setEditor(currentUsername);
    spaceService.deleteSpace(space);
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

  /**
   * Refuses a list of users to remove that holds every manager of the space:
   * none of them is the only manager, yet removing them all would leave the
   * space without one.
   *
   * @param space the space
   * @param usernames the users to remove, normalized
   * @throws IllegalStateException when the list holds every manager
   */
  private void checkKeepsAManager(Space space, List<String> usernames) {
    String[] managers = space.getManagers();
    if (managers != null && managers.length > 0 && usernames.containsAll(Arrays.asList(managers))) {
      throw new IllegalStateException(MSG_SPACE_ALL_MANAGERS.formatted(space.getDisplayName()));
    }
  }

  /**
   * Refuses to remove the only manager of a space from it, or the manager
   * role from them.
   *
   * @param space the space
   * @param username the user, normalized
   * @throws IllegalStateException when the user is the space's only manager
   */
  private void checkNotOnlyManager(Space space, String username) {
    if (spaceService.isOnlyManager(space, username)) {
      throw new IllegalStateException(MSG_SPACE_ONLY_MANAGER.formatted(username, space.getDisplayName()));
    }
  }

  /**
   * @param username a username, possibly prefixed with "@"
   * @return the username without its "@" prefix
   */
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

  /**
   * Sets the avatar or the banner of a space from the given image source, once
   * the current user is known to manage the space.
   *
   * @param spaceId the space
   * @param avatar true for the avatar, false for the banner
   * @param source the one source of the image
   * @return the updated space
   * @throws IllegalAccessException when the user does not manage the space, or
   *           may not read the attachment
   * @throws ObjectNotFoundException when the space, the attachment or the
   *           upload does not exist
   */
  private SpaceModel setSpaceImage(long spaceId,
                                   boolean avatar,
                                   UploadToolUtils.ImageSource source) throws IllegalAccessException, ObjectNotFoundException {
    String username = getCurrentUserName();
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new ObjectNotFoundException(MSG_SPACE_DOESN_T_EXIST.formatted(spaceId));
    } else if (!spaceService.canManageSpace(space, username)) {
      throw new IllegalAccessException(MSG_SPACE_USER_NOT_ADMIN.formatted(spaceId));
    }
    UploadToolUtils.FetchedContent image = UploadToolUtils.resolveImage(attachmentService,
                                                                      fileService,
                                                                      uploadService,
                                                                      getCurrentUserAclIdentity(),
                                                                      source,
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

}

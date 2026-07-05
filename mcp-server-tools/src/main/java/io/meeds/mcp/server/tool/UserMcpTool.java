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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.attachment.AttachmentService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.model.AvatarAttachment;
import org.exoplatform.social.core.model.BannerAttachment;
import org.exoplatform.social.core.profile.ProfileFilter;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.relationship.model.Relationship;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.OnlineStatusModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.util.UploadToolUtils;
import io.meeds.social.translation.service.TranslationService;

import lombok.SneakyThrows;

@Service
public class UserMcpTool implements McpToolPlugin {

  private IdentityManager         identityManager;

  private ProfilePropertyService  profilePropertyService;

  private UserACL                 userAcl;

  private TranslationService      translationService;

  private UserPortalConfigService portalConfigService;

  private AttachmentService        attachmentService;

  private FileService              fileService;

  public UserMcpTool(IdentityManager identityManager,
                          ProfilePropertyService profilePropertyService,
                          UserACL userAcl,
                          TranslationService translationService,
                          UserPortalConfigService portalConfigService,
                          AttachmentService attachmentService,
                          FileService fileService) {
    this.identityManager = identityManager;
    this.profilePropertyService = profilePropertyService;
    this.userAcl = userAcl;
    this.translationService = translationService;
    this.portalConfigService = portalConfigService;
    this.attachmentService = attachmentService;
    this.fileService = fileService;
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

  // Sets the current user's profile avatar from an image provided as exactly one
  // of an http(s) URL, a base64 string, or an ACL-checked reference to a file
  // already attached to a platform object. Acts as the current user, so the
  // avatar always belongs to the caller.
  public UserModel setMyAvatar(String imageUrl,
                               String imageBase64,
                               String attachmentObjectType,
                               String attachmentObjectId) throws IllegalAccessException, ObjectNotFoundException {
    return setProfileImage(Profile.AVATAR, imageUrl, imageBase64, attachmentObjectType, attachmentObjectId);
  }

  // Sets the current user's profile banner from an image provided as exactly one
  // of an http(s) URL, a base64 string, or an ACL-checked reference to a readable
  // platform attachment. Acts as the current user.
  public UserModel setMyBanner(String imageUrl,
                               String imageBase64,
                               String attachmentObjectType,
                               String attachmentObjectId) throws IllegalAccessException, ObjectNotFoundException {
    return setProfileImage(Profile.BANNER, imageUrl, imageBase64, attachmentObjectType, attachmentObjectId);
  }

  private UserModel setProfileImage(String property,
                                    String imageUrl,
                                    String imageBase64,
                                    String attachmentObjectType,
                                    String attachmentObjectId) throws IllegalAccessException, ObjectNotFoundException {
    String username = getCurrentUserName();
    UploadToolUtils.FetchedImage image = UploadToolUtils.resolveImage(attachmentService,
                                                                      fileService,
                                                                      getCurrentUserAclIdentity(),
                                                                      imageUrl,
                                                                      imageBase64,
                                                                      attachmentObjectType,
                                                                      attachmentObjectId,
                                                                      UploadToolUtils.DEFAULT_MAX_BYTES);
    Identity userIdentity = identityManager.getOrCreateUserIdentity(username);
    if (userIdentity == null || userIdentity.getProfile() == null) {
      throw new ObjectNotFoundException("No profile found for the current user.");
    }
    Profile profile = userIdentity.getProfile();
    try (InputStream inputStream = new ByteArrayInputStream(image.bytes())) {
      if (Profile.AVATAR.equals(property)) {
        profile.setProperty(Profile.AVATAR,
                            new AvatarAttachment(null,
                                                 image.fileName(),
                                                 image.mimeType(),
                                                 inputStream,
                                                 System.currentTimeMillis()));
        profile.setListUpdateTypes(List.of(Profile.UpdateType.AVATAR));
      } else {
        profile.setProperty(Profile.BANNER,
                            new BannerAttachment(null,
                                                 image.fileName(),
                                                 image.mimeType(),
                                                 inputStream,
                                                 System.currentTimeMillis()));
        profile.setListUpdateTypes(List.of(Profile.UpdateType.BANNER));
      }
      identityManager.updateProfile(profile, username, true);
    } catch (IOException e) {
      throw new IllegalStateException("Could not update the profile image: " + e.getMessage());
    }
    return getMyUserInformation();
  }

}

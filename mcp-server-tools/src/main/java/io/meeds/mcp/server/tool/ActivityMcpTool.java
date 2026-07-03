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
import static io.meeds.mcp.server.util.McpToolUtils.markdownToHtml;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.attachment.AttachmentService;
import org.exoplatform.social.attachment.model.UploadedAttachmentDetail;
import org.exoplatform.social.core.activity.ActivityFilter;
import org.exoplatform.social.core.activity.ActivityStreamType;
import org.exoplatform.social.core.activity.filter.ActivitySearchFilter;
import org.exoplatform.social.core.activity.model.ActivitySearchResult;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.activity.model.ExoSocialActivityImpl;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.jpa.search.ActivitySearchConnector;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.processor.I18NActivityProcessor;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.ActivityCommentModel;
import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.util.ActivityToolUtils;
import io.meeds.mcp.server.tool.util.UploadToolUtils;
import io.meeds.mcp.server.tool.util.UserToolUtils;
import io.meeds.portal.permlink.service.PermanentLinkService;
import io.meeds.social.translation.service.TranslationService;

import org.exoplatform.upload.UploadService;

import lombok.SneakyThrows;

@Service
public class ActivityMcpTool implements McpToolPlugin {

  private static final int        MAX_TO_LOAD              = 100;

  private static final String     ACTIVITY_ACCESS_DENIED   = "Activity with id '%s' isn't accessible for user";

  private static final String     ACTIVITY_NOT_FOUND       =
                                                     "Activity with id '%s' doesn't exists. Use Tool search_activities to find activities by content";

  private static final String     USER_ACCESS_SPACE_DENIED = "User %s can't access Space with id '%s'";

  private static final String     ACTIVITY_OBJECT_TYPE     = "activity";

  @Autowired
  private ActivityManager         activityManager;

  @Autowired
  private ActivitySearchConnector activitySearchConnector;

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
  private PermanentLinkService    permanentLinkService;

  @Autowired
  private I18NActivityProcessor   i18NActivityProcessor;

  @Autowired
  private AttachmentService       attachmentService;

  @Autowired
  private UploadService           uploadService;

  @SneakyThrows
  public List<ActivityModel> getActivitiesSinceDays(Long spaceId, Long days) throws IllegalAccessException, // NOSONAR
                                                                             ObjectNotFoundException {
    org.exoplatform.services.security.Identity currentUser = getCurrentUserAclIdentity();
    String currentUsername = currentUser.getUserId();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(currentUsername);
    ActivityFilter activityFilter = new ActivityFilter();
    if (spaceId != null && spaceId > 0) {
      Space space = spaceService.getSpaceById(spaceId);
      if (space == null) {
        throw new ObjectNotFoundException("Space with id %s doesn't exist. Use get_my_spaces to search for spaces by name".formatted(spaceId));
      } else if (!spaceService.canViewSpace(space, currentUsername)) {
        throw new IllegalAccessException(USER_ACCESS_SPACE_DENIED.formatted(currentUsername, spaceId));
      }
      Identity spaceIdentity = identityManager.getOrCreateSpaceIdentity(space.getPrettyName());
      activityFilter.setStreamType(ActivityStreamType.ANY_SPACE_ACTIVITY);
      activityFilter.setSpaceIdentityId(spaceIdentity.getIdentityId());
    } else {
      activityFilter.setStreamType(ActivityStreamType.ALL_STREAM);
    }
    long sinceTimestamp = LocalDate.now()
                                   .minusDays(days)
                                   .atStartOfDay(ZoneId.systemDefault())
                                   .toEpochSecond() *
        1000l;

    List<ActivityModel> result = new ArrayList<>();
    int offset = 0;
    int limit = 10;
    List<ExoSocialActivity> activities;
    do {
      List<String> activityIds = activityManager.getActivitiesByFilterWithListAccess(currentUserIdentity,
                                                                                     activityFilter)
                                                .loadIdsAsList(getInteger(offset, DEFAULT_OFFSET),
                                                               getInteger(limit, DEFAULT_LIMIT));
      if (CollectionUtils.isEmpty(activityIds)) {
        break;
      } else {
        activities = activityIds.stream()
                                .map(activityManager::getActivity)
                                .filter(a -> a.getUpdated() == null ? a.getPostedTime() > sinceTimestamp :
                                                                    a.getUpdated().getTime() > sinceTimestamp)
                                .toList();
        if (CollectionUtils.isNotEmpty(activities)) {
          activities.stream()
                    .map(ExoSocialActivity::getId)
                    .map(this::toActivityModel)
                    .forEach(result::add);
        }
        offset += limit;
      }
    } while (!activities.isEmpty() && offset < MAX_TO_LOAD);
    return result;
  }

  @SneakyThrows
  public List<ActivityModel> getActivities(Long spaceId,
                                           Integer offset,
                                           Integer limit) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity currentUser = getCurrentUserAclIdentity();
    String currentUsername = currentUser.getUserId();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(currentUsername);
    ActivityFilter activityFilter = new ActivityFilter();
    if (spaceId != null && spaceId > 0) {
      Space space = spaceService.getSpaceById(spaceId);
      if (space == null) {
        throw new ObjectNotFoundException("Space with id %s doesn't exist. Use get_my_spaces to search for spaces by name".formatted(spaceId));
      } else if (!spaceService.canViewSpace(space, currentUsername)) {
        throw new IllegalAccessException(USER_ACCESS_SPACE_DENIED.formatted(currentUsername, spaceId));
      }
      Identity spaceIdentity = identityManager.getOrCreateSpaceIdentity(space.getPrettyName());
      activityFilter.setStreamType(ActivityStreamType.ANY_SPACE_ACTIVITY);
      activityFilter.setSpaceIdentityId(spaceIdentity.getIdentityId());
    } else {
      activityFilter.setStreamType(ActivityStreamType.ALL_STREAM);
    }
    List<String> activityIds = activityManager.getActivitiesByFilterWithListAccess(currentUserIdentity,
                                                                                   activityFilter)
                                              .loadIdsAsList(getInteger(offset, DEFAULT_OFFSET),
                                                             getInteger(limit, DEFAULT_LIMIT));
    return activityIds.stream()
                      .map(this::toActivityModel)
                      .toList();
  }

  public List<ActivityModel> getUserActivityStream(String username,
                                                   Integer offset,
                                                   Integer limit) throws ObjectNotFoundException {
    org.exoplatform.services.security.Identity viewer = getCurrentUserAclIdentity();
    String targetUsername = username != null && username.startsWith("@") ? username.substring(1) : username;
    Identity ownerIdentity = identityManager.getOrCreateUserIdentity(targetUsername);
    if (ownerIdentity == null) {
      throw new ObjectNotFoundException("User '%s' doesn't exist. Use search_users to find a valid username.".formatted(username));
    }
    Identity viewerIdentity = identityManager.getOrCreateUserIdentity(viewer.getUserId());
    List<String> activityIds = activityManager.getActivitiesWithListAccess(ownerIdentity, viewerIdentity)
                                              .loadIdsAsList(getInteger(offset, DEFAULT_OFFSET),
                                                             getInteger(limit, DEFAULT_LIMIT));
    return activityIds.stream()
                      .map(this::toActivityModel)
                      .toList();
  }

  public ActivityModel getActivity(long activityId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    } else if (activity.isComment()) {
      throw new IllegalAccessException("Activity with id '%s' is a comment. Use get_activity_comment to get an activity comment by its id".formatted(activityId));
    }
    return toActivityModel(activity.getId());
  }

  public ActivityCommentModel getActivityComment(long activityCommentId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityCommentId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityCommentId));
    } else if (!activityManager.isActivityViewable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityCommentId));
    } else if (!activity.isComment()) {
      throw new IllegalAccessException("Activity with id '%s' is a comment. Use get_activity_comment to get an activity comment by its id".formatted(activityCommentId));
    }
    return toActivityCommentModel(activity.getId());
  }

  public ActivityModel createActivity(Long spaceId, String htmlContent) throws IllegalAccessException, ObjectNotFoundException {
    if (StringUtils.isBlank(htmlContent)) {
      throw new IllegalArgumentException("Activity 'content' attribute is mandatory");
    }
    htmlContent = markdownToHtml(htmlContent);
    org.exoplatform.services.security.Identity userIdentity = getCurrentUserAclIdentity();
    String currentUsername = userIdentity.getUserId();
    Identity authenticatedUserIdentity = identityManager.getOrCreateUserIdentity(currentUsername);
    Identity spaceIdentity = null;
    if (spaceId != null && spaceId > 0) {
      Space space = spaceService.getSpaceById(spaceId);
      if (space == null) {
        throw new ObjectNotFoundException("Space with id '%s' doesn't exists. Use get_my_Spaces Tool to search for a space by its name.".formatted(spaceId));
      }
      spaceIdentity = identityManager.getOrCreateSpaceIdentity(space.getPrettyName());
      if (!activityManager.canPostActivityInStream(userIdentity, spaceIdentity)) {
        throw new IllegalAccessException("Current User isn't allowed to post an activity into the Space with id '%s' doesn't exists. Use get_my_Spaces Tool to search for a space by its name.".formatted(spaceId));
      }
    } else if (!activityManager.canPostActivityInStream(userIdentity, authenticatedUserIdentity)) {
      throw new IllegalAccessException("Current User isn't allowed to post an activity without choosing a space.");
    }

    ExoSocialActivity activity = new ExoSocialActivityImpl();
    activity.setTitle(htmlContent);
    activity.setUserId(authenticatedUserIdentity.getId());
    if (spaceIdentity != null) {
      activityManager.saveActivityNoReturn(spaceIdentity, activity);
    } else {
      activityManager.saveActivityNoReturn(authenticatedUserIdentity, activity);
    }
    return toActivityModel(activity.getId());
  }

  public ActivityModel createActivityWithImage(Long spaceId,
                                               String htmlContent,
                                               String imageUrl,
                                               String imageBase64,
                                               String altText) throws IllegalAccessException, ObjectNotFoundException {
    if (StringUtils.isBlank(imageUrl) && StringUtils.isBlank(imageBase64)) {
      throw new IllegalArgumentException("Provide an image via image_url or image_base64. To post without an image, use create_activity.");
    }
    ActivityModel model = createActivity(spaceId, htmlContent);
    attachImageToObject(ACTIVITY_OBJECT_TYPE, String.valueOf(model.id()), imageUrl, imageBase64, altText);
    return toActivityModel(String.valueOf(model.id()));
  }

  public ActivityModel attachImageToActivity(long activityId,
                                            String imageUrl,
                                            String imageBase64,
                                            String altText) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity userIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    }
    if (!activityManager.isActivityEditable(activity, userIdentity)) {
      throw new IllegalAccessException("Activity with id '%s' can't be edited by current user".formatted(activityId));
    }
    attachImageToObject(ACTIVITY_OBJECT_TYPE, String.valueOf(activityId), imageUrl, imageBase64, altText);
    return toActivityModel(String.valueOf(activityId));
  }

  private void attachImageToObject(String objectType,
                                  String objectId,
                                  String imageUrl,
                                  String imageBase64,
                                  String altText) {
    org.exoplatform.services.security.Identity userIdentity = getCurrentUserAclIdentity();
    long userIdentityId = Long.parseLong(identityManager.getOrCreateUserIdentity(userIdentity.getUserId()).getId());
    String uploadId = UploadToolUtils.materializeFromUrlOrBase64(uploadService,
                                                                 imageUrl,
                                                                 imageBase64,
                                                                 UploadToolUtils.DEFAULT_MAX_BYTES);
    try {
      UploadedAttachmentDetail detail = new UploadedAttachmentDetail(uploadService.getUploadResource(uploadId));
      if (StringUtils.isNotBlank(altText)) {
        detail.setAltText(altText);
      }
      attachmentService.saveAttachment(detail, objectType, objectId, null, userIdentityId);
    } catch (IOException | ObjectAlreadyExistsException | ObjectNotFoundException e) {
      throw new IllegalStateException("Could not attach the image: " + e.getMessage());
    } finally {
      UploadToolUtils.release(uploadService, uploadId);
    }
  }

  public ActivityModel updateActivity(long activityId, String htmlContent) throws IllegalAccessException,
                                                                           ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityEditable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException("Activity with id '%s' can't be edited by current user".formatted(activityId));
    }
    htmlContent = markdownToHtml(htmlContent);
    activity.setTitle(htmlContent);
    activity.setUpdated(System.currentTimeMillis());
    activityManager.updateActivity(activity, true);
    return toActivityModel(activity.getId());
  }

  public void deleteActivity(long activityId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityDeletable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException("Activity with id '%s' can't be deleted by current user".formatted(activityId));
    }
    activityManager.deleteActivity(activity);
  }

  public void pinActivity(long activityId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(authenticatedUserIdentity.getUserId());
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.canPinActivity(activity, currentUserIdentity)) {
      throw new IllegalAccessException("Activity with id '%s' can't be pinned by current user".formatted(activityId));
    }
    activityManager.pinActivity(activity.getId(), currentUserIdentity.getId());
  }

  public void unpinActivity(long activityId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(authenticatedUserIdentity.getUserId());
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.canPinActivity(activity, currentUserIdentity)) {
      throw new IllegalAccessException("Activity with id '%s' can't be pinned by current user".formatted(activityId));
    }
    activityManager.unpinActivity(activity.getId());
  }

  public List<ActivityCommentModel> getActivityComments(long activityId,
                                                        Integer offset,
                                                        Integer limit) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    }
    ExoSocialActivity[] comments = activityManager.getCommentsWithListAccess(activity)
                                                  .load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT));
    return comments == null ? Collections.emptyList() :
                            Stream.of(comments)
                                  .map(ExoSocialActivity::getId)
                                  .map(this::toActivityCommentModel)
                                  .toList();
  }

  public ActivityCommentModel createActivityComment(long activityId, String comment) throws IllegalAccessException,
                                                                                     ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    }

    String authenticatedUser = authenticatedUserIdentity.getUserId();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(authenticatedUser);

    ExoSocialActivity commentActivity = new ExoSocialActivityImpl();
    commentActivity.setTitle(comment);
    commentActivity.setPosterId(currentUserIdentity.getId());
    commentActivity.setUserId(currentUserIdentity.getId());
    activityManager.saveComment(activity, commentActivity);
    return toActivityCommentModel(commentActivity.getId());
  }

  public ActivityCommentModel updateComment(long activityCommentId, String comment) throws IllegalAccessException,
                                                                                    ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity commentActivity = activityManager.getActivity(String.valueOf(activityCommentId));
    if (commentActivity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityCommentId));
    } else if (!activityManager.isActivityViewable(commentActivity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityCommentId));
    } else if (StringUtils.isBlank(commentActivity.getParentId())) {
      throw new IllegalStateException("Activity %s isn't a comment".formatted(activityCommentId));
    }
    if (commentActivity.getTemplateParams() == null) {
      commentActivity.setTemplateParams(new HashMap<>());
    } else {
      commentActivity.setTemplateParams(new HashMap<>(commentActivity.getTemplateParams()));
    }
    commentActivity.getTemplateParams().put("original_comment", commentActivity.getTitle());
    commentActivity.setTitle(comment);
    commentActivity.setUpdated(System.currentTimeMillis());
    activityManager.updateActivity(commentActivity, true);
    return toActivityCommentModel(commentActivity.getId());
  }

  public ActivityModel shareActivityToSpace(long activityId, long spaceId) throws IllegalAccessException,
                                                                           ObjectNotFoundException {
    org.exoplatform.services.security.Identity currentUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, currentUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    }
    Space space = spaceService.getSpaceById(spaceId);
    if (space == null) {
      throw new ObjectNotFoundException("Space with id '%s' doesn't exists. Use get_my_Spaces Tool to search for a space by its name.".formatted(spaceId));
    }
    Identity spaceIdentity = identityManager.getOrCreateSpaceIdentity(space.getPrettyName());
    if (!activityManager.canPostActivityInStream(currentUserIdentity, spaceIdentity)) {
      throw new IllegalAccessException("Current User isn't allowed to post an activity into the Space with id '%s' doesn't exists. Use get_my_Spaces Tool to search for a space by its name.".formatted(spaceId));
    }
    String authenticatedUser = currentUserIdentity.getUserId();
    Identity authenticatedUserIdentity = identityManager.getOrCreateUserIdentity(authenticatedUser);

    ExoSocialActivity activityTemplate = new ExoSocialActivityImpl();
    activityTemplate.setTitle(activity.getTitle());
    activityTemplate.setBody(activity.getBody());
    activityTemplate.setType(activity.getType());
    activityTemplate.setUserId(authenticatedUserIdentity.getId());
    activityTemplate.setFiles(activity.getFiles());
    buildActivityParamsFromEntity(activityTemplate, activity.getTemplateParams());
    List<ExoSocialActivity> sharedActivities = activityManager.shareActivity(activityTemplate,
                                                                             String.valueOf(activityId),
                                                                             List.of(space.getPrettyName()),
                                                                             currentUserIdentity);
    if (CollectionUtils.isNotEmpty(sharedActivities)) {
      return toActivityModel(sharedActivities.get(0).getId());
    } else {
      return null;
    }
  }

  public List<UserModel> getActivityLikers(long activityId,
                                           Integer offset,
                                           Integer limit) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity currentUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, currentUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    }

    String[] likerIdentityIds = activity.getLikeIdentityIds();
    if (ArrayUtils.isEmpty(likerIdentityIds)) {
      return Collections.emptyList();
    } else {
      String currentUsername = currentUserIdentity.getUserId();
      return Stream.of(likerIdentityIds)
                   .skip(getInteger(offset, DEFAULT_OFFSET))
                   .limit(getInteger(limit, DEFAULT_LIMIT))
                   .map(Long::parseLong)
                   .map(identityManager::getIdentity)
                   .filter(Objects::nonNull)
                   .map(Identity::getRemoteId)
                   .map(u -> UserToolUtils.toUserModel(identityManager,
                                                       profilePropertyService,
                                                       userAcl,
                                                       translationService,
                                                       portalConfigService,
                                                       u,
                                                       currentUsername,
                                                       getCurrentUserLocale(currentUsername),
                                                       false))
                   .filter(Objects::nonNull)
                   .toList();
    }
  }

  public void likeTheActivity(long activityId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    }
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(authenticatedUserIdentity.getUserId());
    activityManager.saveLike(activity, currentUserIdentity);
  }

  public void unlikeTheActivity(long activityId) throws IllegalAccessException, ObjectNotFoundException {
    org.exoplatform.services.security.Identity authenticatedUserIdentity = getCurrentUserAclIdentity();
    ExoSocialActivity activity = activityManager.getActivity(String.valueOf(activityId));
    if (activity == null) {
      throw new ObjectNotFoundException(ACTIVITY_NOT_FOUND.formatted(activityId));
    } else if (!activityManager.isActivityViewable(activity, authenticatedUserIdentity)) {
      throw new IllegalAccessException(ACTIVITY_ACCESS_DENIED.formatted(activityId));
    }
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(authenticatedUserIdentity.getUserId());
    activityManager.deleteLike(activity, currentUserIdentity);
  }

  public List<ActivityModel> searchActivities(String query,
                                              Long spaceId,
                                              Boolean isFavorite,
                                              Integer offset,
                                              Integer limit) throws IllegalAccessException, ObjectNotFoundException {
    if (StringUtils.isBlank(query) && (isFavorite == null || !isFavorite.booleanValue())) {
      return getActivities(spaceId, offset, limit);
    }
    org.exoplatform.services.security.Identity currentUser = getCurrentUserAclIdentity();
    String currentUsername = currentUser.getUserId();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(currentUsername);

    Identity spaceIdentity = null;
    if (spaceId != null && spaceId > 0) {
      Space space = spaceService.getSpaceById(spaceId);
      if (space == null || !spaceService.canViewSpace(space, currentUsername)) {
        throw new IllegalAccessException(USER_ACCESS_SPACE_DENIED.formatted(currentUsername, spaceId));
      }
      spaceIdentity = identityManager.getOrCreateSpaceIdentity(space.getPrettyName());
    }
    ActivitySearchFilter activityFilter = new ActivitySearchFilter(query,
                                                                   null,
                                                                   null,
                                                                   spaceIdentity
                                                                       == null ? null :
                                                                               Collections.singletonList(spaceIdentity.getIdentityId()),
                                                                   isFavorite != null && isFavorite.booleanValue(),
                                                                   null,
                                                                   null);

    List<ActivitySearchResult> searchResults = activitySearchConnector.search(currentUserIdentity,
                                                                              activityFilter,
                                                                              getInteger(offset, DEFAULT_OFFSET),
                                                                              getInteger(limit, DEFAULT_LIMIT));
    return searchResults.stream()
                        .map(ActivitySearchResult::getId)
                        .map(String::valueOf)
                        .map(this::toActivityModel)
                        .toList();
  }

  private void buildActivityParamsFromEntity(ExoSocialActivity activity, Map<String, ?> templateParams) {
    Map<String, String> currentTemplateParams =
                                              activity.getTemplateParams() == null ? new HashMap<>() :
                                                                                   new HashMap<>(activity.getTemplateParams());
    if (templateParams != null) {
      templateParams.forEach((name, value) -> currentTemplateParams.put(name, (String) value));
    }
    Iterator<Entry<String, String>> entries = currentTemplateParams.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String, String> entry = entries.next();
      if (entry != null) {
        if (StringUtils.isBlank(entry.getValue())) {
          entry.setValue("");
        } else if (StringUtils.equals(entry.getValue(), "-")) {
          entry.setValue(null);
        }
      }
    }
    activity.setTemplateParams(currentTemplateParams);
  }

  private ActivityModel toActivityModel(String activityId) {
    return ActivityToolUtils.toActivityModel(activityManager,
                                             spaceService,
                                             identityManager,
                                             userAcl,
                                             permanentLinkService,
                                             profilePropertyService,
                                             translationService,
                                             i18NActivityProcessor,
                                             portalConfigService,
                                             activityId,
                                             getCurrentUserAclIdentity(),
                                             getCurrentUserLocale());
  }

  private ActivityCommentModel toActivityCommentModel(String commentId) {
    return ActivityToolUtils.toActivityCommentModel(activityManager,
                                                    spaceService,
                                                    identityManager,
                                                    userAcl,
                                                    permanentLinkService,
                                                    profilePropertyService,
                                                    translationService,
                                                    i18NActivityProcessor,
                                                    portalConfigService,
                                                    commentId,
                                                    getCurrentUserAclIdentity(),
                                                    getCurrentUserLocale());
  }

}

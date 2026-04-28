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
package io.meeds.mcp.server.tool.util;

import static io.meeds.mcp.server.util.McpToolUtils.formatDate;

import java.util.Locale;
import java.util.Map;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.processor.I18NActivityProcessor;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.social.core.storage.impl.StorageUtils;
import org.exoplatform.social.core.utils.MentionUtils;

import io.meeds.mcp.server.tool.model.ActivityCommentModel;
import io.meeds.mcp.server.tool.model.ActivityModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.portal.permlink.model.PermanentLinkObject;
import io.meeds.portal.permlink.service.PermanentLinkService;
import io.meeds.social.activity.plugin.ActivityPermanentLinkPlugin;
import io.meeds.social.html.model.HtmlTransformerContext;
import io.meeds.social.html.utils.HtmlUtils;
import io.meeds.social.translation.service.TranslationService;

import lombok.SneakyThrows;

public class ActivityToolUtils {

  private static final String DEFAULT_TITLE_TEMPLATE_PARAM = "default_title";

  private static final String COMMENT_TEMPLATE_PARAM       = "comment";

  private ActivityToolUtils() {
    // Utils class
  }

  @SneakyThrows
  public static ActivityModel toActivityModel(ActivityManager activityManager, // NOSONAR
                                              SpaceService spaceService,
                                              IdentityManager identityManager,
                                              UserACL userAcl,
                                              PermanentLinkService permanentLinkService,
                                              ProfilePropertyService profilePropertyService,
                                              TranslationService translationService,
                                              I18NActivityProcessor i18NActivityProcessor,
                                              UserPortalConfigService portalConfigService,
                                              String activityId,
                                              org.exoplatform.services.security.Identity currentUserAclIdentity,
                                              Locale currentUserLocale) {
    ExoSocialActivity activity = activityManager.getActivity(activityId);
    if (activity == null) {
      return null;
    }
    Long sharedActivityId = getSharedActivityId(activity);
    String activityUrl = CommonsUtils.getCurrentDomain() +
        permanentLinkService.getLink(new PermanentLinkObject(ActivityPermanentLinkPlugin.OBJECT_TYPE, activityId));
    String currentUsername = currentUserAclIdentity.getUserId();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(currentUsername);
    MentionUtils.substituteRoleWithLocale(activity, currentUserLocale);
    if (activity.getTitleId() != null) {
      activity = i18NActivityProcessor.process(activity, currentUserLocale);
    }
    transformHtmlContent(activity, currentUserAclIdentity, currentUserLocale);
    String activityBody = activity.getTitle();
    Map<String, String> templateParams = activity.getTemplateParams();
    if (MapUtils.isNotEmpty(templateParams)) {
      if (templateParams.containsKey(COMMENT_TEMPLATE_PARAM)) {
        activityBody = templateParams.get(COMMENT_TEMPLATE_PARAM);
      } else if (templateParams.containsKey(DEFAULT_TITLE_TEMPLATE_PARAM)) {
        activityBody = templateParams.get(DEFAULT_TITLE_TEMPLATE_PARAM);
      }
    }

    return new ActivityModel(Long.parseLong(activityId),
                             sharedActivityId,
                             activityBody,
                             activityUrl,
                             activity.isPinned() ? formatDate(StorageUtils.parseRFC3339Date(activity.getPinDate())) : null,
                             formatDate(activity.getUpdated()),
                             formatDate(activity.getPostedTime()),
                             activity.getMetadataObjectType(),
                             activity.getMetadataObjectId(),
                             activity.getNumberOfLikes(),
                             activityManager.getNumberOfAllComments(activityId),
                             CollectionUtils.size(activity.getShareActions()),
                             ArrayUtils.contains(activity.getLikeIdentityIds(), currentUserIdentity.getId()),
                             ArrayUtils.contains(activity.getCommentedIds(), currentUserIdentity.getId()),
                             activity.isHidden(),
                             activity.isPinned(),
                             activityManager.isActivityEditable(activity, currentUserAclIdentity),
                             activityManager.isActivityDeletable(activity, currentUserAclIdentity),
                             activity.isPinned() ? getUserModelByIdentityId(identityManager,
                                                                            profilePropertyService,
                                                                            userAcl,
                                                                            translationService,
                                                                            portalConfigService,
                                                                            activity.getPinAuthorId(),
                                                                            currentUsername,
                                                                            currentUserLocale) :
                                                 null,
                             StringUtils.isNumeric(activity.getPosterId()) ?
                                                                           getUserModelByIdentityId(identityManager,
                                                                                                    profilePropertyService,
                                                                                                    userAcl,
                                                                                                    translationService,
                                                                                                    portalConfigService,
                                                                                                    Long.parseLong(activity.getPosterId()),
                                                                                                    currentUsername,
                                                                                                    currentUserLocale) :
                                                                           null,
                             getActivitySpaceModel(spaceService, activity, currentUsername));
  }

  @SneakyThrows
  public static ActivityCommentModel toActivityCommentModel(ActivityManager activityManager, // NOSONAR
                                                            SpaceService spaceService,
                                                            IdentityManager identityManager,
                                                            UserACL userAcl,
                                                            PermanentLinkService permanentLinkService,
                                                            ProfilePropertyService profilePropertyService,
                                                            TranslationService translationService,
                                                            I18NActivityProcessor i18NActivityProcessor,
                                                            UserPortalConfigService portalConfigService,
                                                            String commentId,
                                                            org.exoplatform.services.security.Identity currentUserAclIdentity,
                                                            Locale currentUserLocale) {
    ExoSocialActivity commentActivity = activityManager.getActivity(commentId);
    String activityCommentUrl = CommonsUtils.getCurrentDomain() +
        permanentLinkService.getLink(new PermanentLinkObject(ActivityPermanentLinkPlugin.OBJECT_TYPE, commentId));
    String currentUsername = currentUserAclIdentity.getUserId();
    Identity currentUserIdentity = identityManager.getOrCreateUserIdentity(currentUsername);
    MentionUtils.substituteRoleWithLocale(commentActivity, currentUserLocale);
    if (commentActivity.getTitleId() != null) {
      commentActivity = i18NActivityProcessor.process(commentActivity, currentUserLocale);
    }
    transformHtmlContent(commentActivity, currentUserAclIdentity, currentUserLocale);
    return new ActivityCommentModel(Long.parseLong(commentId.replace(COMMENT_TEMPLATE_PARAM, "")),
                                    Long.parseLong(commentActivity.getParentId()),
                                    StringUtils.isBlank(commentActivity.getParentCommentId()) ? null :
                                                                                              Long.parseLong(commentActivity.getParentCommentId()),
                                    commentActivity.getTitle(),
                                    activityCommentUrl,
                                    formatDate(commentActivity.getUpdated()),
                                    formatDate(commentActivity.getPostedTime()),
                                    commentActivity.getMetadataObjectType(),
                                    commentActivity.getMetadataObjectId(),
                                    commentActivity.getNumberOfLikes(),
                                    ArrayUtils.getLength(commentActivity.getCommentedIds()),
                                    ArrayUtils.contains(commentActivity.getLikeIdentityIds(), currentUserIdentity.getId()),
                                    ArrayUtils.contains(commentActivity.getCommentedIds(), currentUserIdentity.getId()),
                                    activityManager.isActivityEditable(commentActivity, currentUserAclIdentity),
                                    activityManager.isActivityDeletable(commentActivity, currentUserAclIdentity),
                                    StringUtils.isNumeric(commentActivity.getPosterId()) ?
                                                                                         getUserModelByIdentityId(identityManager,
                                                                                                                  profilePropertyService,
                                                                                                                  userAcl,
                                                                                                                  translationService,
                                                                                                                  portalConfigService,
                                                                                                                  Long.parseLong(commentActivity.getPosterId()),
                                                                                                                  currentUsername,
                                                                                                                  currentUserLocale) :
                                                                                         null);

  }

  private static void transformHtmlContent(ExoSocialActivity activity,
                                           org.exoplatform.services.security.Identity aclIdentity,
                                           Locale locale) {
    HtmlTransformerContext htmlContext = new HtmlTransformerContext(aclIdentity, locale);
    activity.setBody(HtmlUtils.transform(activity.getBody(), htmlContext));
    activity.setTitle(HtmlUtils.transform(activity.getTitle(), htmlContext));
  }

  private static SpaceModel getActivitySpaceModel(SpaceService spaceService,
                                                  ExoSocialActivity activity,
                                                  String currentUsername) {
    String spaceId = activity.getSpaceId();
    if (spaceId != null) {
      Space space = spaceService.getSpaceById(Long.parseLong(spaceId));
      if (space != null) {
        return SpaceToolUtils.toSpaceModel(spaceService,
                                           space,
                                           currentUsername);

      }
    }
    return null;
  }

  private static Long getSharedActivityId(ExoSocialActivity activity) {
    if (activity != null
        && activity.getTemplateParams() != null
        && activity.getTemplateParams().containsKey(ActivityManager.SHARED_ACTIVITY_ID_PARAM)) {
      String originalActivityId = activity.getTemplateParams().get(ActivityManager.SHARED_ACTIVITY_ID_PARAM);
      if (StringUtils.isNotBlank(originalActivityId)) {
        return Long.parseLong(originalActivityId);
      }
    }
    return null;
  }

  private static UserModel getUserModelByIdentityId(IdentityManager identityManager, // NOSONAR
                                                    ProfilePropertyService profilePropertyService,
                                                    UserACL userAcl,
                                                    TranslationService translationService,
                                                    UserPortalConfigService portalConfigService,
                                                    Long identityId,
                                                    String currentUsername,
                                                    Locale currentUserLocale) {
    if (identityId != null && identityId > 0) {
      Identity pinAuthorIdentity = identityManager.getIdentity(identityId);
      if (pinAuthorIdentity != null) {
        return UserToolUtils.toUserModel(identityManager,
                                         profilePropertyService,
                                         userAcl,
                                         translationService,
                                         portalConfigService,
                                         pinAuthorIdentity.getRemoteId(),
                                         currentUsername,
                                         currentUserLocale,
                                         true);
      }
    }
    return null;
  }

}

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
import io.meeds.mcp.server.tool.model.ActivitySummaryModel;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceSummaryModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.model.UserSummaryModel;
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

  /**
   * Reads an activity and builds it as the current user sees it.
   *
   * @param activityManager         the activity service
   * @param spaceService            the space service
   * @param identityManager         the identity service
   * @param userAcl                 the ACL service
   * @param permanentLinkService    the permanent link service
   * @param profilePropertyService  the profile property service
   * @param translationService      the translation service
   * @param i18NActivityProcessor   the activity i18n processor
   * @param portalConfigService     the portal configuration service
   * @param activityId              the activity id
   * @param currentUserAclIdentity  the current user's ACL identity
   * @param currentUserLocale       the current user's locale
   * @return the activity, {@code null} when it doesn't exist
   */
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
    return toActivityModel(activityManager,
                           spaceService,
                           identityManager,
                           userAcl,
                           permanentLinkService,
                           profilePropertyService,
                           translationService,
                           i18NActivityProcessor,
                           portalConfigService,
                           activityManager.getActivity(activityId),
                           currentUserAclIdentity,
                           currentUserLocale);
  }

  /**
   * Builds an activity already read as the current user sees it, without
   * reading it again.
   *
   * @param activityManager         the activity service
   * @param spaceService            the space service
   * @param identityManager         the identity service
   * @param userAcl                 the ACL service
   * @param permanentLinkService    the permanent link service
   * @param profilePropertyService  the profile property service
   * @param translationService      the translation service
   * @param i18NActivityProcessor   the activity i18n processor
   * @param portalConfigService     the portal configuration service
   * @param activity                the activity, may be {@code null}
   * @param currentUserAclIdentity  the current user's ACL identity
   * @param currentUserLocale       the current user's locale
   * @return the activity model, {@code null} when the activity is
   */
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
                                              ExoSocialActivity activity,
                                              org.exoplatform.services.security.Identity currentUserAclIdentity,
                                              Locale currentUserLocale) {
    if (activity == null) {
      return null;
    }
    String activityId = activity.getId();
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

    return new ActivityModel(parseActivityId(activityId),
                             sharedActivityId,
                             activityBody,
                             activityUrl,
                             activity.isPinned() ? formatDate(StorageUtils.parseRFC3339Date(activity.getPinDate())) : null,
                             formatDate(activity.getUpdated()),
                             formatDate(activity.getPostedTime()),
                             activity.getPublicationStartTime() == null ? null : formatDate(activity.getPublicationStartTime()),
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

  /**
   * Reduces an activity to the item a list of activities returns: the same
   * fields, its author, the author of its pin and its space reduced to what
   * names them. The names are the ones the full activity carries, so a name
   * the viewer may not see stays absent.
   *
   * @param activity the activity, may be {@code null}
   * @return the list item, {@code null} when the activity is
   */
  public static ActivitySummaryModel toActivitySummaryModel(ActivityModel activity) {
    if (activity == null) {
      return null;
    }
    return new ActivitySummaryModel(activity.id(),
                                    activity.sharedActivityId(),
                                    activity.content(),
                                    activity.url(),
                                    activity.pinDate(),
                                    activity.updatedDate(),
                                    activity.createDate(),
                                    activity.publicationStartTime(),
                                    activity.referencedContentType(),
                                    activity.referencedContentId(),
                                    activity.numberOfLikes(),
                                    activity.numberOfComments(),
                                    activity.numberOfShares(),
                                    activity.hasLiked(),
                                    activity.hasCommented(),
                                    activity.hidden(),
                                    activity.pinned(),
                                    activity.canEdit(),
                                    activity.canDelete(),
                                    toUserSummaryModel(activity.pinAuthor()),
                                    toUserSummaryModel(activity.author()),
                                    toSpaceSummaryModel(activity.space()));
  }

  /**
   * @param user the user, may be {@code null}
   * @return the user's username and display name, {@code null} when the user
   *         is
   */
  public static UserSummaryModel toUserSummaryModel(UserModel user) {
    return user == null ? null : new UserSummaryModel(user.getUsername(), user.getDisplayName());
  }

  /**
   * @param space the space, may be {@code null}
   * @return the space's id and name, {@code null} when the space is
   */
  public static SpaceSummaryModel toSpaceSummaryModel(SpaceModel space) {
    return space == null ? null : new SpaceSummaryModel(space.getSpaceId(), space.getName());
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
    return new ActivityCommentModel(parseActivityId(commentId),
                                    parseActivityId(commentActivity.getParentId()),
                                    StringUtils.isBlank(commentActivity.getParentCommentId()) ? null :
                                                                                              parseActivityId(commentActivity.getParentCommentId()),
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

  private static long parseActivityId(String commentId) {
    return Long.parseLong(commentId.replace(COMMENT_TEMPLATE_PARAM, ""));
  }

}

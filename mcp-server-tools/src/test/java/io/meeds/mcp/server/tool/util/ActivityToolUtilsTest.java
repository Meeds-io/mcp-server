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
package io.meeds.mcp.server.tool.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.processor.I18NActivityProcessor;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.model.ActivityCommentModel;
import io.meeds.portal.permlink.service.PermanentLinkService;
import io.meeds.social.translation.service.TranslationService;

class ActivityToolUtilsTest {

  private static final String           TESTUSER               = "testuser";

  private final ActivityManager         activityManager        = mock(ActivityManager.class);

  private final SpaceService            spaceService           = mock(SpaceService.class);

  private final IdentityManager         identityManager        = mock(IdentityManager.class);

  private final UserACL                 userAcl                = mock(UserACL.class);

  private final PermanentLinkService    permanentLinkService   = mock(PermanentLinkService.class);

  private final ProfilePropertyService  profilePropertyService = mock(ProfilePropertyService.class);

  private final TranslationService      translationService     = mock(TranslationService.class);

  private final I18NActivityProcessor   i18NActivityProcessor  = mock(I18NActivityProcessor.class);

  private final UserPortalConfigService portalConfigService    = mock(UserPortalConfigService.class);

  private final Identity                currentUserAclIdentity = mock(Identity.class);

  @Test
  void toActivityCommentModelStripsCommentPrefixFromParentCommentId() {
    // Reproduces the reported crash: replying to a reply stores the parent
    // comment id prefixed with "comment" (e.g. "comment474424"), which used
    // to be passed straight to Long.parseLong and blow up with a
    // NumberFormatException.
    String commentId = "comment474431";
    ExoSocialActivity commentActivity = mock(ExoSocialActivity.class);
    when(commentActivity.getParentId()).thenReturn("474400");
    when(commentActivity.getParentCommentId()).thenReturn("comment474424");

    when(activityManager.getActivity(commentId)).thenReturn(commentActivity);
    when(currentUserAclIdentity.getUserId()).thenReturn(TESTUSER);
    when(identityManager.getOrCreateUserIdentity(TESTUSER)).thenReturn(mock(org.exoplatform.social.core.identity.model.Identity.class));

    ActivityCommentModel model = ActivityToolUtils.toActivityCommentModel(activityManager,
                                                                          spaceService,
                                                                          identityManager,
                                                                          userAcl,
                                                                          permanentLinkService,
                                                                          profilePropertyService,
                                                                          translationService,
                                                                          i18NActivityProcessor,
                                                                          portalConfigService,
                                                                          commentId,
                                                                          currentUserAclIdentity,
                                                                          null);

    assertEquals(474431L, model.id());
    assertEquals(474400L, model.activityId());
    assertEquals(474424L, model.parentCommentId());
  }

  @Test
  void toActivityCommentModelWithoutParentCommentIdIsNull() {
    String commentId = "comment474431";
    ExoSocialActivity commentActivity = mock(ExoSocialActivity.class);
    when(commentActivity.getParentId()).thenReturn("474400");
    when(commentActivity.getParentCommentId()).thenReturn(null);

    when(activityManager.getActivity(commentId)).thenReturn(commentActivity);
    when(currentUserAclIdentity.getUserId()).thenReturn(TESTUSER);
    when(identityManager.getOrCreateUserIdentity(TESTUSER)).thenReturn(mock(org.exoplatform.social.core.identity.model.Identity.class));

    ActivityCommentModel model = ActivityToolUtils.toActivityCommentModel(activityManager,
                                                                          spaceService,
                                                                          identityManager,
                                                                          userAcl,
                                                                          permanentLinkService,
                                                                          profilePropertyService,
                                                                          translationService,
                                                                          i18NActivityProcessor,
                                                                          portalConfigService,
                                                                          commentId,
                                                                          currentUserAclIdentity,
                                                                          null);

    assertEquals(474431L, model.id());
    assertEquals(474400L, model.activityId());
    assertNull(model.parentCommentId());
  }

}

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

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.profileproperty.model.ProfilePropertySetting;

import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.social.translation.service.TranslationService;

public class UserToolUtils {

  private static final String              PROFILE_PROPERTY_FIELD_NAME  = "optionValue";

  private static final String              PROFILE_PROPERTY_OBJECT_TYPE = "propertySettingOption";

  private static final Map<String, String> DEFAULT_SITE_BY_USER         = new ConcurrentHashMap<>();

  private UserToolUtils() {
    // Utils
  }

  public static UserModel toUserModel(IdentityManager identityManager, // NOSONAR
                                      ProfilePropertyService profilePropertyService,
                                      UserACL userAcl,
                                      TranslationService translationService,
                                      UserPortalConfigService portalConfigService,
                                      String username,
                                      String viewerUsername,
                                      Locale userLocale,
                                      boolean includeDisabled) {
    if (StringUtils.isBlank(username)) {
      throw new IllegalArgumentException("Parameter 'username' is mandatory");
    }
    Identity userIdentity = identityManager.getOrCreateUserIdentity(username);
    if (userIdentity == null || (!includeDisabled && !userIdentity.isEnable())) {
      return null;
    } else {
      Profile profile = userIdentity.getProfile();
      UserModel userModel = new UserModel();
      userModel.setUsername(username);
      userModel.setLoginId(username);
      userModel.setAboutMe((String) profile.getProperty(Profile.ABOUT_ME));
      String currentDomain = CommonsUtils.getCurrentDomain();
      userModel.setAvatarUrl("%s%s".formatted(currentDomain, profile.getAvatarUrl()));
      userModel.setBannerUrl("%s%s".formatted(currentDomain, profile.getBannerUrl()));
      userModel.setProfilePageUrl("%s/portal/%s/profile/%s".formatted(currentDomain,
                                                                      getDefaultSite(portalConfigService, viewerUsername),
                                                                      username));

      org.exoplatform.services.security.Identity viewerUserAclIdentity = userAcl.getUserIdentity(viewerUsername);
      boolean isAdmin = userAcl.isAdministrator(viewerUserAclIdentity);
      boolean isCurrentUser = Strings.CS.equals(viewerUsername, profile.getIdentity().getRemoteId());
      boolean canViewProperties = isAdmin || isCurrentUser;
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.FIRST_NAME)) {
        userModel.setFirstName((String) profile.getProperty(Profile.FIRST_NAME));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.LAST_NAME)) {
        userModel.setLastName((String) profile.getProperty(Profile.LAST_NAME));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.FULL_NAME)) {
        userModel.setDisplayName(profile.getFullName());
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.COUNTRY)) {
        userModel.setCountry((String) profile.getProperty(Profile.COUNTRY));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.CITY)) {
        userModel.setCity((String) profile.getProperty(Profile.CITY));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.COMPANY)) {
        userModel.setCompany((String) profile.getProperty(Profile.COMPANY));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.DEPARTMENT)) {
        userModel.setDepartment((String) profile.getProperty(Profile.DEPARTMENT));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.TEAM)) {
        userModel.setTeam((String) profile.getProperty(Profile.TEAM));
      }
      if (canViewProperties || isProfilePropertyVisible(profilePropertyService, Profile.POSITION)) {
        userModel.setPosition(getProfilePropertyValue(profilePropertyService,
                                                      translationService,
                                                      profile,
                                                      "position",
                                                      userLocale));
      }
      if (profile.getProperty(Profile.EXTERNAL) != null
          && profile.getProperty(Profile.EXTERNAL).equals("true")) {
        userModel.setGuest(true);
      } else {
        userModel.setInternal(true);
      }
      return userModel;
    }
  }

  public static List<Identity> getUserIdentities(IdentityManager identityManager, List<String> usersToInvite) {
    if (CollectionUtils.isEmpty(usersToInvite)) {
      return Collections.emptyList();
    } else {
      return usersToInvite.stream()
                          .map(u -> u.startsWith("@") ? u.substring(1) : u)
                          .map(identityManager::getOrCreateUserIdentity)
                          .filter(Objects::nonNull)
                          .toList();
    }
  }

  public static String getDefaultSite(UserPortalConfigService portalConfigService, String username) {
    return DEFAULT_SITE_BY_USER.computeIfAbsent(username, k -> portalConfigService.getDefaultSite(username).getName());
  }

  private static boolean isProfilePropertyVisible(ProfilePropertyService profilePropertyService, String propertyName) {
    ProfilePropertySetting profilePropertySetting = profilePropertyService.getProfileSettingByName(propertyName);
    return profilePropertySetting != null
           && (profilePropertySetting.isVisible()
               || !profilePropertyService.isPropertySettingHiddenable(profilePropertySetting.getId()));
  }

  private static String getProfilePropertyValue(ProfilePropertyService profilePropertyService,
                                                TranslationService translationService,
                                                Profile profile,
                                                String propertyName,
                                                Locale userLocale) {
    ProfilePropertySetting propertySetting = profilePropertyService.getProfileSettingByName(propertyName);
    String profilePropertyValue = (String) profile.getProperty(propertyName);
    return propertySetting != null && propertySetting.isDropdownList()
           && NumberUtils.isCreatable(profilePropertyValue) ?
                                                            translationService.getTranslationLabelOrDefault(PROFILE_PROPERTY_OBJECT_TYPE,
                                                                                                            Long.parseLong(profilePropertyValue),
                                                                                                            PROFILE_PROPERTY_FIELD_NAME,
                                                                                                            userLocale) :
                                                            profilePropertyValue;
  }

}

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

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.portal.Constants;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.organization.UserProfileHandler;
import org.exoplatform.services.resources.LocaleConfig;
import org.exoplatform.services.resources.LocaleConfigService;
import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.services.user.UserStateService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.constant.UserStatus;
import io.meeds.mcp.server.tool.model.LanguageModel;
import io.meeds.mcp.server.tool.model.SettingsModel;

import lombok.SneakyThrows;

@Service
public class SettingsMcpTool implements McpToolPlugin {

  private static final String USER_TIMEZONE = "user.timeZone";

  @Autowired
  private OrganizationService organizationService;

  @Autowired
  private LocaleConfigService localeConfigService;

  @Autowired
  private UserStateService    userStateService;

  public SettingsModel getMySettings() {
    String username = getCurrentUserName();
    String language = getAttribute(Constants.USER_LANGUAGE);
    if (StringUtils.isBlank(language)) {
      language = localeConfigService.getDefaultLocaleConfig().getLanguage();
    }
    UserStateModel state = userStateService.getUserState(username);
    return new SettingsModel(language,
                             languageLabel(language),
                             getAttribute(USER_TIMEZONE),
                             state == null ? null : state.getStatus());
  }

  public List<LanguageModel> listAvailableLanguages() {
    return localeConfigService.getLocalConfigs()
                              .stream()
                              .map(config -> new LanguageModel(config.getLanguage(), languageLabel(config)))
                              .toList();
  }

  public SettingsModel setMyLanguage(String language) {
    if (StringUtils.isBlank(language)) {
      throw new IllegalArgumentException("'language' is mandatory. Use list_available_languages to see valid codes.");
    }
    boolean available = localeConfigService.getLocalConfigs()
                                           .stream()
                                           .anyMatch(config -> config.getLanguage().equalsIgnoreCase(language));
    if (!available) {
      throw new IllegalArgumentException("Language '%s' isn't available. Use list_available_languages to see valid codes.".formatted(language));
    }
    saveAttribute(Constants.USER_LANGUAGE, language.toLowerCase());
    return getMySettings();
  }

  public SettingsModel setMyTimezone(String timezone) {
    if (StringUtils.isBlank(timezone)) {
      throw new IllegalArgumentException("'timezone' is mandatory, e.g. 'Europe/Paris'.");
    }
    try {
      ZoneId.of(timezone);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid timezone '%s'. Use a valid IANA timezone id like 'Europe/Paris' or 'America/New_York'.".formatted(timezone));
    }
    saveAttribute(USER_TIMEZONE, timezone);
    return getMySettings();
  }

  public SettingsModel setMyStatus(UserStatus status) {
    if (status == null) {
      throw new IllegalArgumentException("'status' is mandatory. Allowed: AVAILABLE, AWAY, DO_NOT_DISTURB, INVISIBLE, OFFLINE.");
    }
    userStateService.saveStatus(getCurrentUserName(), status.getValue());
    return getMySettings();
  }

  @SneakyThrows
  private String getAttribute(String key) {
    UserProfile profile = organizationService.getUserProfileHandler().findUserProfileByName(getCurrentUserName());
    return profile == null ? null : profile.getAttribute(key);
  }

  @SneakyThrows
  private void saveAttribute(String key, String value) {
    UserProfileHandler handler = organizationService.getUserProfileHandler();
    String username = getCurrentUserName();
    UserProfile profile = handler.findUserProfileByName(username);
    if (profile == null) {
      profile = handler.createUserProfileInstance(username);
    }
    profile.setAttribute(key, value);
    handler.saveUserProfile(profile, true);
  }

  private String languageLabel(String language) {
    return languageLabel(localeConfigService.getLocaleConfig(language));
  }

  private String languageLabel(LocaleConfig config) {
    if (config == null || config.getLocale() == null) {
      return null;
    }
    Locale locale = config.getLocale();
    return StringUtils.capitalize(locale.getDisplayName(locale));
  }

}

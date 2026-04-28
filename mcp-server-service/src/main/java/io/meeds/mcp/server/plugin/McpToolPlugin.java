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
package io.meeds.mcp.server.plugin;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.portal.localization.LocaleContextInfoUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.security.ConversationState;

public interface McpToolPlugin {

  final int DEFAULT_OFFSET = 0;

  final int DEFAULT_LIMIT  = 10;

  default Locale getLocale(String language) {
    try {
      return StringUtils.isBlank(language) ? Locale.ENGLISH : Locale.forLanguageTag(language);
    } catch (Exception e) {
      ExoLogger.getLogger(this.getClass())
               .warn("Error retrieving a locale using language code {}. Use English language instead.",
                     language,
                     e);
      return Locale.ENGLISH;
    }
  }

  default Locale getCurrentUserLocale(String username) {
    return LocaleContextInfoUtils.getUserLocale(username);
  }

  default Locale getCurrentUserLocale() {
    return getCurrentUserLocale(getCurrentUserName());
  }

  default org.exoplatform.services.security.Identity getCurrentUserAclIdentity() {
    return ConversationState.getCurrent().getIdentity();
  }

  default String getCurrentUserName() {
    return getCurrentUserAclIdentity().getUserId();
  }

}

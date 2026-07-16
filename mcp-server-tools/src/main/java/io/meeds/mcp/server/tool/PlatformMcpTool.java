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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.info.ProductInformations;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.portal.branding.BrandingService;
import org.exoplatform.services.resources.LocaleConfigService;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.PlatformInfoModel;

import lombok.SneakyThrows;

@Service
public class PlatformMcpTool implements McpToolPlugin {

  @Autowired
  private ProductInformations productInformations;

  @Autowired
  private BrandingService     brandingService;

  @Autowired
  private IdentityManager     identityManager;

  @Autowired
  private SpaceService        spaceService;

  @Autowired
  private LocaleConfigService localeConfigService;

  public PlatformInfoModel getPlatformInfo() {
    org.exoplatform.services.security.Identity userIdentity = getCurrentUserAclIdentity();
    if (userIdentity == null || !userIdentity.isMemberOf("/platform/users")) {
      throw new IllegalStateException("Guest users can't access platform information.");
    }
    long usersCount = identityManager.getIdentitiesCount(OrganizationIdentityProvider.NAME);
    return new PlatformInfoModel(brandingService.getCompanyName(),
                                 safeVersion(),
                                 productInformations.getEdition(),
                                 CommonsUtils.getCurrentDomain(),
                                 usersCount,
                                 spacesCount(),
                                 defaultLanguage());
  }

  private String safeVersion() {
    try {
      return productInformations.getVersion();
    } catch (Exception e) {
      return null;
    }
  }

  @SneakyThrows
  private int spacesCount() {
    return spaceService.getAllSpacesWithListAccess().getSize();
  }

  private String defaultLanguage() {
    return localeConfigService.getDefaultLocaleConfig() == null ? null
                                                                : localeConfigService.getDefaultLocaleConfig().getLanguage();
  }

}

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

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;

import io.meeds.mcp.server.tool.constant.Registration;
import io.meeds.mcp.server.tool.constant.SpaceRole;
import io.meeds.mcp.server.tool.constant.Visibility;
import io.meeds.mcp.server.tool.model.SpaceModel;
import io.meeds.mcp.server.tool.model.SpaceTemplateModel;
import io.meeds.social.space.constant.SpaceRegistration;
import io.meeds.social.space.constant.SpaceVisibility;
import io.meeds.social.space.template.model.SpaceTemplate;

public class SpaceToolUtils {

  private SpaceToolUtils() {
    // Utils
  }

  public static SpaceTemplateModel toSpaceTemplateModel(SpaceTemplate t) {
    if (t == null) {
      return null;
    } else {
      return new SpaceTemplateModel(t.getId(),
                                    t.getName(),
                                    t.getDescription(),
                                    toVisibility(t.getSpaceDefaultVisibility()),
                                    toRegistration(t.getSpaceDefaultRegistration()),
                                    t.getSpaceDefaultCategoryIds());
    }
  }

  public static SpaceModel toSpaceModel(SpaceService spaceService,
                                        Space space,
                                        String username) {
    if (space == null) {
      return null;
    }
    String currentDomain = CommonsUtils.getCurrentDomain();
    return new SpaceModel(space.getSpaceId(),
                          space.getTemplateId(),
                          space.getDisplayName(),
                          space.getDescription(),
                          toVisibility(SpaceVisibility.valueOf(space.getVisibility().toUpperCase())),
                          toRegistration(SpaceRegistration.valueOf(space.getRegistration().toUpperCase())),
                          "%s/portal/s/%s".formatted(currentDomain, space.getSpaceId()),
                          "%s%s".formatted(currentDomain, space.getAvatarUrl()),
                          "%s%s".formatted(currentDomain, space.getBannerUrl()),
                          space.getMembers().length,
                          space.getManagers().length,
                          space.getCategoryIds(),
                          getUserRoles(spaceService, space, username));
  }

  public static Visibility toVisibility(SpaceVisibility v) {
    if (v == null) {
      return null;
    }
    return switch (v) {
    case HIDDEN: {
      yield Visibility.UNLISTED;
    }
    default:
      yield Visibility.LISTED;
    };
  }

  public static SpaceVisibility toSpaceVisibility(Visibility v) {
    if (v == null) {
      return null;
    }
    return switch (v) {
    case UNLISTED: {
      yield SpaceVisibility.HIDDEN;
    }
    default:
      yield SpaceVisibility.PRIVATE;
    };
  }

  public static Registration toRegistration(SpaceRegistration v) {
    if (v == null) {
      return null;
    }
    return switch (v) {
    case OPEN: {
      yield Registration.OPEN;
    }
    case VALIDATION: {
      yield Registration.REQUEST_JOIN;
    }
    default:
      yield Registration.INVITE_ONLY;
    };
  }

  public static SpaceRegistration toSpaceRegistration(Registration v) {
    if (v == null) {
      return null;
    }
    return switch (v) {
    case OPEN: {
      yield SpaceRegistration.OPEN;
    }
    case REQUEST_JOIN: {
      yield SpaceRegistration.VALIDATION;
    }
    default:
      yield SpaceRegistration.CLOSED;
    };
  }

  public static List<SpaceRole> getUserRoles(SpaceService spaceService, Space space, String username) {
    return Stream.of(spaceService.isMember(space, username) ? SpaceRole.MEMBER : null,
                     spaceService.isManager(space, username) ? SpaceRole.MANAGER : null,
                     spaceService.canRedactOnSpace(space, username) ? SpaceRole.REDACTOR : null,
                     spaceService.canPublishOnSpace(space, username) ? SpaceRole.PUBLISHER : null,
                     spaceService.isInvitedUser(space, username) ? SpaceRole.INVITED : null,
                     spaceService.isPendingUser(space, username) ? SpaceRole.PENDING : null)
                 .filter(Objects::nonNull)
                 .toList();
  }

}

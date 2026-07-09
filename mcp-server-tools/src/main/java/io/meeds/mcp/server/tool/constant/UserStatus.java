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
package io.meeds.mcp.server.tool.constant;

/**
 * Presence status a user can set, mapped to the values stored by
 * {@code org.exoplatform.services.user.UserStateService}.
 */
public enum UserStatus {

  AVAILABLE("available"),
  AWAY("away"),
  DO_NOT_DISTURB("donotdisturb"),
  INVISIBLE("invisible"),
  OFFLINE("offline");

  private final String value;

  UserStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

}

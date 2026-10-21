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
package io.meeds.mcp.server.model;

import lombok.Data;

/**
 * The answer to a pending approval card. The first answer wins: the card
 * resends its answer until the server confirms it, and later copies change
 * nothing.
 */
@Data
public class UserToolApprovalAnswer {

  private String       username;

  /**
   * Whether an answer has been taken and is being recorded: an "Always allow"
   * answer creates its grant outside the card's lock, and the waiter keeps
   * waiting meanwhile, so the grant is never created for a card that timed out.
   */
  private boolean      claimed;

  private boolean      answered;

  private boolean      approved;

  /** The standing approval created by an "Always allow" answer, or null. */
  private McpToolGrant grant;

  /**
   * @param username the user the card is shown to
   */
  public UserToolApprovalAnswer(String username) {
    this.username = username;
  }

}

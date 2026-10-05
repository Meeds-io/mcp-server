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
 * A pending approval card: the call it was shown for, as the server resolved
 * it, and whether the card may offer "Always allow". An "Always allow" answer
 * builds its grant from this record only.
 */
@Data
public class UserToolApprovalRequest {

  private String                 username;

  private long                   start = System.currentTimeMillis();

  /** The call the card was shown for, or null for a card without grants. */
  private McpToolGrantRequest    grantRequest;

  /** Whether the card may offer "Always allow". */
  private boolean                grantable;

  /** The argument limit the card may offer, or null. */
  private McpToolGrantConstraint offeredConstraint;

  /**
   * @param username the user the card is shown to
   */
  public UserToolApprovalRequest(String username) {
    this.username = username;
  }

  /**
   * @param timeout the approval timeout in milliseconds
   * @return true when the card has waited longer than the timeout
   */
  public boolean isTimedOut(long timeout) {
    return (System.currentTimeMillis() - start) > timeout;
  }

}

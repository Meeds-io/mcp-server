/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2026 Meeds Association contact@meeds.io
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

import java.time.Instant;

import io.meeds.mcp.server.constant.McpToolGrantOwnerType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A standing approval of one tool: while it is valid, a call it covers runs
 * without showing an approval card, and the run is traced as granted.
 * <p>
 * A grant covers a call when the tool names are equal, the owner is the
 * calling user (or the grant is a {@link McpToolGrantOwnerType#PLATFORM}
 * policy), the call happens strictly before {@link #getExpiresAt()}, the agent
 * is the grant's agent (or the grant names none), its {@link #getConstraint()}
 * (if any) is satisfied by the tool's own evaluator, and — unless the grant is
 * {@link #isUnattended()} — the call comes from an interactive chat
 * conversation. A grant chosen on an approval card is never unattended.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class McpToolGrant {

  /** The grant identifier in the provider's store, null before creation. */
  private Long                   id;

  /** Who the grant belongs to. */
  private McpToolGrantOwnerType  ownerType;

  /** The owning user for a {@link McpToolGrantOwnerType#USER} grant. */
  private String                 username;

  /** The MCP tool name (snake case). */
  private String                 toolName;

  /** The only agent the grant covers, or null for any agent. */
  private String                 agentNameId;

  /** The argument limit, or null when the grant covers any arguments. */
  private McpToolGrantConstraint constraint;

  /**
   * Whether the grant also covers calls made without the user present (no
   * chat conversation). Never set from an approval card.
   */
  private boolean                unattended;

  /** When the grant was created. */
  private Instant                createdAt;

  /** The instant from which the grant no longer applies. */
  private Instant                expiresAt;

  // Workaround javadoc error not reading lombok classes
  public static class McpToolGrantBuilder { // NOSONAR
  }

}

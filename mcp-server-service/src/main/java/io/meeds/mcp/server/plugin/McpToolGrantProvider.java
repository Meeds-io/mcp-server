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
package io.meeds.mcp.server.plugin;

import java.util.List;

import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantRequest;

/**
 * The store of standing tool approvals, contributed by another add-on (the AI
 * add-on): the MCP server decides, the provider only keeps the grants. With no
 * provider installed no grant ever applies and every approval-gated call shows
 * its card, as before standing approvals existed.
 * <p>
 * A contributor is a Spring {@code @Service} with an explicit, unique bean name
 * whose class implements this interface first, so the Kernel/Spring bridge
 * exports it to the MCP server's context.
 */
public interface McpToolGrantProvider {

  /**
   * Lists the candidate grants for one call: the user's own grants of the tool
   * and the platform grants of the tool, neither revoked nor expired. The MCP
   * server checks every returned grant again (owner, expiry, agent, constraint,
   * interactivity) before applying one, so a provider returning too much never
   * widens a decision.
   *
   * @param username the calling user, never blank
   * @param toolName the MCP tool name
   * @return the candidate grants, never null
   */
  List<McpToolGrant> findGrants(String username, String toolName);

  /**
   * Records that a call ran under a grant (last use and use count). Called
   * after the decision and before the tool runs; a failure here is logged by
   * the caller and does not stop the call, whose run is traced anyway.
   *
   * @param grantId the grant that applied
   * @param request the call it applied to
   */
  void recordUse(long grantId, McpToolGrantRequest request);

  /**
   * Stores a new grant the user chose on an approval card. Every field of the
   * grant was derived by the MCP server from the pending call and the checked
   * choice; the provider still enforces its own limits (maximum validity) and
   * may refuse by throwing.
   *
   * @param grant   the grant to store, without id
   * @param request the pending call the card was shown for
   * @return the stored grant, with its id
   */
  McpToolGrant createGrant(McpToolGrant grant, McpToolGrantRequest request);

}

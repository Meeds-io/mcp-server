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

import org.apache.commons.lang3.StringUtils;

import io.meeds.mcp.server.constant.PrincipalKind;

/**
 * The platform account that executes a tool call: a person's, or an agent's
 * own account. An agent account also names the agent it is the account of.
 *
 * @param kind        whether the account is a person's or an agent's
 * @param username    the account's login, never blank
 * @param agentNameId the agent the account belongs to, set for
 *                      {@link PrincipalKind#AGENT} only
 */
public record PrincipalRef(PrincipalKind kind, String username, String agentNameId) {

  /**
   * Checks the reference is complete and consistent: a kind, a login, and an
   * agent name id exactly when the account is an agent's.
   *
   * @param kind        whether the account is a person's or an agent's
   * @param username    the account's login
   * @param agentNameId the agent the account belongs to, for an agent only
   * @throws IllegalArgumentException when a part is missing or does not fit
   *                                    the kind
   */
  public PrincipalRef {
    if (kind == null) {
      throw new IllegalArgumentException("A principal needs a kind");
    } else if (StringUtils.isBlank(username)) {
      throw new IllegalArgumentException("A principal needs a user name");
    } else if (kind == PrincipalKind.AGENT && StringUtils.isBlank(agentNameId)) {
      throw new IllegalArgumentException("An agent principal needs the agent it is the account of");
    } else if (kind == PrincipalKind.USER && agentNameId != null) {
      throw new IllegalArgumentException("A person principal names no agent");
    }
  }

  /**
   * @param username a person's login
   * @return the reference of that person's account
   */
  public static PrincipalRef user(String username) {
    return new PrincipalRef(PrincipalKind.USER, username, null);
  }

  /**
   * @param username    an agent account's login
   * @param agentNameId the agent it is the account of
   * @return the reference of that agent account
   */
  public static PrincipalRef agent(String username, String agentNameId) {
    return new PrincipalRef(PrincipalKind.AGENT, username, agentNameId);
  }

}

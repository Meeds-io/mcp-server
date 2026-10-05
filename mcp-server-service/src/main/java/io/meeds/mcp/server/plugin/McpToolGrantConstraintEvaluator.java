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

import java.util.Map;

import io.meeds.mcp.server.model.McpToolGrantConstraint;

/**
 * Evaluates the argument limits of standing approvals for the tools of one
 * add-on. It lives beside the tool so that the limit and the tool read the
 * arguments with the same parser: a value the tool would interpret differently
 * from the check is the hole this SPI exists to close.
 * <p>
 * Both methods receive the arguments exactly as the tool method will receive
 * them (camel-case keys, values as parsed from the call input). Both must fail
 * closed: an argument they cannot read with certainty means "no constraint
 * offered" and "does not match".
 * <p>
 * A contributor is a dedicated Spring {@code @Service} with an explicit,
 * unique bean name, non-final (the Kernel/Spring bridge exports it to the MCP
 * server as a proxy of its class). It is never the tool plugin class itself:
 * every public method of an {@code McpToolPlugin} is a tool candidate.
 */
public interface McpToolGrantConstraintEvaluator {

  /**
   * @param toolName the MCP tool name
   * @return true when this evaluator owns the constraints of that tool
   */
  boolean supports(String toolName);

  /**
   * Tells whether any standing approval, limited or not, may cover this call.
   * A tool returns false for calls it never lets run unasked, for example a
   * mail sent from a shared mailbox or in another person's name; such a call
   * always shows its card, and the card doesn't offer "Always allow".
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them
   * @return true when a standing approval may cover the call
   */
  default boolean allowsStandingApproval(String toolName, Map<String, Object> arguments) {
    return true;
  }

  /**
   * Derives the argument limit an approval card may offer for this call, for
   * example "only recipients in example.com" when every recipient is there.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them
   * @return the constraint to offer, or null when none can be offered
   */
  McpToolGrantConstraint proposeConstraint(String toolName, Map<String, Object> arguments);

  /**
   * Tells whether a call stays within a grant's argument limit.
   *
   * @param toolName   the MCP tool name
   * @param arguments  the call arguments as the tool receives them
   * @param constraint the grant's constraint, never null
   * @return true only when the call provably satisfies the constraint
   */
  boolean matches(String toolName, Map<String, Object> arguments, McpToolGrantConstraint constraint);

}

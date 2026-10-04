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
 * A contributor is a Spring {@code @Service} with an explicit, unique bean name
 * whose class implements this interface first (the Kernel/Spring bridge exports
 * a bean under its first interface only, so a tool plugin class cannot also be
 * the evaluator).
 */
public interface McpToolGrantConstraintEvaluator {

  /**
   * @param toolName the MCP tool name
   * @return true when this evaluator owns the constraints of that tool
   */
  boolean supports(String toolName);

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

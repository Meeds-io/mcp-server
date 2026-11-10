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

/**
 * Checks a call of one add-on's tools before the server asks the user to
 * approve it, so that a call the tool would refuse anyway never shows an
 * approval card. It runs for every call of a tool it supports, before the
 * standing approvals and the card, whatever the caller's scopes.
 * <p>
 * A precheck only refuses: it never lets a call skip its card or a grant, and
 * it never replaces the tool's own checks, which still run when the call is
 * approved. It refuses by throwing an {@link IllegalArgumentException} whose
 * message the model reads, as a refusal of the tool itself would; any other
 * exception refuses the call too, with a generic message. A precheck that
 * fails to tell whether it supports a tool is not applied to it.
 * <p>
 * The arguments arrive exactly as the tool method receives them (camel-case
 * keys, values as parsed from the call input); a key may map to {@code null},
 * which reads as an omitted argument.
 * <p>
 * A contributor is a dedicated Spring {@code @Service} with an explicit,
 * unique bean name, non-final (the Kernel/Spring bridge exports it to the MCP
 * server as a proxy of its class). It is never the tool plugin class itself:
 * every public method of an {@code McpToolPlugin} is a tool candidate.
 */
public interface McpToolCallPrecheck {

  /**
   * @param toolName the MCP tool name
   * @return true when this precheck checks the calls of that tool
   */
  boolean supports(String toolName);

  /**
   * Checks a call before its approval, reading and changing nothing the call
   * would change.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them, never null
   * @param username  the user the call runs as
   * @throws IllegalArgumentException with the message the model reads, when
   *           the call is refused
   */
  void check(String toolName, Map<String, Object> arguments, String username);

}

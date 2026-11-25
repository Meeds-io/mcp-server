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

/**
 * Tells which space an object belongs to, for the tools that reach one by its
 * id while their call is limited to a space: a tool definition whose
 * {@code space_scope} names this resolver ({@code resolver}) is called only
 * when the object it reaches belongs to the call's space. For a write that
 * declares {@code space_role: editor}, it also tells whether a person could
 * edit the object ({@link #canEdit(String, String)}).
 * <p>
 * The {@link #spaceOf(String)} answer is used for the space decision only and
 * never reaches the model, so a resolver reads as the system: a missing
 * object, an object of no space, an object in another space and an object it
 * can't read all lead to the same refusal. The {@link #canEdit(String, String)}
 * answer decides whether a digital worker makes the write for a person or
 * asks the space's managers, which the model is told. Neither method may
 * throw; an exception is read as "no space" and "no".
 * <p>
 * A contributor is a Spring {@code @Service} with a unique bean name,
 * non-final (the Kernel/Spring bridge exports it to the MCP server as a proxy
 * of its class), and is looked up at call time, so an add-on that boots after
 * the MCP server is found. A tool whose resolver no add-on provides, or two
 * add-ons provide under one name, is refused.
 */
public interface SpaceScopeResolver {

  /**
   * @return the name a tool definition's {@code space_scope.resolver} gives,
   *         unique on the platform
   */
  String getName();

  /**
   * @param objectId the id the tool call gives: decimal digits, or, when the
   *          declaration says {@code id_format: hex32}, 32 lowercase
   *          hexadecimal digits exactly as sent, or, for
   *          {@code id_format: matrix_room}, a Matrix room id exactly as
   *          sent, with or without its server name
   * @return the id of the space the object belongs to, or null when it is
   *         missing, belongs to no space or can't be read
   */
  Long spaceOf(String objectId);

  /**
   * Tells whether a person could edit the object in person, for a write whose
   * {@code space_scope} declares {@code space_role: editor}: a digital worker
   * makes that write for the person only when the answer is true. Like
   * {@link #spaceOf(String)}, it reads as the system, and it must not throw;
   * an exception, like a null answer, is read as "no", and the write then
   * waits for a manager of the space to decide.
   * <p>
   * A run nobody asked for (a scheduled one) asks no role: a write a standing
   * approval of a manager covers runs at once, without this method. Any other
   * write it makes is recorded as a proposal, and the manager who approves it
   * is asked about here: such a write runs only on the approval of a manager
   * who could make it in person.
   *
   * @param objectId the object's id, as {@link #spaceOf(String)} receives it
   * @param userName the person a digital worker would make the write for
   * @return true when the person could edit the object, false when not or
   *         when this resolver doesn't tell, the default
   */
  default Boolean canEdit(String objectId, String userName) {
    return Boolean.FALSE;
  }

}

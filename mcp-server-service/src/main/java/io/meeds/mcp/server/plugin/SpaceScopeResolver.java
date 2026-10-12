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
 * Tells which space an object belongs to, for the tools that read one by its
 * id while their call is limited to a space: a tool definition whose
 * {@code space_scope} names this resolver ({@code resolver}) is called only
 * when the object it reads belongs to the call's space.
 * <p>
 * The answer is used for that decision only and never reaches the model, so a
 * resolver reads as the system: a missing object, an object of no space, an
 * object in another space and an object it can't read all lead to the same
 * refusal. It must not throw; an exception is read as "no space".
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
   * @param objectId the id the tool call gives, in decimal digits
   * @return the id of the space the object belongs to, or null when it is
   *         missing, belongs to no space or can't be read
   */
  Long spaceOf(String objectId);

}

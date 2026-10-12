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

/**
 * A tool call refused because it reads outside the only space its acting
 * identity may read: traced as denied, not as an error, and still an
 * {@link IllegalStateException} for every caller. Its message is written for
 * the model, which reads it as the tool's result and can call again within
 * the space.
 */
public class SpaceScopeRefusedException extends IllegalStateException {

  private static final long serialVersionUID = 6217930545120813547L;

  /**
   * @param message the explanation the model reads
   */
  public SpaceScopeRefusedException(String message) {
    super(message);
  }

}

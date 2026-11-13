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

import java.util.List;

/**
 * The keys of the items of the lists a tool answers, read from the return type
 * of its method, and where its results hold those items.
 *
 * @param listProperty the property of a list envelope holding the items, null
 *                       when the result is a top-level JSON array
 * @param keys         the keys of the items, in the order they are
 *                       serialized, empty when they are not known
 */
public record ListItemKeys(String listProperty, List<String> keys) {

  /**
   * The keys of a tool answering no list, or a list whose item keys are not
   * known.
   */
  public static final ListItemKeys NONE = new ListItemKeys(null, List.of());

  /**
   * @return true when the item keys are not known
   */
  public boolean isEmpty() {
    return keys.isEmpty();
  }
}

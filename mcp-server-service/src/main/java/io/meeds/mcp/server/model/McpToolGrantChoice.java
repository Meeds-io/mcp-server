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

import io.meeds.mcp.server.constant.McpToolGrantScope;

/**
 * What the user picked with "Always allow" on an approval card: the only part
 * of a grant the client chooses, each value from a closed list checked by the
 * server.
 *
 * @param scope       this agent only, or any agent
 * @param days        the validity in days, one of the offered durations
 * @param constrained whether the grant is limited by the argument constraint
 *                      the card offered (the constraint itself is derived on
 *                      the server from the pending call)
 */
public record McpToolGrantChoice(McpToolGrantScope scope, int days, boolean constrained) {
}

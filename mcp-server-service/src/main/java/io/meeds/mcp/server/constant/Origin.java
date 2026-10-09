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
package io.meeds.mcp.server.constant;

/**
 * What triggered a tool call: the entry point the acting identity was built
 * at.
 */
public enum Origin {

  /** A turn of a person's chat with an agent. */
  CHAT,

  /** A call of an external MCP client, with its own token. */
  EXTERNAL_MCP,

  /** An occurrence of a scheduled agent run. */
  SCHEDULE,

  /**
   * An unattended run started by another source, an e-mail filter rule for
   * example, whose grant scope names that source.
   */
  SOURCE,

  /** The execution of a proposal its owner approved. */
  PROPOSAL,

  /** An agent account answering a mention or a reply in a stream. */
  MENTION,

  /** An agent account answering a comment on a task. */
  TASK_COMMENT,

  /** An agent account working on a task assigned to it. */
  TASK_ASSIGNMENT,

  /** A platform process acting on its own. */
  SYSTEM;

}

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
 * One tool call as the MCP server resolved it, the input of every standing
 * approval decision. Each field comes from the server's own resolution of the
 * call (the token, the trusted internal-client headers, the call input), never
 * from an approval answer: a grant chosen on a card is built from this record,
 * so the card can only pick a scope and an expiry.
 *
 * @param requestId      the call identifier, also the approval request id
 * @param username       the user the tool runs as
 * @param toolName       the MCP tool name (snake case)
 * @param toolInput      the call arguments as received (JSON)
 * @param agentNameId    the calling agent, or null when unknown
 * @param conversationId the chat conversation, or null outside a chat
 * @param clientId       the OAuth client that owns the token, may be null
 * @param retry          whether the call belongs to a retried answer
 * @param actingIdentity who executes the call, for whom and through which
 *                         agents, or null when the server resolved none;
 *                         {@code username} is its subject and
 *                         {@code agentNameId} its grant scope
 */
public record McpToolGrantRequest(String requestId,
                                  String username,
                                  String toolName,
                                  String toolInput,
                                  String agentNameId,
                                  String conversationId,
                                  String clientId,
                                  boolean retry,
                                  ActingIdentity actingIdentity) {

  /**
   * A call with no resolved acting identity, as add-ons built it before the
   * identity existed.
   *
   * @param requestId      the call identifier
   * @param username       the user the tool runs as
   * @param toolName       the MCP tool name (snake case)
   * @param toolInput      the call arguments as received (JSON)
   * @param agentNameId    the calling agent, or null when unknown
   * @param conversationId the chat conversation, or null outside a chat
   * @param clientId       the OAuth client that owns the token, may be null
   * @param retry          whether the call belongs to a retried answer
   */
  public McpToolGrantRequest(String requestId, // NOSONAR the call's parts, each one its own
                             String username,
                             String toolName,
                             String toolInput,
                             String agentNameId,
                             String conversationId,
                             String clientId,
                             boolean retry) {
    this(requestId, username, toolName, toolInput, agentNameId, conversationId, clientId, retry, null);
  }

}

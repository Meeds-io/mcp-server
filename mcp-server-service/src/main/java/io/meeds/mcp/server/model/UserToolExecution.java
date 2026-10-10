/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2025 Meeds Association contact@meeds.io
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

import io.meeds.mcp.server.constant.UserToolRequestType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserToolExecution {

  private String              id;

  private String              conversationId;

  private Long                retryMessageId;

  private String              username;

  private UserToolRequestType toolExecutionType;

  private String              toolName;

  private String              toolInput;

  private String              toolOutput;

  private long                startTime;

  private boolean             completed;

  /** The standing approval the call ran under, or null. */
  private Long                grantId;

  /** The owner type of {@link #grantId}'s grant, or null. */
  private String              grantOwnerType;

  /** The login of the account that executed the call, or null when unknown. */
  private String              actorUserName;

  /** Whether that account is a person's or an agent's, or null when unknown. */
  private String              actorKind;

  /** The person the call was for, null when an agent acted as itself. */
  private String              onBehalfOf;

  /** The agents from the entry point to the calling one, null when unknown. */
  private List<String>        agentChain;

  /** What triggered the call, or null when unknown. */
  private String              origin;

  // Workaround javadoc error not reading lombok classes
  public static class UserToolExecutionBuilder { // NOSONAR
  }
}

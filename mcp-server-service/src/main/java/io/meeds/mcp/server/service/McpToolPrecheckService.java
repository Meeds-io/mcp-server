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
package io.meeds.mcp.server.service;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.meeds.mcp.server.model.McpToolPrecheckRefusedException;
import io.meeds.mcp.server.plugin.McpToolCallPrecheck;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs the add-ons' prechecks of a tool call before the server asks the user
 * to approve it (see {@link McpToolCallPrecheck}): a call one of them refuses
 * shows no card and doesn't run, and the model reads why.
 */
@Service
@Slf4j
public class McpToolPrecheckService {

  private static final String           NOT_CHECKED   =
                                                    "Not executed: '%s' could not be checked before asking the user to approve it. Don't call it again in this turn: tell the user it could not be prepared.";

  private static final String           BAD_ARGUMENTS =
                                                    "Not executed: '%s' needs its arguments as a JSON object. Call it again that way.";

  private final ObjectProvider<McpToolCallPrecheck> prechecks;

  /**
   * @param prechecks the contributed prechecks, none being a valid state
   */
  public McpToolPrecheckService(ObjectProvider<McpToolCallPrecheck> prechecks) {
    this.prechecks = prechecks;
  }

  /**
   * Runs every precheck supporting the tool on a call, in order; the first
   * refusal ends the check.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them, null when
   *                    the call input isn't a JSON object
   * @param username  the user the call runs as
   * @throws McpToolPrecheckRefusedException when a precheck refuses the call,
   *           fails, or can't read its arguments
   */
  public void check(String toolName, Map<String, Object> arguments, String username) {
    List<McpToolCallPrecheck> supporting = getPrechecks(toolName);
    if (supporting.isEmpty()) {
      return;
    }
    if (arguments == null) {
      throw new McpToolPrecheckRefusedException(BAD_ARGUMENTS.formatted(toolName));
    }
    for (McpToolCallPrecheck precheck : supporting) {
      try {
        precheck.check(toolName, arguments, username);
      } catch (IllegalArgumentException e) {
        throw new McpToolPrecheckRefusedException(StringUtils.isBlank(e.getMessage()) ? NOT_CHECKED.formatted(toolName) :
                                                                                     e.getMessage());
      } catch (RuntimeException e) {
        log.warn("Precheck '{}' failed on a call of tool '{}', the call is refused", precheck, toolName, e);
        throw new McpToolPrecheckRefusedException(NOT_CHECKED.formatted(toolName));
      }
    }
  }

  /**
   * @param toolName the MCP tool name
   * @return every precheck supporting the tool, empty when none
   */
  private List<McpToolCallPrecheck> getPrechecks(String toolName) {
    if (prechecks == null || StringUtils.isBlank(toolName)) {
      return List.of();
    }
    return prechecks.orderedStream().filter(precheck -> supports(precheck, toolName)).toList();
  }

  /**
   * @param precheck a contributed precheck
   * @param toolName the MCP tool name
   * @return whether it supports the tool, false when it fails to say
   */
  private boolean supports(McpToolCallPrecheck precheck, String toolName) {
    try {
      return precheck.supports(toolName);
    } catch (RuntimeException e) {
      log.warn("Precheck '{}' failed to tell whether it checks tool '{}'", precheck, toolName, e);
      return false;
    }
  }

}

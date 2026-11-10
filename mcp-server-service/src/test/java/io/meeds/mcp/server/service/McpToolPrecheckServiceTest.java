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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import io.meeds.mcp.server.model.McpToolPrecheckRefusedException;
import io.meeds.mcp.server.plugin.McpToolCallPrecheck;

@ExtendWith(MockitoExtension.class)
class McpToolPrecheckServiceTest {

  private static final String         TOOL      = "apply_list_definition";

  private static final String         USERNAME  = "root";

  private static final Map<String, Object> ARGUMENTS = Map.of("planToken", "v2.1.abc");

  @Mock
  private ObjectProvider<McpToolCallPrecheck> prechecks;

  /**
   * A tool no precheck supports is let through, even with arguments that are
   * not an object.
   */
  @Test
  void check_noPrecheckSupportsTheTool_letsTheCallThrough() {
    McpToolCallPrecheck other = precheck(false);
    when(prechecks.orderedStream()).thenAnswer(invocation -> Stream.of(other));

    new McpToolPrecheckService(prechecks).check(TOOL, null, USERNAME);

    verify(other, never()).check(any(), any(), any());
  }

  /**
   * No precheck installed is a valid state.
   */
  @Test
  void check_noPrecheckInstalled_letsTheCallThrough() {
    when(prechecks.orderedStream()).thenAnswer(invocation -> Stream.empty());

    new McpToolPrecheckService(prechecks).check(TOOL, ARGUMENTS, USERNAME);
    new McpToolPrecheckService(null).check(TOOL, ARGUMENTS, USERNAME);
  }

  /**
   * A refusal reaches the model with the precheck's own message.
   */
  @Test
  void check_refusal_keepsTheMessage() {
    McpToolCallPrecheck precheck = precheck(true);
    doThrow(new IllegalArgumentException("forms.plan.mismatch")).when(precheck).check(TOOL, ARGUMENTS, USERNAME);
    when(prechecks.orderedStream()).thenAnswer(invocation -> Stream.of(precheck));

    McpToolPrecheckRefusedException e = assertThrows(McpToolPrecheckRefusedException.class,
                                                     () -> new McpToolPrecheckService(prechecks).check(TOOL, ARGUMENTS, USERNAME));

    assertEquals("forms.plan.mismatch", e.getMessage());
  }

  /**
   * A precheck that fails refuses the call, without handing its failure's text
   * to the model.
   */
  @Test
  void check_failure_refusesWithoutTheFailureText() {
    McpToolCallPrecheck precheck = precheck(true);
    doThrow(new IllegalStateException("connection refused")).when(precheck).check(TOOL, ARGUMENTS, USERNAME);
    when(prechecks.orderedStream()).thenAnswer(invocation -> Stream.of(precheck));

    McpToolPrecheckRefusedException e = assertThrows(McpToolPrecheckRefusedException.class,
                                                     () -> new McpToolPrecheckService(prechecks).check(TOOL, ARGUMENTS, USERNAME));

    assertTrue(e.getMessage().contains("could not be checked"), e.getMessage());
    assertFalse(e.getMessage().contains("connection refused"), e.getMessage());
  }

  /**
   * Arguments that are not an object are refused when a precheck supports the
   * tool: it could not read them.
   */
  @Test
  void check_unreadableArguments_refusedWhenSupported() {
    McpToolCallPrecheck precheck = precheck(true);
    when(prechecks.orderedStream()).thenAnswer(invocation -> Stream.of(precheck));

    assertThrows(McpToolPrecheckRefusedException.class, () -> new McpToolPrecheckService(prechecks).check(TOOL, null, USERNAME));
    verify(precheck, never()).check(any(), any(), any());
  }

  /**
   * A precheck that fails to tell whether it supports a tool is not applied,
   * so that a broken contributor never blocks every other add-on's tools; the
   * others still run, and the first refusal ends the check.
   */
  @Test
  void check_supportsFailure_notAppliedAndFirstRefusalEnds() {
    McpToolCallPrecheck broken = mock(McpToolCallPrecheck.class);
    when(broken.supports(TOOL)).thenThrow(new IllegalStateException("broken"));
    McpToolCallPrecheck refusing = precheck(true);
    doThrow(new IllegalArgumentException("no place")).when(refusing).check(TOOL, ARGUMENTS, USERNAME);
    McpToolCallPrecheck last = precheck(true);
    when(prechecks.orderedStream()).thenAnswer(invocation -> Stream.of(broken, refusing, last));

    McpToolPrecheckRefusedException e = assertThrows(McpToolPrecheckRefusedException.class,
                                                     () -> new McpToolPrecheckService(prechecks).check(TOOL, ARGUMENTS, USERNAME));

    assertEquals("no place", e.getMessage());
    verify(broken, never()).check(any(), any(), any());
    verify(last, never()).check(any(), any(), any());
  }

  /**
   * @param supports what the precheck answers for {@link #TOOL}
   * @return a mocked precheck
   */
  private static McpToolCallPrecheck precheck(boolean supports) {
    McpToolCallPrecheck precheck = mock(McpToolCallPrecheck.class);
    when(precheck.supports(TOOL)).thenReturn(supports);
    return precheck;
  }

}

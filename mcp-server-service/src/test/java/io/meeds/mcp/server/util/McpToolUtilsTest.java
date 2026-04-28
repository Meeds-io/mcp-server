/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
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
package io.meeds.mcp.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.ToolDefinitionMethods;

class McpToolUtilsTest {

  private static final String TOOL_NAME = "alpha_tool";

  @Test
  void fromJsonString_blank_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.fromJsonString(null));
    assertNull(McpToolUtils.fromJsonString(""));
    assertNull(McpToolUtils.fromJsonString("   "));
  }

  @Test
  void toJsonStringBase64_encodesInputSchema_andFromJsonStringBase64_decodesBack() {// NOSONAR
    SimpleToolDefinition tool = new SimpleToolDefinition();
    tool.setName(TOOL_NAME);
    tool.setDescription("Alpha");
    tool.setInputSchema("{\"type\":\"object\",\"x\":1}");
    tool.setRequireApproval(true);
    tool.setDisabled(false);

    ToolDefinitionMethods original = new ToolDefinitionMethods(List.of(tool));

    String encodedJson = McpToolUtils.toJsonStringBase64(original);
    assertNotNull(encodedJson);
    assertTrue(encodedJson.contains(TOOL_NAME));

    assertFalse(encodedJson.contains("\"type\":\"object\""));

    ToolDefinitionMethods decoded = McpToolUtils.fromJsonStringBase64(encodedJson);
    assertNotNull(decoded);
    assertEquals(1, decoded.tools().size());

    SimpleToolDefinition decodedTool = decoded.tools().get(0);
    assertEquals(TOOL_NAME, decodedTool.getName());
    assertEquals("Alpha", decodedTool.getDescription());
    assertEquals("{\"type\":\"object\",\"x\":1}", decodedTool.getInputSchema());
    assertTrue(decodedTool.isRequireApproval());
    assertFalse(decodedTool.isDisabled());
  }

  @Test
  void fromJsonStringBase64_blank_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.fromJsonStringBase64(null));
    assertNull(McpToolUtils.fromJsonStringBase64(""));
    assertNull(McpToolUtils.fromJsonStringBase64("   "));
  }

  @Test
  void toSnakeCase_nullOrEmpty_passthrough() {// NOSONAR
    assertNull(McpToolUtils.toSnakeCase(null));
    assertEquals("", McpToolUtils.toSnakeCase(""));
  }

  @Test
  void toSnakeCase_convertsCamelToSnake() {// NOSONAR
    assertEquals("my_tool", McpToolUtils.toSnakeCase("myTool"));
    assertEquals("my_tool_name", McpToolUtils.toSnakeCase("myToolName"));
    assertEquals("a", McpToolUtils.toSnakeCase("A"));
  }

  @Test
  void toCamelCase_nullOrEmpty_passthrough() {// NOSONAR
    assertNull(McpToolUtils.toCamelCase(null));
    assertEquals("", McpToolUtils.toCamelCase(""));
  }

  @Test
  void toCamelCase_convertsSnakeToCamel() {// NOSONAR
    assertEquals("myTool", McpToolUtils.toCamelCase("my_tool"));
    assertEquals("myToolName", McpToolUtils.toCamelCase("my_tool_name"));
    assertEquals("alreadyCamel", McpToolUtils.toCamelCase("alreadyCamel"));
  }

  @Test
  void markdownToHtml_rendersBasicMarkdown() {// NOSONAR
    String html = McpToolUtils.markdownToHtml("**bold**");
    assertNotNull(html);
    assertTrue(html.contains("<strong>bold</strong>"), "Expected strong tag in HTML output");
  }

  @Test
  void markdownToHtml_nullInput_returnsOriginalViaCatch() {// NOSONAR
    assertNull(McpToolUtils.markdownToHtml(null));
  }

  @Test
  void toDate_blank_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.toDate(null));
    assertNull(McpToolUtils.toDate(""));
    assertNull(McpToolUtils.toDate("   "));
  }

  @Test
  void formatDate_long_nullOrNonPositive_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.formatDate((Long) null));
    assertNull(McpToolUtils.formatDate(0L));
    assertNull(McpToolUtils.formatDate(-1L));
  }

  @Test
  void formatDate_date_null_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.formatDate((Date) null));
  }

  @Test
  void formatDate_and_toDate_roundTrip() throws Exception { // NOSONAR
    String username = "root";
    Identity userIdentity = new Identity(username);
    ConversationState state = new ConversationState(userIdentity);
    ConversationState.setCurrent(state);

    CommonsUtils.getService(OrganizationService.class)
                .getUserProfileHandler()
                .findUserProfileByName(username)
                .setAttribute("exo.timezone", "UTC");

    Date now = new Date();
    String formatted = McpToolUtils.formatDate(now);

    assertNotNull(formatted);

    Date parsed = McpToolUtils.toDate(formatted);
    assertNotNull(parsed);

    long deltaMs = Math.abs(parsed.getTime() - now.getTime());
    assertTrue(deltaMs < 2000, "Expected roundtrip date to be within 2s, got delta=" + deltaMs + "ms");
  }
}

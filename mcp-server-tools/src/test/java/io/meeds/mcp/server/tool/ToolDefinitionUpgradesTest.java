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
package io.meeds.mcp.server.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.ToolDefinitionUpgrade;
import io.meeds.mcp.server.util.McpToolUtils;

class ToolDefinitionUpgradesTest {

  /**
   * Every upgrade of this module names one of its tools, and replaces only
   * texts older than the shipped one: a digest of the shipped text would make
   * the upgrade replace nothing, and a misspelt name would leave the saved
   * definition of the tool it meant in place.
   */
  @Test
  void everyUpgradeNamesAShippedToolAndReplacesOnlyOlderTexts() {
    URL definitionsUrl = getClass().getClassLoader().getResource("ai-tool-definitions.json");
    URL upgradesUrl = getClass().getClassLoader().getResource("ai-tool-definition-upgrades.json");
    assertNotNull(definitionsUrl);
    assertNotNull(upgradesUrl);
    Map<String, SimpleToolDefinition> tools = McpToolUtils.parseToolDefinitions(definitionsUrl)
                                                          .stream()
                                                          .collect(Collectors.toMap(SimpleToolDefinition::getName,
                                                                                    Function.identity()));
    List<ToolDefinitionUpgrade> upgrades = McpToolUtils.parseToolDefinitionUpgrades(upgradesUrl);
    assertFalse(upgrades.isEmpty());
    for (ToolDefinitionUpgrade upgrade : upgrades) {
      SimpleToolDefinition tool = tools.get(upgrade.name());
      assertNotNull(tool, upgrade.name() + " is no tool of this module");
      assertFalse(upgrade.replacedDefinitions().isEmpty(), upgrade.name() + " replaces nothing");
      String shipped = McpToolUtils.definitionDigest(tool.getDescription(), tool.inputSchema());
      assertFalse(upgrade.replacedDefinitions().contains(shipped), upgrade.name() + " lists the digest of its shipped text");
    }
  }

  /**
   * The managed spaces' description that names the creation date reaches
   * every booted instance: each text a lineage shipped before it is listed
   * as replaced, the one without « (max 100) » that develop shipped and the
   * one with it that the feature branches shipped next (EXO-94746).
   */
  @Test
  void managedSpacesReplacesEveryTextShippedBeforeTheCreationDate() {
    URL upgradesUrl = getClass().getClassLoader().getResource("ai-tool-definition-upgrades.json");
    assertNotNull(upgradesUrl);
    List<String> replaced = McpToolUtils.parseToolDefinitionUpgrades(upgradesUrl)
                                        .stream()
                                        .filter(upgrade -> "get_managed_spaces".equals(upgrade.name()))
                                        .flatMap(upgrade -> upgrade.replacedDefinitions().stream())
                                        .toList();
    for (String limit : List.of("Limit of spaces to retrieve", "Limit of spaces to retrieve (max 100)")) {
      String schema = """
          {"type":"object","properties":{"query":{"type":"string","description":"Keyword search"},"offset":{"type":"integer","description":"Offset of spaces to retrieve"},"limit":{"type":"integer","description":"%s"}}}
          """.formatted(limit);
      assertTrue(replaced.contains(McpToolUtils.definitionDigest("Retrieve the list of spaces where the current user is a manager.", schema)),
                 "the text whose limit reads « " + limit + " » is not replaced");
    }
  }

  /**
   * The user stream's description that names the posts in common spaces
   * reaches every booted instance: the profile-stream text develop shipped
   * and the one with « Compact items » and « (max 100) » that the feature
   * branches shipped next are both listed as replaced (EXO-94931).
   */
  @Test
  void userActivityStreamReplacesEveryProfileStreamText() {
    URL upgradesUrl = getClass().getClassLoader().getResource("ai-tool-definition-upgrades.json");
    assertNotNull(upgradesUrl);
    List<String> replaced = McpToolUtils.parseToolDefinitionUpgrades(upgradesUrl)
                                        .stream()
                                        .filter(upgrade -> "get_user_activity_stream".equals(upgrade.name()))
                                        .flatMap(upgrade -> upgrade.replacedDefinitions().stream())
                                        .toList();
    String profileStream = "Retrieve the activities (Also Known As 'posts') on a given user's profile stream, filtered to what the current user is allowed to see.";
    String schema = """
        {"type":"object","properties":{"username":{"type":"string","description":"Username whose activity stream to retrieve"},"offset":{"type":"integer","description":"Offset of activities to retrieve"},"limit":{"type":"integer","description":"%s"}},"required":["username"]}
        """;
    assertTrue(replaced.contains(McpToolUtils.definitionDigest(profileStream,
                                                               schema.formatted("Limit of activities to retrieve"))),
               "the text develop shipped is not replaced");
    assertTrue(replaced.contains(McpToolUtils.definitionDigest(profileStream + " Compact items; get_activity for details.",
                                                               schema.formatted("Limit of activities to retrieve (max 100)"))),
               "the text the feature branches shipped is not replaced");
  }

}

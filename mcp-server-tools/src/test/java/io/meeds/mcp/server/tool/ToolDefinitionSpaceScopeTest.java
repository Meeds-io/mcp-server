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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.meeds.mcp.server.model.SpaceScopeDeclaration;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.tool.plugin.ActivitySpaceScopeResolver;
import io.meeds.mcp.server.util.McpToolUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the {@code space_scope} declarations this module ships: which tools a
 * call limited to one space may use, and on which arguments.
 */
class ToolDefinitionSpaceScopeTest {

  private static final Set<String> SPACE_READS  = Set.of("get_activities",
                                                         "get_activities_since_days",
                                                         "search_activities",
                                                         "get_space_by_id",
                                                         "get_space_members",
                                                         "list_users_of_space_by_role");

  private static final Set<String> OBJECT_READS = Set.of("get_activity", "get_activity_comments");

  private static final Set<String> DIRECTORY    = Set.of("get_user_by_username", "search_users");

  /**
   * Exactly the expected tools declare a space scope, each of the expected
   * kind, every one of them a read: a tool added to the list, or a write
   * declared, fails here first.
   */
  @Test
  void declaredToolsAreTheExpectedReads() {
    Map<String, SimpleToolDefinition> declared = declaredTools();

    assertThat(declared.keySet()).containsExactlyInAnyOrderElementsOf(union(SPACE_READS, OBJECT_READS, DIRECTORY));
    declared.forEach((name, tool) -> {
      assertThat(tool.getAnnotations()).as(name).isNotNull();
      assertThat(tool.getAnnotations().readOnlyHint()).as(name).isTrue();
    });
    SPACE_READS.forEach(name -> {
      SpaceScopeDeclaration scope = declared.get(name).getSpaceScope();
      assertThat(scope.isSpaceKind()).as(name).isTrue();
      assertThat(scope.getSpaceArg()).as(name).isEqualTo("space_id");
      assertThat(scope.isUseScopeWhenAbsent()).as(name).isTrue();
    });
    OBJECT_READS.forEach(name -> {
      SpaceScopeDeclaration scope = declared.get(name).getSpaceScope();
      assertThat(scope.isObjectKind()).as(name).isTrue();
      assertThat(scope.getObjectArg()).as(name).isEqualTo("activity_id");
      assertThat(scope.getResolver()).as(name).isEqualTo(ActivitySpaceScopeResolver.NAME);
    });
    DIRECTORY.forEach(name -> assertThat(declared.get(name).getSpaceScope().isUnscopedKind()).as(name).isTrue());
    assertThat(declared.get("search_activities").getSpaceScope().getFalseOnlyArgs()).containsExactly("is_favorite");
  }

  /**
   * The arguments a limited call may carry are exactly the tool's schema
   * properties: none the tool doesn't take, none it takes left unchecked.
   */
  @Test
  void acceptedArgumentsAreTheSchemaProperties() {
    JsonMapper mapper = JsonMapper.builder().build();
    declaredTools().forEach((name, tool) -> {
      JsonNode properties = mapper.readTree(tool.getInputSchema()).path("properties");
      Set<String> schema = new LinkedHashSet<>(properties.propertyNames());
      SpaceScopeDeclaration scope = tool.getSpaceScope();
      Set<String> accepted = new LinkedHashSet<>();
      if (scope.getSpaceArg() != null) {
        accepted.add(scope.getSpaceArg());
      }
      if (scope.getObjectArg() != null) {
        accepted.add(scope.getObjectArg());
      }
      accepted.addAll(scope.getAllowedArgs() == null ? List.of() : scope.getAllowedArgs());
      accepted.addAll(scope.getFalseOnlyArgs() == null ? List.of() : scope.getFalseOnlyArgs());
      assertThat(accepted).as(name).containsExactlyInAnyOrderElementsOf(schema);
    });
  }

  /**
   * @return the shipped tools that declare a space scope, by name
   */
  private Map<String, SimpleToolDefinition> declaredTools() {
    URL url = getClass().getClassLoader().getResource("ai-tool-definitions.json");
    return McpToolUtils.parseToolDefinitions(url)
                       .stream()
                       .filter(tool -> tool.getSpaceScope() != null)
                       .collect(Collectors.toMap(SimpleToolDefinition::getName, tool -> tool));
  }

  /**
   * @param sets the sets to join
   * @return their union
   */
  @SafeVarargs
  private static Set<String> union(Set<String>... sets) {
    Set<String> union = new LinkedHashSet<>();
    for (Set<String> set : sets) {
      union.addAll(set);
    }
    return union;
  }

}

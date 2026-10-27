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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.util.McpToolUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the binding of the upload tools and of the image tools that take an
 * {@code upload_id}: each public method has a definition of its snake_case
 * name, and each property of that definition's input schema, in camelCase, is
 * one of the method's parameters, so no argument binds to null. A public
 * method with no definition is silently no tool, and a schema property with
 * no parameter silently never arrives.
 */
class UploadToolDefinitionsTest {

  private static final Set<String> TOOLS = Set.of("start_upload",
                                                  "get_upload",
                                                  "create_activity_with_image",
                                                  "attach_image_to_activity",
                                                  "create_activity_comment_with_image",
                                                  "attach_image_to_comment",
                                                  "set_my_avatar",
                                                  "set_my_banner",
                                                  "set_space_avatar",
                                                  "set_space_banner");

  /**
   * Every listed tool is a public method of a tool class whose parameters are
   * exactly the definition's schema properties, in camelCase, and carries
   * {@code upload_id} where it takes an image; the write tools require
   * approval and the read tool is read-only.
   */
  @Test
  void theUploadAndImageToolsBindEveryArgument() {
    Map<String, SimpleToolDefinition> definitions = McpToolUtils.parseToolDefinitions(getClass().getClassLoader()
                                                                                                .getResource("ai-tool-definitions.json"))
                                                                .stream()
                                                                .collect(Collectors.toMap(SimpleToolDefinition::getName,
                                                                                          Function.identity()));
    Map<String, Method> methods = publicToolMethods();

    TOOLS.forEach(name -> {
      assertThat(definitions).as(name).containsKey(name);
      assertThat(methods).as(name).containsKey(name);
      Set<String> properties = schemaProperties(definitions.get(name)).stream()
                                                                      .map(McpToolUtils::toCamelCase)
                                                                      .collect(Collectors.toSet());
      Set<String> parameters = Arrays.stream(methods.get(name).getParameters())
                                     .map(Parameter::getName)
                                     .collect(Collectors.toSet());
      assertThat(parameters).as(name).isEqualTo(properties);
      if (name.endsWith("_upload")) {
        return;
      }
      assertThat(parameters).as(name).contains("uploadId");
      assertThat(definitions.get(name).isRequireApproval()).as(name).isTrue();
    });
    assertThat(definitions.get("start_upload").isRequireApproval()).isTrue();
    assertThat(definitions.get("start_upload").getAnnotations().readOnlyHint()).isFalse();
    assertThat(definitions.get("get_upload").isRequireApproval()).isFalse();
    assertThat(definitions.get("get_upload").getAnnotations().readOnlyHint()).isTrue();
  }

  /**
   * @return the public methods the tool classes declare, by snake_case name
   */
  private static Map<String, Method> publicToolMethods() {
    return List.of(ActivityMcpTool.class, UserMcpTool.class, SpaceMcpTool.class, UploadMcpTool.class)
               .stream()
               .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
               .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
               .collect(Collectors.toMap(method -> McpToolUtils.toSnakeCase(method.getName()),
                                         Function.identity(),
                                         (first, second) -> first));
  }

  /**
   * @param definition a tool definition
   * @return the names of its input schema's properties
   */
  private static List<String> schemaProperties(SimpleToolDefinition definition) {
    return new ArrayList<>(JsonMapper.builder()
                                     .build()
                                     .readTree(definition.getInputSchema())
                                     .path("properties")
                                     .propertyNames());
  }

}

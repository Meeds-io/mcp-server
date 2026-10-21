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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads this module's tool definitions file as raw JSON: the parser hands the
 * {@code annotations} object to the MCP SDK's {@link ToolAnnotations}, which
 * ignores a key it does not know, so a misspelled hint would load as absent.
 */
class ToolDefinitionAnnotationsTest {

  /** The tools of this module that write, each declaring readOnlyHint false. */
  private static final Set<String> WRITE_TOOLS = Set.of("create_space",
                                                        "update_space_settings",
                                                        "invite_users_to_space",
                                                        "remove_users_from_space",
                                                        "set_user_role_in_space",
                                                        "remove_user_role_in_space",
                                                        "create_activity",
                                                        "update_activity",
                                                        "delete_activity",
                                                        "pin_activity",
                                                        "unpin_activity",
                                                        "create_activity_comment",
                                                        "update_comment",
                                                        "share_activity_to_space",
                                                        "like_the_activity",
                                                        "unlike_the_activity",
                                                        "add_content_to_category",
                                                        "remove_content_from_category",
                                                        "mark_notification_read",
                                                        "mark_all_notifications_read",
                                                        "hide_notification",
                                                        "set_notification_channel_enabled",
                                                        "set_notification_plugin_enabled",
                                                        "mute_space_notifications",
                                                        "send_connection_request",
                                                        "accept_connection_request",
                                                        "refuse_connection_request",
                                                        "remove_connection",
                                                        "update_my_profile",
                                                        "add_work_experience",
                                                        "update_work_experience",
                                                        "remove_work_experience",
                                                        "set_profile_field_visibility",
                                                        "join_space",
                                                        "request_to_join_space",
                                                        "cancel_join_request",
                                                        "leave_space",
                                                        "accept_space_invitation",
                                                        "decline_space_invitation",
                                                        "accept_space_join_request",
                                                        "decline_space_join_request",
                                                        "delete_space",
                                                        "set_my_language",
                                                        "set_my_timezone",
                                                        "set_my_status",
                                                        "add_to_favorites",
                                                        "remove_from_favorites",
                                                        "create_activity_with_image",
                                                        "attach_image_to_activity",
                                                        "create_activity_comment_with_image",
                                                        "attach_image_to_comment",
                                                        "set_my_avatar",
                                                        "set_my_banner",
                                                        "set_space_avatar",
                                                        "set_space_banner",
                                                        "remove_image_from_activity",
                                                        "remove_image_from_comment",
                                                        "publish_scheduled_activity_now");

  private static JsonNode          tools;

  @BeforeAll
  static void readDefinitions() throws Exception {
    try (InputStream inputStream = ToolDefinitionAnnotationsTest.class.getResourceAsStream("/ai-tool-definitions.json")) {
      tools = new ObjectMapper().readTree(inputStream).path("tools");
    }
    assertFalse(tools.isEmpty());
  }

  /**
   * Every key inside a tool's {@code annotations} is a property of the SDK's
   * {@link ToolAnnotations}, read from its {@code @JsonProperty} names.
   */
  @Test
  void everyAnnotationsKeyIsAToolAnnotationsProperty() {
    Set<String> declaredKeys = Arrays.stream(ToolAnnotations.class.getRecordComponents())
                                     .map(RecordComponent::getAccessor)
                                     .map(accessor -> accessor.getAnnotation(JsonProperty.class))
                                     .filter(Objects::nonNull)
                                     .map(JsonProperty::value)
                                     .collect(Collectors.toSet());
    assertTrue(declaredKeys.contains("readOnlyHint"), "ToolAnnotations' properties not read: " + declaredKeys);
    for (JsonNode tool : tools) {
      JsonNode annotations = tool.path("annotations");
      assertTrue(annotations.isObject(), tool.path("name").asString() + " declares no annotations");
      annotations.propertyNames()
                 .forEach(key -> assertTrue(declaredKeys.contains(key),
                                                     tool.path("name").asString() + " declares an annotations key "
                                                         + "ToolAnnotations does not know: " + key));
    }
  }

  /**
   * Every tool declares its readOnlyHint, and the ones declaring it false are
   * exactly the write tools, named.
   */
  @Test
  void theWriteToolsAndOnlyThemDeclareReadOnlyHintFalse() {
    Set<String> declaredWrites = new HashSet<>();
    for (JsonNode tool : tools) {
      JsonNode readOnlyHint = tool.path("annotations").path("readOnlyHint");
      assertTrue(readOnlyHint.isBoolean(), tool.path("name").asString() + " declares no boolean readOnlyHint");
      if (!readOnlyHint.booleanValue()) {
        declaredWrites.add(tool.path("name").asString());
      }
    }
    assertEquals(WRITE_TOOLS, declaredWrites);
  }

}

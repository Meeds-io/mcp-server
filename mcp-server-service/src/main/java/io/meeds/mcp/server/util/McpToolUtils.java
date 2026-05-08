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
package io.meeds.mcp.server.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import org.exoplatform.commons.utils.ISO8601;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.security.ConversationState;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.ToolDefinitionMethods;

import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class McpToolUtils {

  public static final String        AI_AGENT_TOOL_EXECUTION_EVENT                 = "ai-agent-tool-execution";

  public static final String        MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID = "mcp-internal";

  public static final String        TOOL_CONTEXT_USER_NAME_PARAM                  = "userName";

  public static final String        TOOL_CONTEXT_ID_PARAM                         = "contextId";

  public static final String        TOOL_CONTEXT_ID                               = UUID.randomUUID().toString();

  public static final String        TOOL_READ_SCOPE                               = "mcp.tools.read";

  public static final String        TOOL_WRITE_SCOPE                              = "mcp.tools.write";

  public static final String        TOOL_WRITE_APPROVE_SCOPE                      = "mcp.tools.writeWithApproval";

  public static final String        EVENT_TOOL_UPDATED                            = "ai-agent-tool-updated";

  public static final String        AI_AGENT_TOOL_APPROVED_PARAM                  = "approved";

  public static final String        AI_AGENT_TOOL_USERNAME_PARAM                  = "username";

  public static final String        AI_AGENT_TOOL_CONVERSATION_ID_PARAM           = "conversationId";

  public static final String        AI_AGENT_TOOL_INPUT_PARAM                     = "toolInput";

  public static final String        AI_AGENT_TOOL_OUTPUT_PARAM                    = "toolOutput";

  public static final String        AI_AGENT_TOOL_TYPE_PARAM                      = "type";

  public static final String        AI_AGENT_TOOL_DURATION_PARAM                  = "duration";

  public static final String        AI_AGENT_TOOL_START_TIME_PARAM                = "startTime";

  public static final String        AI_AGENT_TOOL_ID_PARAM                        = "id";

  public static final String        AI_AGENT_TOOL_NAME_PARAM                      = "toolName";

  public static final String        AI_AGENT_TOOL_EXEC_COMPLETED_PARAM            = "completed";

  private static final ObjectMapper OBJECT_MAPPER                                 = new ObjectMapper();

  private static final String       PROFILE_TIMEZONE                              = "user.timeZone";

  static {
    // Workaround when Jackson is defined in shared library with different
    // version and without artifact jackson-datatype-jsr310
    OBJECT_MAPPER.setVisibility(VisibilityChecker.Std.defaultInstance().withFieldVisibility(JsonAutoDetect.Visibility.ANY));
  }

  private McpToolUtils() {
    // Utils class
  }

  public static List<SimpleToolDefinition> parseToolDefinitions(URL url) {
    try (InputStream inputStream = url.openStream()) {
      String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
      ToolDefinitionMethods definitionMethods = fromJsonString(content);
      return definitionMethods.tools();
    } catch (IOException e) {
      log.warn("An error occurred while parsing Tool Definitions from url {}", url, e);
      return Collections.emptyList();
    }
  }

  @SneakyThrows
  public static final ToolDefinitionMethods fromJsonString(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    return OBJECT_MAPPER.readValue(value, ToolDefinitionMethods.class);
  }

  @SneakyThrows
  public static ToolDefinitionMethods fromJsonStringBase64(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    ToolDefinitionMethods toolDefinitions = OBJECT_MAPPER.readValue(value, ToolDefinitionMethods.class);
    toolDefinitions.tools()
                   .forEach(t -> t.setInputSchema(new String(Base64.getDecoder()
                                                                   .decode(t.getInputSchema()
                                                                            .replace("\"", "")
                                                                            .getBytes()))));
    return toolDefinitions;
  }

  @SneakyThrows
  public static String toJsonStringBase64(ToolDefinitionMethods toolDefinitions) {
    List<SimpleToolDefinition> tools = toolDefinitions.tools()
                                                      .stream()
                                                      .map(SimpleToolDefinition::new)
                                                      .toList();
    tools.forEach(t -> t.setInputSchema(new String(Base64.getEncoder().encode(t.getInputSchema().getBytes()))));
    return OBJECT_MAPPER.writeValueAsString(new ToolDefinitionMethods(tools));
  }

  public static String toSnakeCase(String name) {
    if (name == null || name.isEmpty()) {
      return name;
    }
    StringBuilder snakeCaseBuilder = new StringBuilder();
    snakeCaseBuilder.append(Character.toLowerCase(name.charAt(0)));
    for (int i = 1; i < name.length(); i++) {
      char currentChar = name.charAt(i);
      if (Character.isUpperCase(currentChar)) {
        snakeCaseBuilder.append('_');
        snakeCaseBuilder.append(Character.toLowerCase(currentChar));
      } else {
        snakeCaseBuilder.append(currentChar);
      }
    }
    return snakeCaseBuilder.toString();
  }

  public static String toCamelCase(String name) {
    if (name == null || name.isEmpty()) {
      return name;
    }
    StringBuilder camelCaseBuilder = new StringBuilder();
    boolean capitalizeNext = false;

    for (int i = 0; i < name.length(); i++) {
      char currentChar = name.charAt(i);
      if (currentChar == '_') {
        capitalizeNext = true;
      } else {
        if (capitalizeNext) {
          camelCaseBuilder.append(Character.toUpperCase(currentChar));
          capitalizeNext = false;
        } else {
          camelCaseBuilder.append(currentChar);
        }
      }
    }
    return camelCaseBuilder.toString();
  }

  public static Object getMethodToolFieldValue(MethodToolCallback toolCallback, String fieldName) throws NoSuchFieldException,
                                                                                                  IllegalAccessException {
    Field field = MethodToolCallback.class.getDeclaredField(fieldName);
    field.setAccessible(true); // NOSONAR
    return field.get(toolCallback);
  }

  public static String getCurrentUserName() {
    if (ConversationState.getCurrent() != null
        && ConversationState.getCurrent().getIdentity() != null) {
      return ConversationState.getCurrent().getIdentity().getUserId();
    } else if (SecurityContextHolder.getContext() != null
               && SecurityContextHolder.getContext().getAuthentication() != null) {
      String authenticatedUser = SecurityContextHolder.getContext().getAuthentication().getName();
      if (!StringUtils.equals(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, authenticatedUser)) {
        return authenticatedUser;
      } else if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletRequestAttributes) {
        HttpServletRequest request = servletRequestAttributes.getRequest();
        // Coming from internaly authenticated call using 'client_credentials'
        // OAuth mechanism
        String userName = request.getHeader(TOOL_CONTEXT_USER_NAME_PARAM);
        String contextId = request.getHeader(TOOL_CONTEXT_ID_PARAM);
        if (StringUtils.equals(contextId, TOOL_CONTEXT_ID)
            && StringUtils.isNotBlank(userName)) {
          return userName;
        }
      }
    }
    return null;
  }

  public static String markdownToHtml(String markdown) {
    if (StringUtils.isBlank(markdown)
        || StringUtils.containsAny(markdown,
                                   "<ul>",
                                   "<li>",
                                   "<div>",
                                   "<p>",
                                   "<img ")) {
      return markdown;
    }
    try {
      Parser parser = Parser.builder().build();
      Node document = parser.parse(markdown);
      return HtmlRenderer.builder().build().render(document);
    } catch (Exception e) {
      log.warn("Error transforming content to markdown (use original content): {}", markdown, e);
      return markdown;
    }
  }

  public static Date toDate(String date) {
    if (StringUtils.isBlank(date)) {
      return null;
    }
    return ISO8601.parse(date).getTime();
  }

  public static String formatDate(Long time) {
    if (time == null || time <= 0) {
      return null;
    }
    return formatDate(new Date(time));
  }

  public static String formatDate(Date date) {
    if (date == null) {
      return null;
    }
    TimeZone tz = getUserTimeZone();
    Calendar calendar = tz != null ? Calendar.getInstance(tz) : Calendar.getInstance();
    calendar.setTime(date);
    return ISO8601.format(calendar);
  }

  @SneakyThrows
  public static TimeZone getUserTimeZone() {
    OrganizationService orgService = ExoContainerContext.getService(OrganizationService.class);
    UserProfile userProfile = orgService.getUserProfileHandler().findUserProfileByName(getCurrentUserName());
    String timeZone = userProfile == null ? null : userProfile.getAttribute(PROFILE_TIMEZONE);
    if (StringUtils.isBlank(timeZone)) {
      return null;
    } else {
      return TimeZone.getTimeZone(timeZone);
    }
  }

}

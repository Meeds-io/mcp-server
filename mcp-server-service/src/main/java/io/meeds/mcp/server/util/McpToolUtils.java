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
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
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

  /**
   * Name of the {@code ExoFeatureService} feature gating the MCP server, both
   * globally (its on/off flag) and per user (the audience resolved by
   * {@code McpServerFeaturePlugin}).
   */
  public static final String        MCP_SERVER_FEATURE                            = "mcp.server";

  public static final String        EVENT_MCP_SERVER_AUDIENCE_UPDATED             = "mcp-server-audience-updated";

  public static final String        TOOL_CONTEXT_USER_NAME_PARAM                  = "userName";

  public static final String        TOOL_CONTEXT_ID_PARAM                         = "contextId";

  public static final String        TOOL_CONTEXT_CONVERSATION_ID_PARAM            = "conversationId";

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

  /**
   * Utility class: every member is static, so it is never instantiated.
   */
  private McpToolUtils() {
  }

  /**
   * Parses one {@code ai-tool-definitions.json} resource.
   *
   * @param url the resource to read
   * @return the tool definitions it declares, empty when it cannot be read
   */
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

  /**
   * @param value the tool definitions as a JSON string
   * @return the parsed definitions, or null when the string is blank
   */
  @SneakyThrows
  public static final ToolDefinitionMethods fromJsonString(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    return OBJECT_MAPPER.readValue(value, ToolDefinitionMethods.class);
  }

  /**
   * Same as {@link #fromJsonString(String)} for the persisted form, whose
   * input schemas are base64 encoded.
   *
   * @param value the persisted tool definitions
   * @return the parsed definitions with decoded schemas, or null when blank
   */
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

  /**
   * Serializes tool definitions to their persisted form, base64 encoding each
   * input schema.
   *
   * @param toolDefinitions the definitions to serialize
   * @return the JSON string to persist
   */
  @SneakyThrows
  public static String toJsonStringBase64(ToolDefinitionMethods toolDefinitions) {
    List<SimpleToolDefinition> tools = toolDefinitions.tools()
                                                      .stream()
                                                      .map(SimpleToolDefinition::new)
                                                      .toList();
    tools.forEach(t -> t.setInputSchema(new String(Base64.getEncoder().encode(t.getInputSchema().getBytes()))));
    return OBJECT_MAPPER.writeValueAsString(new ToolDefinitionMethods(tools));
  }

  /**
   * @param name a Java method name
   * @return its snake-case form, which is the MCP tool name
   */
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

  /**
   * @param name a snake-case name
   * @return its camel-case form
   */
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

  /**
   * Reads a private field of a Spring AI {@link MethodToolCallback}, which
   * exposes no accessor for the tool object and method it wraps.
   *
   * @param toolCallback the callback to introspect
   * @param fieldName    the field to read
   * @return the field value
   * @throws NoSuchFieldException   when the field does not exist
   * @throws IllegalAccessException when it cannot be read
   */
  public static Object getMethodToolFieldValue(MethodToolCallback toolCallback, String fieldName) throws NoSuchFieldException,
                                                                                                  IllegalAccessException {
    Field field = MethodToolCallback.class.getDeclaredField(fieldName);
    field.setAccessible(true); // NOSONAR
    return field.get(toolCallback);
  }

  /**
   * Resolves the user on behalf of whom the current Tool is executed: the
   * kernel {@link ConversationState} identity when set, else, for the internal
   * client-credentials call only, the {@link #TOOL_CONTEXT_USER_NAME_PARAM}
   * request header, else the authenticated principal. The header is trusted
   * under the gate of {@link #getInternalToolCallRequest()} and nowhere else;
   * the internal call is recognized by the OAuth client that owns the token
   * ({@link #isInternalClientAuthentication(Authentication)}), never by the
   * token subject, so a user whose login happens to be the internal client id
   * is just that user.
   *
   * @return the user name, or null when no user can be resolved
   */
  public static String getCurrentUserName() {
    if (ConversationState.getCurrent() != null
        && ConversationState.getCurrent().getIdentity() != null) {
      return ConversationState.getCurrent().getIdentity().getUserId();
    }
    // SecurityContextHolder.getContext() never returns null: every strategy
    // creates an empty context when none is bound to the thread
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return null;
    } else if (isInternalClientAuthentication(authentication)) {
      // Coming from internally authenticated call using 'client_credentials'
      // OAuth mechanism: the end user is carried in the request header
      HttpServletRequest request = getInternalToolCallRequest();
      String userName = request == null ? null : request.getHeader(TOOL_CONTEXT_USER_NAME_PARAM);
      return StringUtils.isBlank(userName) ? null : userName;
    } else {
      return authentication.getName();
    }
  }

  /**
   * Resolves the AI chat conversation in which the current Tool is executed.
   * The id travels in the {@link #TOOL_CONTEXT_CONVERSATION_ID_PARAM} request
   * header set by the internal MCP client and is trusted under exactly the
   * same gate as the user name ({@link #getInternalToolCallRequest()}): any
   * other caller, an external OAuth MCP client for instance, gets null
   * whatever it sends, so it can never choose the conversation its Tool
   * executions are recorded in.
   *
   * @return the conversation id, or null when the call isn't the internal
   *         client's or carries no conversation
   */
  public static String getCurrentConversationId() {
    HttpServletRequest request = getInternalToolCallRequest();
    return request == null ? null : StringUtils.trimToNull(request.getHeader(TOOL_CONTEXT_CONVERSATION_ID_PARAM));
  }

  /**
   * Returns the current servlet request only when it is the internal
   * client-credentials call (a bearer token owned by the OAuth client
   * {@link #MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID}, see
   * {@link #isInternalClientAuthentication(Authentication)}) carrying a
   * {@link #TOOL_CONTEXT_ID_PARAM} header equal to the JVM-private
   * {@link #TOOL_CONTEXT_ID}. That is the only caller whose context headers
   * (user name, conversation id) are trusted.
   *
   * @return the request, or null for any other caller or outside a request
   */
  private static HttpServletRequest getInternalToolCallRequest() {
    if (!isInternalClientAuthentication(SecurityContextHolder.getContext().getAuthentication())) {
      return null;
    } else if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletRequestAttributes) {
      HttpServletRequest request = servletRequestAttributes.getRequest();
      return isInternalToolContextId(request.getHeader(TOOL_CONTEXT_ID_PARAM)) ? request : null;
    } else {
      return null;
    }
  }

  /**
   * Tells whether the authentication is the internal client-credentials call:
   * a bearer token whose introspection attributes name
   * {@link #MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID} as the owning OAuth
   * client ({@link OAuth2TokenIntrospectionClaimNames#CLIENT_ID}, set by the
   * authorization server from the registered client the token was issued to).
   * The token subject is deliberately not consulted: it is the client id for a
   * client-credentials grant but the user login for a user grant, so a user
   * whose login is the internal client id would otherwise enter the internal
   * path with an ordinary user token.
   *
   * @param authentication the current {@link Authentication}, may be null
   * @return true when the token belongs to the internal client, else false
   */
  public static boolean isInternalClientAuthentication(Authentication authentication) {
    if (!(authentication instanceof BearerTokenAuthentication bearerTokenAuthentication)) {
      return false;
    }
    // getTokenAttributes() is never null: an unmodifiable copy of the
    // principal attributes, built by every constructor
    return isInternalClientId(bearerTokenAuthentication.getTokenAttributes().get(OAuth2TokenIntrospectionClaimNames.CLIENT_ID));
  }

  /**
   * Same question as {@link #isInternalClientAuthentication(Authentication)},
   * asked one step earlier: at token introspection time there is no
   * {@link Authentication} yet, only the introspected principal. Both
   * enforcement points of the MCP access gate must recognize the internal
   * client the same way or the exemption means two different things, so both
   * go through {@link #isInternalClientId(Object)} and neither consults the
   * token subject.
   *
   * @param tokenAttributes the introspected token attributes, may be null
   * @return true when the token belongs to the internal client, else false
   */
  public static boolean isInternalClientPrincipal(Map<String, Object> tokenAttributes) {
    return tokenAttributes != null && isInternalClientId(tokenAttributes.get(OAuth2TokenIntrospectionClaimNames.CLIENT_ID));
  }

  /**
   * Compares an introspected {@code client_id} claim with the internal MCP
   * client's registration id. The claim is authoritative: the authorization
   * server overwrites it from the registered client the token was actually
   * issued to, so unlike the subject it cannot be chosen by the token holder.
   *
   * @param clientIdClaim the {@code client_id} claim value, may be null or of
   *                      any type
   * @return true when the claim names the internal MCP client
   */
  private static boolean isInternalClientId(Object clientIdClaim) {
    return clientIdClaim instanceof String clientIdValue
           && Strings.CS.equals(MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, clientIdValue);
  }

  /**
   * Compares the received {@link #TOOL_CONTEXT_ID_PARAM} header with the
   * JVM-private {@link #TOOL_CONTEXT_ID} in constant time: the id is the
   * trust anchor of the internal call, so its comparison must not leak, byte
   * by byte, how much of it a caller got right.
   *
   * @param contextId the header value, may be null
   * @return true when it equals {@link #TOOL_CONTEXT_ID}, else false
   */
  private static boolean isInternalToolContextId(String contextId) {
    return contextId != null
           && MessageDigest.isEqual(contextId.getBytes(StandardCharsets.UTF_8),
                                    TOOL_CONTEXT_ID.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Renders Markdown a tool produced into the HTML the platform's content
   * fields expect, and leaves it untouched when it already carries HTML — a
   * tool's output is not always Markdown, and rendering HTML twice mangles it.
   *
   * @param markdown the tool output, may be null or blank
   * @return the rendered HTML, or the input unchanged when it is blank, when
   *         it already looks like HTML, or when rendering failed
   */
  public static String markdownToHtml(String markdown) {
    if (StringUtils.isBlank(markdown)
        || Strings.CS.containsAny(markdown,
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

  /**
   * @param date an ISO-8601 date as a tool argument carries it, may be null or
   *             blank
   * @return the parsed date, or null when nothing was given
   */
  public static Date toDate(String date) {
    if (StringUtils.isBlank(date)) {
      return null;
    }
    return ISO8601.parse(date).getTime();
  }

  /**
   * @param time an epoch timestamp in milliseconds, may be null or
   *             non-positive
   * @return the ISO-8601 representation in the current user's time zone, or
   *         null when no usable timestamp was given
   */
  public static String formatDate(Long time) {
    if (time == null || time <= 0) {
      return null;
    }
    return formatDate(new Date(time));
  }

  /**
   * @param date the date to render, may be null
   * @return the ISO-8601 representation in the current user's time zone, or
   *         null when no date was given
   */
  public static String formatDate(Date date) {
    if (date == null) {
      return null;
    }
    TimeZone tz = getUserTimeZone();
    Calendar calendar = tz != null ? Calendar.getInstance(tz) : Calendar.getInstance();
    calendar.setTime(date);
    return ISO8601.format(calendar);
  }

  /**
   * Reads the acting user's time zone from their platform profile, so that a
   * date a tool returns reads the same as the one the web UI shows them.
   *
   * @return the user's time zone, or null when the profile carries none — the
   *         caller then falls back to the server's default
   */
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

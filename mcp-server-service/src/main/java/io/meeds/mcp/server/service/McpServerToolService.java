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
package io.meeds.mcp.server.service;

import static io.meeds.mcp.server.util.McpToolUtils.EVENT_TOOL_UPDATED;
import static io.meeds.mcp.server.util.McpToolUtils.MCP_SERVER_FEATURE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_READ_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_APPROVE_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.TOOL_WRITE_SCOPE;
import static io.meeds.mcp.server.util.McpToolUtils.toSnakeCase;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.services.listener.ListenerService;

import io.meeds.mcp.server.listener.ToolListener;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.ToolDefinitionMethods;
import io.meeds.mcp.server.util.McpToolUtils;

import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class McpServerToolService {

  public static final String                SCOPE_PATTERN                 = "SCOPE_%s";

  public static final String                READ_SCOPE_AUTHORITY          = SCOPE_PATTERN.formatted(TOOL_READ_SCOPE);

  public static final String                WRITE_SCOPE_AUTHORITY         = SCOPE_PATTERN.formatted(TOOL_WRITE_SCOPE);

  public static final String                WRITE_APPROVE_SCOPE_AUTHORITY = SCOPE_PATTERN.formatted(TOOL_WRITE_APPROVE_SCOPE);

  private static final String               AI_TOOLS_JSON_PATH            = "ai-tool-definitions.json";

  private static final Context              AI_AGENT_CONTEXT              = Context.GLOBAL.id("AI_AGENT");

  private static final String               TOOLS_KEY                     = "AI_AGENT_TOOL_DEFINITIONS_v17";

  private static final Scope                TOOLS_SCOPE                   = Scope.APPLICATION.id(TOOLS_KEY);

  @Autowired
  private PortalContainer                   container;

  @Autowired
  private SettingService                    settingService;

  @Autowired
  private ExoFeatureService                 featureService;

  @Autowired
  private ListenerService                   listenerService;

  @Value("${meeds.mcp.tools.forceReimport:false}")
  @Setter
  private boolean                           forceReimport;

  private Boolean                           mcpEnabled;

  private List<ToolListener>                toolListeners                 = new ArrayList<>();

  @Setter
  private Map<String, SimpleToolDefinition> toolDefinitions;

  /**
   * @param methodName the Java method name implementing the tool
   * @return the tool definition registered under that method's snake-case
   *         name, or null when no such tool exists
   */
  public SimpleToolDefinition getToolDefinitionByMethodName(String methodName) {
    return this.getToolDefinitions().get(toSnakeCase(methodName));
  }

  /**
   * @param toolName the MCP tool name
   * @return the tool definition registered under that name, or null when no
   *         such tool exists
   */
  public SimpleToolDefinition getToolDefinition(String toolName) {
    return this.getToolDefinitions().get(toolName);
  }

  /**
   * Tells whether a tool call must be confirmed by the end user before it
   * runs. A tool marked as requiring approval only actually asks for one when
   * the caller holds the approval scope.
   *
   * @param methodName     the Java method name implementing the tool
   * @param authentication the current OAuth authentication
   * @return true when the call must be approved by the user first
   */
  public boolean isRequireApproval(String methodName, Authentication authentication) {
    SimpleToolDefinition toolDefinition = getToolDefinitionByMethodName(methodName);
    return toolDefinition != null
           && toolDefinition.isRequireApproval()
           && authentication.getAuthorities()
                            .stream()
                            .anyMatch(a -> WRITE_APPROVE_SCOPE_AUTHORITY.equals(a.getAuthority()));
  }

  /**
   * Same check as {@link #isAllowedTool(SimpleToolDefinition, Authentication)},
   * for a caller holding only a name: an MCP tool name or the Java method name
   * behind it, tried in that order.
   *
   * @param toolOrMethodName the MCP tool name or its Java method name
   * @param authentication   the current OAuth authentication
   * @return true when the caller may use the tool, false when the tool is
   *         unknown
   */
  public boolean isAllowedTool(String toolOrMethodName, Authentication authentication) { // NOSONAR
    SimpleToolDefinition toolDefinition = getToolDefinitionByMethodName(toolOrMethodName);
    if (toolDefinition == null) {
      toolDefinition = getToolDefinition(toolOrMethodName);
    }
    if (toolDefinition == null) {
      log.warn("Tool with name '{}' wasn't found", toolOrMethodName);
      return false;
    } else {
      return isAllowedTool(toolDefinition, authentication);
    }
  }

  /**
   * Second enforcement point of the MCP access gate, behind the token
   * introspector: tells whether the authenticated caller may execute, or even
   * see, one given tool.
   * <p>
   * The first condition used to read the global {@code mcp.server} flag alone,
   * which meant every authenticated user of the platform. It now asks the
   * per-user question instead — {@code ExoFeatureService.isFeatureActiveForUser}
   * checks that same global flag first, then delegates to
   * {@code McpServerFeaturePlugin}, so the global off switch keeps denying
   * exactly what it denied before and the audience narrows it further.
   * <p>
   * The internal client is exempt from both, as it has always been: EVA calls
   * its tools through the internal client-credentials grant, and that must keep
   * working while MCP is globally off and whatever audience an administrator
   * configures — an audience lists humans, and the internal client is not one.
   * The exemption is recognized by the OAuth client that owns the token
   * ({@link McpToolUtils#isInternalClientAuthentication(Authentication)}) and
   * no longer by the token subject, which is the user login on a user grant:
   * a user whose login happened to equal the internal client id used to be
   * exempt here, and is not any more.
   *
   * @param toolDefinition the tool being listed or called
   * @param authentication the current OAuth authentication
   * @return true when the caller may use the tool
   */
  public boolean isAllowedTool(SimpleToolDefinition toolDefinition, Authentication authentication) {
    // Verify that the call is internal call, else return Not Allowed
    if (!McpToolUtils.isInternalClientAuthentication(authentication) && !isMcpServerEnabledForUser(authentication)) {
      return false;
    }
    boolean canRead = CollectionUtils.isNotEmpty(authentication.getAuthorities())
                      && authentication.getAuthorities()
                                       .stream()
                                       .anyMatch(a -> READ_SCOPE_AUTHORITY.equals(a.getAuthority()));
    boolean canWrite = CollectionUtils.isNotEmpty(authentication.getAuthorities())
                       && authentication.getAuthorities()
                                        .stream()
                                        .anyMatch(a -> WRITE_SCOPE_AUTHORITY.equals(a.getAuthority())
                                                       || WRITE_APPROVE_SCOPE_AUTHORITY.equals(a.getAuthority()));
    Boolean readOnlyTool = toolDefinition.getAnnotations() == null ? null : toolDefinition.getAnnotations().readOnlyHint();
    if (readOnlyTool == null) {
      readOnlyTool = !toolDefinition.isRequireApproval();
    }
    return (readOnlyTool && canRead) || (!readOnlyTool && canWrite);
  }

  /**
   * @return every known tool definition by name, importing them from the
   *         classpath on first access
   */
  public Map<String, SimpleToolDefinition> getToolDefinitions() {
    if (MapUtils.isEmpty(toolDefinitions)) {
      this.retrieveToolDefinitions();
    }
    return toolDefinitions;
  }

  /**
   * Updates an administrable tool definition, persists the whole set and
   * notifies listeners so that a running instance picks the change up without
   * a restart.
   *
   * @param toolName        the MCP tool name
   * @param title           the new title
   * @param description     the new description shown to the LLM
   * @param inputSchema     the new JSON input schema
   * @param requireApproval whether calls must be approved by the end user
   * @param disabled        whether the tool is withdrawn from the server
   * @return the updated tool definition
   */
  @Synchronized
  public ToolDefinition updateToolDefinition(String toolName,
                                             String title,
                                             String description,
                                             String inputSchema,
                                             boolean requireApproval,
                                             boolean disabled) {
    log.info("Update AI Agent Tool '{}'", toolName);
    SimpleToolDefinition existingToolDefinition = this.getToolDefinition(toolName);
    existingToolDefinition.setTitle(title);
    existingToolDefinition.setDescription(description);
    existingToolDefinition.setInputSchema(inputSchema);
    existingToolDefinition.setRequireApproval(requireApproval);
    existingToolDefinition.setDisabled(disabled);
    if (existingToolDefinition.getAnnotations() == null) {
      existingToolDefinition.setAnnotations(new ToolAnnotations(title,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null));
    } else {
      existingToolDefinition.setAnnotations(new ToolAnnotations(title,
                                                                existingToolDefinition.getAnnotations().readOnlyHint(),
                                                                existingToolDefinition.getAnnotations().destructiveHint(),
                                                                existingToolDefinition.getAnnotations().idempotentHint(),
                                                                existingToolDefinition.getAnnotations().openWorldHint(),
                                                                existingToolDefinition.getAnnotations().returnDirect()));
    }
    this.saveToolsContent(McpToolUtils.toJsonStringBase64(new ToolDefinitionMethods(new ArrayList<>(toolDefinitions.values()))));
    toolListeners.forEach(l -> l.handleToolUpdate(toolName));
    listenerService.broadcast(EVENT_TOOL_UPDATED, toolName, existingToolDefinition);
    return existingToolDefinition;
  }

  /**
   * Registers a listener notified whenever a tool definition changes.
   *
   * @param listener the listener to add
   */
  public void addToolUpdateListener(ToolListener listener) {
    toolListeners.add(listener);
  }

  /**
   * @return true when the MCP server is globally switched on. This is the
   *         instance-wide on/off flag only: it says nothing about whether a
   *         given user belongs to the MCP audience — ask
   *         {@link #isMcpServerEnabledForUser(String)} for that.
   */
  public boolean isMcpServerEnabled() {
    if (mcpEnabled == null) {
      mcpEnabled = featureService.isActiveFeature(MCP_SERVER_FEATURE);
    }
    return mcpEnabled;
  }

  /**
   * Resolves the user behind an OAuth authentication and answers the per-user
   * question for them. The user is the token subject, which the authorization
   * server sets to the platform login on a user grant.
   *
   * @param authentication the current OAuth authentication, may be null
   * @return true when MCP is globally on and that user is in the audience
   */
  private boolean isMcpServerEnabledForUser(Authentication authentication) {
    return authentication != null && isMcpServerEnabledForUser(authentication.getName());
  }

  /**
   * Answers the per-user MCP question: is MCP globally on <em>and</em> is this
   * user in its audience? Both halves come from
   * {@code ExoFeatureService.isFeatureActiveForUser}, which checks the global
   * flag itself before delegating to {@code McpServerFeaturePlugin}.
   * <p>
   * Deliberately not memoised, unlike {@link #isMcpServerEnabled()}: the
   * answer depends on the user and on an audience an administrator may change
   * at any moment, and on a security input a stale <em>wide</em> answer is a
   * hole rather than an inconvenience.
   *
   * @param username the platform login of the end user, may be null or blank
   * @return true when that user may use the MCP server
   */
  public boolean isMcpServerEnabledForUser(String username) {
    return StringUtils.isNotBlank(username)
           && featureService.isFeatureActiveForUser(MCP_SERVER_FEATURE, username);
  }

  /**
   * Switches the MCP server on instance-wide and drops the memoised flag so
   * the change is visible on the next request.
   */
  public void enableMcpServer() {
    featureService.saveActiveFeature(MCP_SERVER_FEATURE, true);
    mcpEnabled = null;
  }

  /**
   * Switches the MCP server off instance-wide and drops the memoised flag so
   * the change is visible on the next request.
   */
  public void disableMcpServer() {
    featureService.saveActiveFeature(MCP_SERVER_FEATURE, false);
    mcpEnabled = null;
  }

  /**
   * Builds the tool registry: every {@code ai-tool-definitions.json} on the
   * portal classpath, each entry overridden by its persisted version when one
   * exists, then persisted back as the new reference set.
   */
  @SneakyThrows
  private void retrieveToolDefinitions() {
    String toolsContent = getToolsContent();
    List<SimpleToolDefinition> savedDefinitions;
    if (!forceReimport && StringUtils.isNotBlank(toolsContent)) {
      savedDefinitions = McpToolUtils.fromJsonStringBase64(toolsContent)
                                     .tools()
                                     .stream()
                                     .toList();
    } else {
      savedDefinitions = Collections.emptyList();
      settingService.remove(AI_AGENT_CONTEXT, TOOLS_SCOPE);
      forceReimport = false;
    }
    Enumeration<URL> toolDefinitionResources = container.getPortalClassLoader().getResources(AI_TOOLS_JSON_PATH);
    List<SimpleToolDefinition> toolDefinitionList = Collections.list(toolDefinitionResources)
                                                               .stream()
                                                               .map(McpToolUtils::parseToolDefinitions)
                                                               .flatMap(Collection::stream)
                                                               .map(toolDefinition -> savedDefinitions.stream()
                                                                                                      .filter(t -> t.getName()
                                                                                                                    .equals(toolDefinition.getName()))
                                                                                                      .findFirst()
                                                                                                      .orElse(toolDefinition))
                                                               .toList();
    saveToolsContent(McpToolUtils.toJsonStringBase64(new ToolDefinitionMethods(toolDefinitionList)));
    toolDefinitions = toolDefinitionList.stream() // NOSONAR
                                        .collect(Collectors.toMap(ToolDefinition::name,
                                                                  Function.identity(),
                                                                  ObjectUtils::firstNonNull));
  }

  /**
   * @return the persisted tool definitions as a base64 JSON string, or null
   *         when none were persisted yet
   */
  private String getToolsContent() {
    SettingValue<?> settingValue = settingService.get(AI_AGENT_CONTEXT,
                                                      TOOLS_SCOPE,
                                                      TOOLS_KEY);
    return settingValue == null || settingValue.getValue() == null ? null : settingValue.getValue().toString();
  }

  /**
   * Persists the tool definitions.
   *
   * @param content the whole set as a base64 JSON string
   */
  private void saveToolsContent(String content) {
    settingService.set(AI_AGENT_CONTEXT,
                       TOOLS_SCOPE,
                       TOOLS_KEY,
                       SettingValue.create(content));
  }

}

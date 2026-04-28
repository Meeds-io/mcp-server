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

  private static final String               TOOLS_KEY                     = "AI_AGENT_TOOL_DEFINITIONS_v13";

  private static final Scope                TOOLS_SCOPE                   = Scope.APPLICATION.id(TOOLS_KEY);

  private static final String               MCP_SERVER_FEATURE            = "mcp.server";

  @Autowired
  private PortalContainer                   container;

  @Autowired
  private SettingService                    settingService;

  @Autowired
  private ExoFeatureService                 featureService;

  @Autowired
  private McpInternalOAuthClientService     oAuthService;

  @Autowired
  private ListenerService                   listenerService;

  @Value("${meeds.mcp.tools.forceReimport:false}")
  private boolean                           forceReimport;

  private Boolean                           mcpEnabled;

  private List<ToolListener>                toolListeners                 = new ArrayList<>();

  private Map<String, SimpleToolDefinition> toolDefinitions;

  public SimpleToolDefinition getToolDefinitionByMethodName(String methodName) {
    return this.getToolDefinitions().get(toSnakeCase(methodName));
  }

  public SimpleToolDefinition getToolDefinition(String toolName) {
    return this.getToolDefinitions().get(toolName);
  }

  public boolean isRequireApproval(String methodName, Authentication authentication) {
    SimpleToolDefinition toolDefinition = getToolDefinitionByMethodName(methodName);
    return toolDefinition != null
           && toolDefinition.isRequireApproval()
           && authentication.getAuthorities()
                            .stream()
                            .anyMatch(a -> WRITE_APPROVE_SCOPE_AUTHORITY.equals(a.getAuthority()));
  }

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

  public boolean isAllowedTool(SimpleToolDefinition toolDefinition, Authentication authentication) {
    // Verify that the call is internal call, else return Not Allowed
    if (!isMcpServerEnabled() && !oAuthService.getClientRegistrationId().equals(authentication.getName())) {
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

  public Map<String, SimpleToolDefinition> getToolDefinitions() {
    if (MapUtils.isEmpty(toolDefinitions)) {
      this.retrieveToolDefinitions();
    }
    return toolDefinitions;
  }

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
    this.saveToolsContent(McpToolUtils.toJsonStringBase64(new ToolDefinitionMethods(new ArrayList<>(toolDefinitions.values()))));
    toolListeners.forEach(l -> l.handleToolUpdate(toolName));
    listenerService.broadcast(EVENT_TOOL_UPDATED, toolName, existingToolDefinition);
    return existingToolDefinition;
  }

  public void addToolUpdateListener(ToolListener listener) {
    toolListeners.add(listener);
  }

  public boolean isMcpServerEnabled() {
    if (mcpEnabled == null) {
      mcpEnabled = featureService.isActiveFeature(MCP_SERVER_FEATURE);
    }
    return mcpEnabled;
  }

  public void enableMcpServer() {
    featureService.saveActiveFeature(MCP_SERVER_FEATURE, true);
    mcpEnabled = null;
  }

  public void disableMcpServer() {
    featureService.saveActiveFeature(MCP_SERVER_FEATURE, false);
    mcpEnabled = null;
  }

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

  private String getToolsContent() {
    SettingValue<?> settingValue = settingService.get(AI_AGENT_CONTEXT,
                                                      TOOLS_SCOPE,
                                                      TOOLS_KEY);
    return settingValue == null || settingValue.getValue() == null ? null : settingValue.getValue().toString();
  }

  private void saveToolsContent(String content) {
    settingService.set(AI_AGENT_CONTEXT,
                       TOOLS_SCOPE,
                       TOOLS_KEY,
                       SettingValue.create(content));
  }

}

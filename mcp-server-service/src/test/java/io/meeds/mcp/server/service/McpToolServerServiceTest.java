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
package io.meeds.mcp.server.service;

import static io.meeds.mcp.server.service.McpServerToolService.READ_SCOPE_AUTHORITY;
import static io.meeds.mcp.server.service.McpServerToolService.WRITE_APPROVE_SCOPE_AUTHORITY;
import static io.meeds.mcp.server.service.McpServerToolService.WRITE_SCOPE_AUTHORITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.services.listener.ListenerService;

import io.meeds.mcp.server.listener.ToolListener;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.ToolDefinitionMethods;
import io.meeds.mcp.server.util.McpToolUtils;
import io.meeds.social.util.JsonUtils;

import lombok.SneakyThrows;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
class McpToolServerServiceTest {

  private static final String           TOOL_NAME            = "myTool";

  private static final String           NEW_TITLE            = "NewTitle";

  private static final String           NEW_DESCRIPTION      = "NewDesc";

  private static final String           FORCE_REIMPORT_PARAM = "forceReimport";

  private static final String           TYPE_OBJECT          = "{\"type\":\"object\"}";

  private static final String           BETA_TOOL            = "beta_tool";

  private static final String           ALPHA_TOOL           = "alpha_tool";

  @Mock
  private PortalContainer               container;

  @Mock
  private SettingService                settingService;

  @Mock
  private ListenerService               listenerService;

  @Mock
  private Authentication                authentication;

  @Mock
  private ExoFeatureService             featureService;

  @Mock
  private McpInternalOAuthClientService oAuthService;

  @Spy
  @InjectMocks
  private McpServerToolService          mcpServerToolService;

  @BeforeEach
  void init() {
    lenient().when(featureService.isActiveFeature(any())).thenReturn(true);
    lenient().when(oAuthService.getClientRegistrationId()).thenReturn("clientId");
  }

  @Test
  void getToolDefinitions_whenEmpty_importsFromClasspathResources_andPersists(// NOSONAR
                                                                              @TempDir
                                                                              Path tmp) {
    ReflectionTestUtils.setField(mcpServerToolService, FORCE_REIMPORT_PARAM, true);

    when(settingService.get(any(), any(), any())).thenReturn(null);

    SimpleToolDefinition t1 = tool(ALPHA_TOOL, "Alpha", TYPE_OBJECT, false, false);
    SimpleToolDefinition t2 = tool(BETA_TOOL, "Beta", TYPE_OBJECT, true, false);

    URL url = writeToolDefinitionsJson(tmp, List.of(t1, t2));
    mockContainerResources(url);

    Map<String, SimpleToolDefinition> defs = mcpServerToolService.getToolDefinitions();

    assertNotNull(defs); // NOSONAR
    assertEquals(2, defs.size());
    assertEquals("Alpha", defs.get(ALPHA_TOOL).getDescription());
    assertTrue(defs.get(BETA_TOOL).isRequireApproval());

    verify(settingService).set(any(), any(), any(), any());

    assertFalse((boolean) ReflectionTestUtils.getField(mcpServerToolService, FORCE_REIMPORT_PARAM));
  }

  @Test
  void getToolDefinitions_mergesSavedDefinitions_whenNotForceReimport(// NOSONAR
                                                                      @TempDir
                                                                      Path tmp) {
    ReflectionTestUtils.setField(mcpServerToolService, FORCE_REIMPORT_PARAM, false);

    SimpleToolDefinition parsed = tool(ALPHA_TOOL, "ParsedDesc", TYPE_OBJECT, false, false);
    SimpleToolDefinition other = tool("gamma_tool", "Gamma", TYPE_OBJECT, false, false);

    SimpleToolDefinition savedOverride = tool(ALPHA_TOOL, "SavedDesc", "{\"type\":\"object\",\"saved\":true}", true, true);
    String savedBase64 = McpToolUtils.toJsonStringBase64(new ToolDefinitionMethods(List.of(savedOverride)));

    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(savedBase64));

    URL url = writeToolDefinitionsJson(tmp, List.of(parsed, other));
    mockContainerResources(url);

    Map<String, SimpleToolDefinition> defs = mcpServerToolService.getToolDefinitions();

    assertEquals(2, defs.size());

    SimpleToolDefinition alpha = defs.get(ALPHA_TOOL);
    assertNotNull(alpha);
    assertEquals("SavedDesc", alpha.getDescription());
    assertTrue(alpha.isRequireApproval());
    assertTrue(alpha.isDisabled());
    assertTrue(alpha.getInputSchema().contains("\"saved\":true"));

    assertEquals("Gamma", defs.get("gamma_tool").getDescription());

    verify(settingService).set(any(), any(), any(), any());
  }

  @Test
  void isRequireApproval_usesSnakeCaseMethodNameLookup() {// NOSONAR
    SimpleToolDefinition tool = tool("my_tool", "desc", "{}", true, false);
    ReflectionTestUtils.setField(mcpServerToolService, "toolDefinitions", Map.of("my_tool", tool));

    assertFalse(mcpServerToolService.isRequireApproval(TOOL_NAME, authentication));
    when(authentication.getAuthorities()).thenAnswer(invocation -> Collections.singleton(new SimpleGrantedAuthority(WRITE_APPROVE_SCOPE_AUTHORITY)));
    assertTrue(mcpServerToolService.isRequireApproval(TOOL_NAME, authentication));
    assertFalse(mcpServerToolService.isRequireApproval("unknownTool", null));
  }

  @Test
  void shouldReturnFalseWhenToolNotFound() {
    doReturn(null).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    doReturn(null).when(mcpServerToolService).getToolDefinition(TOOL_NAME);

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertFalse(result);
    verify(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    verify(mcpServerToolService).getToolDefinition(TOOL_NAME);
  }

  @Test
  void shouldAllowWhenToolRequiresApprovalAndUserHasWriteScope() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(true);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(WRITE_SCOPE_AUTHORITY)));

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertTrue(result);
  }

  @Test
  void shouldNotAllowWhenMcpServerIsDisabled() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(featureService.isActiveFeature(any())).thenReturn(false);

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertFalse(result);
  }

  @Test
  void shouldAllowWhenMcpServerInternalToolInvocation() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);

    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(READ_SCOPE_AUTHORITY)));
    when(featureService.isActiveFeature(any())).thenReturn(false);
    String clientId = oAuthService.getClientRegistrationId();
    when(authentication.getName()).thenReturn(clientId);

    boolean result = mcpServerToolService.isAllowedTool(toolDefinition, authentication);

    assertTrue(result);
  }

  @Test
  void shouldAllowWhenToolRequiresApprovalAndUserHasWriteApproveScope() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(true);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(WRITE_APPROVE_SCOPE_AUTHORITY)));

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertTrue(result);
  }

  @Test
  void shouldDenyWhenToolRequiresApprovalAndUserHasOnlyReadScope() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(true);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(READ_SCOPE_AUTHORITY)));

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertFalse(result);
  }

  @Test
  void shouldAllowWhenToolDoesNotRequireApprovalAndUserHasReadScope() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(false);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(READ_SCOPE_AUTHORITY)));

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertTrue(result);
  }

  @Test
  void shouldDenyWhenToolDoesNotRequireApprovalAndUserHasOnlyWriteScope() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(false);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(WRITE_SCOPE_AUTHORITY)));

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertFalse(result);
  }

  @Test
  void shouldFallbackToGetToolDefinitionWhenMethodNameLookupReturnsNull() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(false);

    doReturn(null).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinition(TOOL_NAME);
    when(authentication.getAuthorities()).thenAnswer(invocation -> List.of(new SimpleGrantedAuthority(READ_SCOPE_AUTHORITY)));

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertTrue(result);
    verify(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    verify(mcpServerToolService).getToolDefinition(TOOL_NAME);
  }

  @Test
  void shouldDenyWhenAuthoritiesAreNull() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(false);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenReturn(null);

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertFalse(result);
  }

  @Test
  void shouldDenyWhenAuthoritiesAreEmpty() {
    SimpleToolDefinition toolDefinition = mock(SimpleToolDefinition.class);
    when(toolDefinition.isRequireApproval()).thenReturn(false);

    doReturn(toolDefinition).when(mcpServerToolService).getToolDefinitionByMethodName(TOOL_NAME);
    when(authentication.getAuthorities()).thenReturn(List.of());

    boolean result = mcpServerToolService.isAllowedTool(TOOL_NAME, authentication);

    assertFalse(result);
  }

  @Test
  void updateToolDefinition_updatesFields_persistsBase64_andNotifiesListeners() { // NOSONAR
    SimpleToolDefinition existing = tool(ALPHA_TOOL, "Old", "{\"a\":1}", false, false);
    ReflectionTestUtils.setField(mcpServerToolService, "toolDefinitions", Map.of(ALPHA_TOOL, existing));

    ToolListener listener = mock(ToolListener.class);
    mcpServerToolService.addToolUpdateListener(listener);

    ArgumentCaptor<SettingValue<?>> settingValueCaptor = ArgumentCaptor.forClass(SettingValue.class);

    SimpleToolDefinition updated = (SimpleToolDefinition) mcpServerToolService.updateToolDefinition(ALPHA_TOOL,
                                                                                                    NEW_TITLE,
                                                                                                    NEW_DESCRIPTION,
                                                                                                    "{\"type\":\"object\",\"new\":true}",
                                                                                                    true,
                                                                                                    true);

    assertSame(existing, updated);
    assertEquals(NEW_TITLE, updated.title());
    assertEquals(NEW_DESCRIPTION, updated.description());
    assertEquals("{\"type\":\"object\",\"new\":true}", updated.inputSchema());
    assertTrue(updated.isRequireApproval());
    assertTrue(updated.isDisabled());

    verify(settingService).set(any(), any(), any(), settingValueCaptor.capture());
    Object persistedValue = settingValueCaptor.getValue().getValue();
    assertNotNull(persistedValue);

    ToolDefinitionMethods decoded = McpToolUtils.fromJsonStringBase64(persistedValue.toString());
    assertNotNull(decoded);
    assertEquals(1, decoded.tools().size());
    assertEquals(ALPHA_TOOL, decoded.tools().get(0).getName());
    assertEquals(NEW_DESCRIPTION, decoded.tools().get(0).getDescription());
    assertTrue(decoded.tools().get(0).isRequireApproval());
    assertTrue(decoded.tools().get(0).isDisabled());

    verify(listener).handleToolUpdate(ALPHA_TOOL);
  }

  @SneakyThrows
  private void mockContainerResources(URL url) {
    ClassLoader cl = mock(ClassLoader.class);
    Enumeration<URL> enumeration = Collections.enumeration(List.of(url));

    when(container.getPortalClassLoader()).thenReturn(cl);
    when(cl.getResources("ai-tool-definitions.json")).thenReturn(enumeration);
  }

  @SneakyThrows
  private URL writeToolDefinitionsJson(Path tmp, List<SimpleToolDefinition> tools) {
    String json = JsonUtils.toJsonString(new ToolDefinitionMethods(tools));
    Path file = tmp.resolve("ai-tool-definitions.json");
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file.toUri().toURL();
  }

  private static SimpleToolDefinition tool(String name,
                                           String description,
                                           String inputSchema,
                                           boolean requireApproval,
                                           boolean disabled) {
    SimpleToolDefinition t = new SimpleToolDefinition();
    t.setName(name);
    t.setDescription(description);
    t.setInputSchema(inputSchema);
    t.setRequireApproval(requireApproval);
    t.setDisabled(disabled);
    return t;
  }

}

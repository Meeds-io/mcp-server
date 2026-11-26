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
package io.meeds.mcp.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;

import org.exoplatform.services.listener.ListenerService;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.modelcontextprotocol.spec.McpSchema.Tool;

@ExtendWith(MockitoExtension.class)
class McpToolListingCacheTest {

  private static final String      ALPHA_TOOL = "alpha_tool";

  private static final String      BETA_TOOL  = "beta_tool";

  @Mock
  private SettingService           settingService;

  @Mock
  private ListenerService          listenerService;

  @Mock
  private ExoFeatureService        featureService;

  @Mock
  private McpServerAudienceService audienceService;

  @Spy
  @InjectMocks
  private McpServerToolService     toolService;

  private McpToolListingCache      cache;

  private AtomicInteger            builds     = new AtomicInteger();

  private Authentication           caller;

  @BeforeEach
  void init() {
    lenient().when(featureService.isActiveFeature(any())).thenReturn(true);
    ReflectionTestUtils.setField(toolService,
                                 "toolDefinitions",
                                 Map.of(ALPHA_TOOL, definition(ALPHA_TOOL), BETA_TOOL, definition(BETA_TOOL)));
    cache = new McpToolListingCache(toolService, () -> {
      builds.incrementAndGet();
      return List.of(tool(ALPHA_TOOL),
                     tool(BETA_TOOL));
    });
    caller = new TestingAuthenticationToken("caller",
                                                                                        "n/a",
                                                                                        List.of(new SimpleGrantedAuthority(McpServerToolService.READ_SCOPE_AUTHORITY)));
  }

  @Test
  @DisplayName("A tool switched off by another node is not listed")
  void aSwitchOffStoredByAnotherNodeIsNotListed() {
    assertEquals(List.of(ALPHA_TOOL, BETA_TOOL), listedNames());

    storedFlags(Map.of(ALPHA_TOOL, SettingValue.create("true")));

    assertEquals(List.of(BETA_TOOL), listedNames());
  }

  @Test
  @DisplayName("A tool switched on by another node is listed although the entry was built while it was off")
  void aSwitchOnStoredByAnotherNodeIsListedAfterAnEntryBuiltWhileItWasOff() {
    storedFlags(Map.of(ALPHA_TOOL, SettingValue.create("true")));
    assertEquals(List.of(BETA_TOOL), listedNames());

    storedFlags(Map.of(ALPHA_TOOL, SettingValue.create("false")));

    assertEquals(List.of(ALPHA_TOOL, BETA_TOOL), listedNames());
    assertEquals(1, builds.get(), "the entry is built once, the flag is asked at every read");
  }

  @Test
  @DisplayName("A failed read of the flags lists nothing, and the next listing after recovery lists the tools")
  void aFailedReadIsNotCachedAndTheNextListingAfterRecoveryListsTheTools() {
    when(settingService.getSettingsByContextAndScope(any(), any(), any(), any())).thenThrow(new RuntimeException("store down"))
                                                                                  .thenReturn(Map.of());

    assertEquals(List.of(), listedNames());
    assertEquals(List.of(ALPHA_TOOL, BETA_TOOL), listedNames());
  }

  private List<String> listedNames() {
    return cache.list(caller).block().tools().stream().map(Tool::name).toList();
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private void storedFlags(Map<String, SettingValue<?>> flags) {
    when(settingService.getSettingsByContextAndScope(eq("GLOBAL"),
                                                     eq("AI_AGENT"),
                                                     eq("APPLICATION"),
                                                     startsWith("AI_AGENT_TOOL_DISABLED"))).thenReturn((Map) flags);
  }

  private static Tool tool(String name) {
    return new Tool(name, null, null, Map.of("type", "object"), null, null, null, null);
  }

  private static SimpleToolDefinition definition(String name) {
    SimpleToolDefinition definition = new SimpleToolDefinition();
    definition.setName(name);
    return definition;
  }

}

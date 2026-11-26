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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

/**
 * The {@code tools/list} answer: the server's tools the caller's scopes allow,
 * memoised per distinct scope set, then stripped at every read of the tools an
 * administrator switched off.
 * <p>
 * The cached entry is built on the scope check alone and never asks the
 * switched-off flag, so it holds no state that another node can change: a tool
 * switched off or on anywhere is listed or dropped at the very next read,
 * whatever this cache holds. The flag is asked once per listing.
 * <p>
 * The key holds the scopes and not the caller, on an invariant this cache
 * depends on: the door ({@code McpServerOauthOpaqueTokenIntrospector}) has
 * already refused any caller outside the MCP audience before a handler runs,
 * and the scope check answers on the global flag and the scopes alone -
 * all-or-nothing per user, never per tool - so every caller reaching the
 * listing with the same scopes sees the same list. Should the audience ever
 * become per-tool or per-group, the caller goes back into the key, and the map
 * then also needs an eviction policy: it only ever grows until a tool
 * definition changes.
 */
public class McpToolListingCache {

  private final Map<List<String>, Mono<McpSchema.ListToolsResult>> cache = new ConcurrentHashMap<>();

  private final McpServerToolService                               toolService;

  private final Supplier<List<Tool>>                               toolsSupplier;

  /**
   * @param toolService   the tool service, asked the scope check and the
   *                      switched-off tools
   * @param toolsSupplier the tools the MCP server exposes
   */
  public McpToolListingCache(McpServerToolService toolService, Supplier<List<Tool>> toolsSupplier) {
    this.toolService = toolService;
    this.toolsSupplier = toolsSupplier;
  }

  /**
   * @param authentication the caller's authentication, carrying the token's
   *                       scopes as {@code SCOPE_*} authorities
   * @return the tools the caller's scopes allow, but the ones switched off
   */
  public Mono<McpSchema.ListToolsResult> list(Authentication authentication) {
    List<String> key = authentication.getAuthorities()
                                     .stream()
                                     .map(GrantedAuthority::getAuthority)
                                     .filter(a -> a.startsWith("SCOPE_"))
                                     .distinct()
                                     .sorted()
                                     .toList();
    return cache.computeIfAbsent(key, k -> {
      List<Tool> tools = toolsSupplier.get()
                                      .stream()
                                      .filter(t -> toolService.isScopeAllowedTool(t.name(), authentication))
                                      .toList();
      return Mono.just(McpSchema.ListToolsResult.builder(tools).build());
    }).map(this::withoutSwitchedOffTools);
  }

  /**
   * Drops every cached answer. Called when a tool definition changes, so a
   * changed approval requirement is visible on the next listing.
   */
  public void clear() {
    cache.clear();
  }

  private McpSchema.ListToolsResult withoutSwitchedOffTools(McpSchema.ListToolsResult result) {
    Set<String> switchedOff = toolService.getDisabledToolNames();
    return McpSchema.ListToolsResult.builder(result.tools()
                                                   .stream()
                                                   .filter(t -> !switchedOff.contains(t.name()))
                                                   .toList())
                                    .build();
  }

}

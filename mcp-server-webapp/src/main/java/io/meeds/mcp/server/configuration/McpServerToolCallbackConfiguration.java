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
package io.meeds.mcp.server.configuration;

import java.util.List;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.exoplatform.portal.config.UserACL;

import io.meeds.mcp.server.configuration.model.McpServerOAuthClientProperties;
import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.service.McpToolApprovalService;
import io.meeds.mcp.server.service.McpToolCallbackProviderService;
import io.meeds.mcp.server.service.McpServerToolService;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableConfigurationProperties({
  McpServerProperties.class,
  McpServerStreamableHttpProperties.class,
  McpServerOAuthClientProperties.class,
})
@Slf4j
public class McpServerToolCallbackConfiguration {

  @Bean
  public ToolCallbackProvider mcpToolCallbackProvider(ApplicationContext applicationContext,
                                                      McpServerToolService mcpServerToolService,
                                                      McpToolApprovalService mcpToolApprovalService,
                                                      UserACL userAcl,
                                                      List<McpToolPlugin> tools) {
    McpToolCallbackProviderService toolCallbackProvider = new McpToolCallbackProviderService(applicationContext,
                                                                                             mcpServerToolService,
                                                                                             mcpToolApprovalService,
                                                                                             userAcl,
                                                                                             tools);
    mcpServerToolService.addToolUpdateListener(toolCallbackProvider::updateToolDefinition);
    return toolCallbackProvider;
  }

}

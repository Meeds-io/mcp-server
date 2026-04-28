/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.meeds.mcp.server.configuration;

import static io.meeds.mcp.server.util.McpServerUtils.aggregateToolCallbacks;
import static io.meeds.mcp.server.util.McpServerUtils.toSyncToolSpecifications;

import java.util.List;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.server.McpServerFeatures;

@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
@ConditionalOnProperty(prefix = McpServerProperties.CONFIG_PREFIX, name = "type", havingValue = "SYNC", matchIfMissing = true)
public class McpServerToolCallbackSyncConverterConfiguration {

  @Bean
  public List<McpServerFeatures.SyncToolSpecification> syncTools(ObjectProvider<List<ToolCallback>> toolCalls,
                                                                 List<ToolCallback> toolCallbacksList,
                                                                 List<ToolCallbackProvider> toolCallbackProvider) {
    List<ToolCallback> tools = aggregateToolCallbacks(toolCalls, toolCallbacksList, toolCallbackProvider);
    return toSyncToolSpecifications(tools);
  }

}

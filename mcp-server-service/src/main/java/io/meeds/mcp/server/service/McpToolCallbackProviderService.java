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

import static io.meeds.mcp.server.util.McpToolUtils.getCurrentUserName;
import static io.meeds.mcp.server.util.McpToolUtils.getMethodToolFieldValue;
import static io.meeds.mcp.server.util.McpToolUtils.toCamelCase;
import static io.meeds.mcp.server.util.McpToolUtils.toSnakeCase;
import static io.meeds.mcp.server.util.McpServerUtils.getMimeType;
import static io.meeds.mcp.server.util.McpServerUtils.toAsyncToolSpecification;
import static io.meeds.mcp.server.util.McpServerUtils.toSyncToolSpecification;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MimeType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.type.TypeReference;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.common.ContainerTransactional;
import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.UserToolDeniedException;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.model.UserToolExecution.UserToolExecutionBuilder;
import io.meeds.mcp.server.model.UserToolTimeoutException;
import io.meeds.mcp.server.plugin.McpToolPlugin;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class McpToolCallbackProviderService implements ToolCallbackProvider {

  private ApplicationContext     applicationContext;

  private McpServerToolService   mcpServerToolService;

  private McpToolApprovalService mcpToolApprovalService;

  private UserACL                userAcl;

  private List<McpToolPlugin>    toolObjects;

  @Override
  public ToolCallback[] getToolCallbacks() {
    ToolCallback[] toolCallbacks = toolObjects.stream()
                                              .map(toolObject -> Stream.of(getToolClassMethods(toolObject))
                                                                       .filter(ReflectionUtils.USER_DECLARED_METHODS::matches)
                                                                       .filter(m -> Modifier.isPublic(m.getModifiers()))
                                                                       .map(m -> toToolCallback(toolObject, m))
                                                                       .filter(Objects::nonNull))
                                              .flatMap(Function.identity())
                                              .toArray(ToolCallback[]::new);
    log.info("Retrieved ToolCallbacks: {}", toolCallbacks.length);
    validateToolCallbacks(toolCallbacks);
    return toolCallbacks;
  }

  @SneakyThrows
  public void updateToolDefinition(String toolName) {
    ToolCallback toolCallback = buildToolCallback(toolName);
    if (toolCallback == null) {
      throw new ObjectNotFoundException("Tool with name '%s' not found".formatted(toolName));
    }
    MimeType mimeType = getMimeType(toolName);
    try {
      McpAsyncServer mcpAsyncServer = applicationContext.getBean(McpAsyncServer.class);
      AsyncToolSpecification asyncToolSpecification = toAsyncToolSpecification(toolCallback, mimeType);
      mcpAsyncServer.removeTool(toolName);
      mcpAsyncServer.addTool(asyncToolSpecification);
      mcpAsyncServer.notifyToolsListChanged();
    } catch (NoSuchBeanDefinitionException e) {
      McpSyncServer mcpSyncServer = applicationContext.getBean(McpSyncServer.class);
      SyncToolSpecification syncToolSpecification = toSyncToolSpecification(toolCallback, mimeType);
      mcpSyncServer.removeTool(toolName);
      mcpSyncServer.addTool(syncToolSpecification);
      mcpSyncServer.notifyToolsListChanged();
    }
    log.info("Tool '{}' Definition Updated on MCP Server", toolName);
  }

  private Method[] getToolClassMethods(McpToolPlugin toolObject) {
    return ReflectionUtils.getDeclaredMethods(AopUtils.isAopProxy(toolObject) ?
                                                                              AopUtils.getTargetClass(toolObject) :
                                                                              toolObject.getClass());
  }

  private ToolCallback buildToolCallback(String toolName) {
    return toolObjects.stream()
                      .map(toolObject -> Stream.of(getToolClassMethods(toolObject))
                                               .filter(m -> toolName.equals(toSnakeCase(m.getName())))
                                               .filter(ReflectionUtils.USER_DECLARED_METHODS::matches)
                                               .filter(m -> Modifier.isPublic(m.getModifiers()))
                                               .map(m -> toToolCallback(toolObject, m))
                                               .filter(Objects::nonNull))
                      .flatMap(Function.identity())
                      .filter(Objects::nonNull)
                      .findFirst()
                      .orElse(null);
  }

  private ToolCallback toToolCallback(McpToolPlugin toolObject, Method toolMethod) {
    ToolDefinition toolDefinition = mcpServerToolService.getToolDefinitionByMethodName(toolMethod.getName());
    if (toolDefinition == null) {
      return null;
    } else {
      MethodToolCallback toolCallback = MethodToolCallback.builder()
                                                          .toolDefinition(toolDefinition)
                                                          .toolMetadata(ToolMetadata.from(toolMethod))
                                                          .toolMethod(toolMethod)
                                                          .toolObject(toolObject)
                                                          .toolCallResultConverter(ToolUtils.getToolCallResultConverter(toolMethod))
                                                          .build();
      return new MethodToolCallbackWrapper(mcpServerToolService,
                                           mcpToolApprovalService,
                                           userAcl,
                                           toolCallback);
    }
  }

  private void validateToolCallbacks(ToolCallback[] toolCallbacks) {
    List<String> duplicateToolNames = ToolUtils.getDuplicateToolNames(toolCallbacks);
    if (!duplicateToolNames.isEmpty()) {
      throw new IllegalStateException("Multiple tools with the same name (%s) found in sources: %s".formatted(
                                                                                                              String.join(", ",
                                                                                                                          duplicateToolNames),
                                                                                                              this.toolObjects.stream()
                                                                                                                              .map(o -> o.getClass()
                                                                                                                                         .getName())
                                                                                                                              .collect(Collectors.joining(", "))));
    }
  }

  public static class MethodToolCallbackWrapper implements ToolCallback {

    private static final String          LLM_ERROR_EXPLANATION              =
                                                               "Error calling Tool '%s'. Please check the allowed Tool input types. The original input was: %s.";

    private static final String          TOOL_CONTEXT_CONVERSATION_ID_PARAM = "conversationId";

    private final McpServerToolService   mcpServerToolService;

    private final McpToolApprovalService mcpToolApprovalService;

    private final UserACL                userAcl;

    private final MethodToolCallback     toolCallback;

    private final Object                 toolObject;

    private final Method                 toolMethod;

    @SneakyThrows
    public MethodToolCallbackWrapper(McpServerToolService mcpServerToolService,
                                     McpToolApprovalService mcpToolApprovalService,
                                     UserACL userAcl,
                                     MethodToolCallback toolCallback) {
      this.mcpServerToolService = mcpServerToolService;
      this.mcpToolApprovalService = mcpToolApprovalService;
      this.userAcl = userAcl;
      this.toolCallback = toolCallback;
      this.toolObject = getMethodToolFieldValue(toolCallback, "toolObject");
      this.toolMethod = (Method) getMethodToolFieldValue(toolCallback, "toolMethod");
    }

    @Override
    @SneakyThrows
    public String call(String toolInput, ToolContext toolContext) {
      try {
        String username = getCurrentUserName();
        Identity userIdentity = userAcl.getUserIdentity(username);
        return call(toolInput, toolContext, userIdentity);
      } catch (Exception e) {
        log.warn("Error while call Tool with input '{}'", toolInput, e);
        throw e;
      }
    }

    @Override
    public String call(String toolInput) {
      return call(toolInput, null);
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return mcpServerToolService.getToolDefinitionByMethodName(toolMethod.getName());
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return toolCallback.getToolMetadata();
    }

    @ContainerTransactional
    private String call(String toolInput, ToolContext toolContext, Identity userIdentity) throws Exception {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      String id = UUID.randomUUID().toString();
      String conversationId = getConversationId();
      ConversationState.setCurrent(new ConversationState(userIdentity));
      UserToolExecutionBuilder executionBuilder = UserToolExecution.builder()
                                                                   .id(id)
                                                                   .conversationId(conversationId)
                                                                   .username(userIdentity.getUserId())
                                                                   .toolName(toolMethod.getName())
                                                                   .startTime(System.currentTimeMillis())
                                                                   .toolInput(toolInput);
      try {
        if (!mcpServerToolService.isAllowedTool(toolMethod.getName(), authentication)) {
          throw new IllegalAccessException("Tool '%s' execution isn't allowed switch selected scopes".formatted(toolMethod.getName()));
        } else if (mcpServerToolService.isRequireApproval(toolMethod.getName(), authentication)) {
          mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.APPROVAL_REQUEST)
                                                                    .build());
          boolean approved = mcpToolApprovalService.requestApproval(id,
                                                                    conversationId,
                                                                    toolMethod.getName(),
                                                                    toolInput,
                                                                    userIdentity.getUserId());
          if (!approved) {
            throw new UserToolDeniedException("Tool execution aborted. As LLM, give an answer to the User: 'You denied the execution thus ...'.");
          }
        }
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_START)
                                                                  .build());
        // Call the Tool
        Map<String, Object> toolArguments = extractToolArguments(toolInput);
        toolArguments = transformSnakeToCamelCaseArguments(toolArguments);
        toolInput = JsonParser.toJson(toolArguments);
        String toolOutput = toolCallback.call(toolInput, toolContext);
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_FINISHED)
                                                                  .toolOutput(toolOutput)
                                                                  .completed(true)
                                                                  .build());
        log.debug("Call Tool '{}#{}' with input: {}. Output: {}",
                  toolObject.getClass().getName(),
                  toolMethod.getName(),
                  toolInput,
                  toolOutput);
        return toolOutput;
      } catch (UserToolDeniedException e) {
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_DENIED)
                                                                  .completed(true)
                                                                  .build());
        throw e;
      } catch (UserToolTimeoutException e) {
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.APPROVAL_TIMEOUT)
                                                                  .completed(true)
                                                                  .build());
        throw e;
      } catch (Exception e) {
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_ERROR)
                                                                  .toolOutput("Error: %s".formatted(e.getMessage()))
                                                                  .completed(true)
                                                                  .build());
        log.error("Error calling Tool '{}#{}' with input: {}",
                  toolObject.getClass().getName(),
                  toolMethod.getName(),
                  toolInput,
                  e);
        if (e instanceof IllegalArgumentException
            || e instanceof IllegalStateException
            || e instanceof ObjectNotFoundException
            || e instanceof IllegalAccessException) {
          throw e;
        } else {
          throw new IllegalStateException(LLM_ERROR_EXPLANATION.formatted(toolMethod.getName(),
                                                                          toolInput),
                                          e);
        }
      } finally {
        ConversationState.setCurrent(null);
      }
    }

    private String getConversationId() {
      if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletRequestAttributes) {
        // Workaround until the ToolContext attributes are mapped as well
        // (for now, only 'exchange' value)
        HttpServletRequest request = servletRequestAttributes.getRequest();
        return request.getHeader(TOOL_CONTEXT_CONVERSATION_ID_PARAM);
      } else {
        return null;
      }
    }

    private Map<String, Object> extractToolArguments(String toolInput) {
      return JsonParser.fromJson(toolInput, new TypeReference<>() {
      });
    }

    private Map<String, Object> transformSnakeToCamelCaseArguments(Map<String, Object> toolArguments) {
      return toolArguments.entrySet()
                          .stream()
                          .collect(Collectors.toMap(e -> toCamelCase(e.getKey()),
                                                    Entry::getValue,
                                                    ObjectUtils::firstNonNull));
    }

  }

}

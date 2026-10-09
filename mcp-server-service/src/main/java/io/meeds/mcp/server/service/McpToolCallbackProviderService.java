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

import static io.meeds.mcp.server.util.McpServerUtils.getMimeType;
import static io.meeds.mcp.server.util.McpServerUtils.toAsyncToolSpecification;
import static io.meeds.mcp.server.util.McpServerUtils.toSyncToolSpecification;
import static io.meeds.mcp.server.util.McpToolUtils.getClientId;
import static io.meeds.mcp.server.util.McpToolUtils.getCurrentActingIdentity;
import static io.meeds.mcp.server.util.McpToolUtils.getCurrentAgentNameId;
import static io.meeds.mcp.server.util.McpToolUtils.getCurrentConversationId;
import static io.meeds.mcp.server.util.McpToolUtils.getCurrentUserName;
import static io.meeds.mcp.server.util.McpToolUtils.getMethodToolFieldValue;
import static io.meeds.mcp.server.util.McpToolUtils.isCurrentCallRetry;
import static io.meeds.mcp.server.util.McpToolUtils.toCamelCase;
import static io.meeds.mcp.server.util.McpToolUtils.toSnakeCase;

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
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.ai.util.JsonHelper;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MimeType;
import org.springframework.util.ReflectionUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.common.ContainerTransactional;
import io.meeds.mcp.server.constant.PrincipalKind;
import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.ActingIdentity;
import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.model.McpToolGrantRequest;
import io.meeds.mcp.server.model.UserToolDeniedException;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.model.UserToolExecution.UserToolExecutionBuilder;
import io.meeds.mcp.server.model.UserToolRefusedException;
import io.meeds.mcp.server.model.UserToolTimeoutException;
import io.meeds.mcp.server.plugin.McpToolPlugin;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class McpToolCallbackProviderService implements ToolCallbackProvider {

  private static final JsonHelper jsonHelper = new JsonHelper();

  private ApplicationContext      applicationContext;

  private McpServerToolService    mcpServerToolService;

  private McpToolApprovalService  mcpToolApprovalService;

  private UserACL                 userAcl;

  private List<McpToolPlugin>     toolObjects;

  private McpToolGrantService     mcpToolGrantService;

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
                                           mcpToolGrantService,
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

    private static final String          LLM_ERROR_EXPLANATION            =
                                                             "Error calling Tool '%s'. Please check the allowed Tool input types. The original input was: %s.";

    private static final String          LLM_ALWAYS_ASK_EXPLANATION       =
                                                             "Tool '%s' requires the user's approval every time, and this caller can't ask for it. As LLM, tell the user that this tool can't be executed from here.";

    private static final String          LLM_NO_CONVERSATION_EXPLANATION  =
                                                             "Tool '%s' requires the user's approval, which can only be requested from a Meeds AI chat conversation, and this call carries no conversation. As LLM, tell the user that this tool can't be executed from here.";

    private static final String          LLM_NO_APPROVER_EXPLANATION      =
                                                             "Tool '%s' requires an approval, and this call is made by an agent acting as itself, for whom nobody can approve it. As LLM, state in your answer that this tool can't be run without a standing approval.";

    private final McpServerToolService   mcpServerToolService;

    private final McpToolApprovalService mcpToolApprovalService;

    private final McpToolGrantService    mcpToolGrantService;

    private final UserACL                userAcl;

    private final MethodToolCallback     toolCallback;

    private final Object                 toolObject;

    private final Method                 toolMethod;

    /**
     * @param mcpServerToolService   the tool registry
     * @param mcpToolApprovalService the approval cards
     * @param mcpToolGrantService    the standing approvals, may be null (no
     *                                 grant ever applies)
     * @param userAcl                the platform ACL
     * @param toolCallback           the wrapped tool method
     */
    @SneakyThrows
    public MethodToolCallbackWrapper(McpServerToolService mcpServerToolService,
                                     McpToolApprovalService mcpToolApprovalService,
                                     McpToolGrantService mcpToolGrantService,
                                     UserACL userAcl,
                                     MethodToolCallback toolCallback) {
      this.mcpServerToolService = mcpServerToolService;
      this.mcpToolApprovalService = mcpToolApprovalService;
      this.mcpToolGrantService = mcpToolGrantService;
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

    /**
     * Runs one tool call as the user: the acting identity check, the scope
     * check, then for an approval-gated or "always ask" tool the standing
     * approval or the card, then the tool itself, each step traced to the
     * user's chat with who acted, for whom and through which agents.
     * <p>
     * The bound ACL identity is the acting identity's subject, and nothing
     * else: the call is refused, traced as an error, when the identity the
     * server resolved names another account than the one the call runs as,
     * or a combination the server refuses.
     *
     * @param toolInput    the call input
     * @param toolContext  the Spring AI tool context
     * @param userIdentity the user the tool runs as
     * @return the tool output
     * @throws Exception when the call is refused, denied, times out or fails
     */
    @ContainerTransactional
    private String call(String toolInput, ToolContext toolContext, Identity userIdentity) throws Exception {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      String id = UUID.randomUUID().toString();
      String conversationId = getCurrentConversationId();
      ConversationState.setCurrent(new ConversationState(userIdentity));
      UserToolExecutionBuilder executionBuilder = UserToolExecution.builder()
                                                                   .id(id)
                                                                   .conversationId(conversationId)
                                                                   .username(userIdentity.getUserId())
                                                                   .toolName(toolMethod.getName())
                                                                   .startTime(System.currentTimeMillis())
                                                                   .toolInput(toolInput);
      try {
        ActingIdentity actingIdentity = resolveActingIdentity(userIdentity.getUserId());
        withActingIdentity(executionBuilder, actingIdentity);
        if (!mcpServerToolService.isAllowedTool(toolMethod.getName(), authentication)) {
          throw new IllegalAccessException("Tool '%s' execution isn't allowed switch selected scopes".formatted(toolMethod.getName()));
        }
        String toolName = toSnakeCase(toolMethod.getName());
        boolean alwaysAsk = mcpServerToolService.isAlwaysAsk(toolName);
        if (alwaysAsk && !mcpServerToolService.hasApprovalScope(authentication)) {
          // An "always ask" tool means what it says for every caller: one that
          // can't show a card (a plain write token, as external MCP clients
          // hold) is refused rather than run unasked
          throw new IllegalAccessException(LLM_ALWAYS_ASK_EXPLANATION.formatted(toolMethod.getName()));
        }
        McpToolGrantRequest grantRequest = new McpToolGrantRequest(id,
                                                                   userIdentity.getUserId(),
                                                                   toolName,
                                                                   toolInput,
                                                                   actingIdentity == null ? getCurrentAgentNameId() :
                                                                                          actingIdentity.grantScope(),
                                                                   conversationId,
                                                                   getClientId(authentication),
                                                                   isCurrentCallRetry(),
                                                                   actingIdentity);
        if (alwaysAsk || mcpServerToolService.isRequireApproval(toolMethod.getName(), authentication)) {
          approveCall(id, grantRequest, alwaysAsk, executionBuilder);
        } else if (isAgentActor(actingIdentity) && mcpServerToolService.isWriteTool(toolMethod.getName())) {
          // an agent account writes only under its own grants, whatever scope
          // the internal client's token holds: a plain write scope, which
          // skips the approval branch, never lets it write ungranted
          approveAgentWrite(grantRequest, executionBuilder);
        }
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_START)
                                                                  .build());
        // Call the Tool
        Map<String, Object> toolArguments = extractToolArguments(toolInput);
        toolArguments = transformSnakeToCamelCaseArguments(toolArguments);
        toolInput = jsonHelper.toJson(toolArguments);
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
      } catch (UserToolDeniedException | UserToolRefusedException e) {
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
        // Spring AI's MethodToolCallback wraps the tool method's exception in a
        // ToolExecutionException; unwrap it so the tool's LLM-directed message
        // (e.g. "provide exactly one image source", "The image URL returned HTTP
        // 429.") reaches the LLM instead of the generic explanation below.
        Throwable cause = e instanceof ToolExecutionException && e.getCause() != null ? e.getCause() : e;
        mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_ERROR)
                                                                  .toolOutput("Error: %s".formatted(cause.getMessage()))
                                                                  .completed(true)
                                                                  .build());
        log.error("Error calling Tool '{}#{}' with input: {}",
                  toolObject.getClass().getName(),
                  toolMethod.getName(),
                  toolInput,
                  cause);
        if (cause instanceof IllegalArgumentException
            || cause instanceof IllegalStateException
            || cause instanceof ObjectNotFoundException
            || cause instanceof IllegalAccessException) {
          throw (Exception) cause;
        } else {
          throw new IllegalStateException(LLM_ERROR_EXPLANATION.formatted(toolMethod.getName(),
                                                                          toolInput),
                                          e);
        }
      } finally {
        ConversationState.setCurrent(null);
      }
    }

    /**
     * Resolves the acting identity of the call and checks it against the
     * account the call runs as.
     *
     * @param username the account the call runs as
     * @return the acting identity, or null when the server resolved none (a
     *         call without a user)
     * @throws IllegalStateException when the identity's subject isn't that
     *                                 account, or the server refuses the
     *                                 identity the internal client sent
     */
    private ActingIdentity resolveActingIdentity(String username) {
      ActingIdentity actingIdentity = getCurrentActingIdentity();
      if (actingIdentity != null && !StringUtils.equals(actingIdentity.subject(), username)) {
        throw new IllegalStateException("Tool '%s' call refused: its acting identity is for '%s', the call runs as '%s'".formatted(toolMethod.getName(),
                                                                                                                                 actingIdentity.subject(),
                                                                                                                                 username));
      }
      return actingIdentity;
    }

    /**
     * @param actingIdentity the acting identity, may be null
     * @return true when an agent account acts as itself
     */
    private static boolean isAgentActor(ActingIdentity actingIdentity) {
      return actingIdentity != null && actingIdentity.actor().kind() == PrincipalKind.AGENT;
    }

    /**
     * Decides a write an agent account makes as itself outside the approval
     * branch: a standing approval covering it lets it run, traced as granted;
     * otherwise nobody can approve it and it is refused, traced as denied.
     *
     * @param grantRequest     the call as the server resolved it
     * @param executionBuilder the trace builder of the call
     * @throws UserToolRefusedException when no grant covers the call
     */
    private void approveAgentWrite(McpToolGrantRequest grantRequest, UserToolExecutionBuilder executionBuilder) {
      McpToolGrant grant = mcpToolGrantService == null ? null :
                                                       mcpToolGrantService.findApplicableGrant(grantRequest,
                                                                                               grantArguments(grantRequest.toolInput()));
      if (grant == null) {
        throw new UserToolRefusedException(LLM_NO_APPROVER_EXPLANATION.formatted(toolMethod.getName()));
      }
      runUnderGrant(grant, grantRequest, executionBuilder);
    }

    /**
     * Lets a call run under the standing approval that covers it: its use
     * recorded and logged, the step traced as granted.
     *
     * @param grant            the covering grant
     * @param grantRequest     the call as the server resolved it
     * @param executionBuilder the trace builder of the call
     */
    private void runUnderGrant(McpToolGrant grant, McpToolGrantRequest grantRequest, UserToolExecutionBuilder executionBuilder) {
      executionBuilder.grantId(grant.getId()).grantOwnerType(grant.getOwnerType().name());
      mcpToolGrantService.recordUse(grant, grantRequest);
      log.info("Tool '{}' run for user '{}' under standing approval '{}' ({}, agent: {}, conversation: {}, client: {})",
               grantRequest.toolName(),
               grantRequest.username(),
               grant.getId(),
               grant.getOwnerType(),
               grantRequest.agentNameId(),
               grantRequest.conversationId(),
               grantRequest.clientId());
      mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.TOOL_EXECUTION_GRANTED)
                                                                .build());
    }

    /**
     * Copies who acts, for whom, through which agents and on which trigger
     * into the trace of the call.
     *
     * @param executionBuilder the trace builder of the call
     * @param actingIdentity   the acting identity, may be null
     */
    private static void withActingIdentity(UserToolExecutionBuilder executionBuilder, ActingIdentity actingIdentity) {
      if (actingIdentity != null) {
        executionBuilder.actorUserName(actingIdentity.actor().username())
                        .actorKind(actingIdentity.actor().kind().name())
                        .onBehalfOf(actingIdentity.onBehalfOf())
                        .agentChain(actingIdentity.chain())
                        .origin(actingIdentity.origin().name());
      }
    }

    /**
     * Decides an approval-gated call: a standing approval covering it lets it
     * run (traced as granted, its use recorded and logged), otherwise the
     * person the call is for is asked on a card in the conversation, which may
     * offer "Always allow". An "always ask" tool never uses a standing
     * approval and never offers one. A call made by an agent acting as itself
     * has nobody to ask: without a covering grant it is refused before any
     * wait, and traced as denied.
     *
     * @param id               the call identifier
     * @param grantRequest     the call as the server resolved it
     * @param alwaysAsk        whether the tool is marked "always ask"
     * @param executionBuilder the trace builder of the call
     * @throws UserToolDeniedException when the user denies the call
     */
    private void approveCall(String id,
                             McpToolGrantRequest grantRequest,
                             boolean alwaysAsk,
                             UserToolExecutionBuilder executionBuilder) {
      Map<String, Object> toolArguments = grantArguments(grantRequest.toolInput());
      McpToolGrant grant = alwaysAsk || mcpToolGrantService == null ? null :
                                                                     mcpToolGrantService.findApplicableGrant(grantRequest,
                                                                                                             toolArguments);
      if (grant != null) {
        runUnderGrant(grant, grantRequest, executionBuilder);
        return;
      }
      ActingIdentity actingIdentity = grantRequest.actingIdentity();
      boolean noApprover = actingIdentity != null && actingIdentity.onBehalfOf() == null;
      if (StringUtils.isBlank(grantRequest.conversationId())) {
        // The approval card lives in the chat conversation: without one,
        // nobody could ever answer and the request would only time out
        String explanation = LLM_NO_CONVERSATION_EXPLANATION.formatted(toolMethod.getName());
        throw noApprover ? new UserToolRefusedException(explanation) : new IllegalStateException(explanation);
      } else if (noApprover) {
        throw new UserToolRefusedException(LLM_NO_APPROVER_EXPLANATION.formatted(toolMethod.getName()));
      }
      boolean grantable = !alwaysAsk
                          && mcpToolGrantService != null
                          && mcpToolGrantService.isGrantStoreAvailable()
                          && mcpToolGrantService.allowsStandingApproval(grantRequest.toolName(), toolArguments);
      McpToolGrantConstraint offeredConstraint = grantable ? mcpToolGrantService.proposeConstraint(grantRequest.toolName(),
                                                                                                    toolArguments) :
                                                           null;
      mcpToolApprovalService.traceToolExecution(executionBuilder.toolExecutionType(UserToolRequestType.APPROVAL_REQUEST)
                                                                .build());
      boolean approved = mcpToolApprovalService.requestApproval(id,
                                                                grantRequest.conversationId(),
                                                                toolMethod.getName(),
                                                                grantRequest.toolInput(),
                                                                actingIdentity == null ? grantRequest.username() :
                                                                                       actingIdentity.onBehalfOf(),
                                                                grantRequest,
                                                                grantable,
                                                                offeredConstraint);
      if (!approved) {
        throw new UserToolDeniedException("Tool execution aborted. As LLM, give an answer to the User: 'You denied the execution thus ...'.");
      }
    }

    /**
     * Reads the call arguments the way the tool method will receive them, for
     * the argument limits of standing approvals.
     *
     * @param toolInput the call input
     * @return the arguments with camel-case keys, or null when the input isn't
     *         a JSON object (no argument limit can then match)
     */
    private Map<String, Object> grantArguments(String toolInput) {
      try {
        return transformSnakeToCamelCaseArguments(extractToolArguments(toolInput));
      } catch (RuntimeException e) {
        return null;
      }
    }

    /**
     * @param toolInput the call input
     * @return the call input as a JSON object map
     */
    private Map<String, Object> extractToolArguments(String toolInput) {
      return jsonHelper.fromJson(toolInput, new ParameterizedTypeReference<>() {
      });
    }

    /**
     * @param toolArguments the call arguments with the schema's keys
     * @return the arguments with the camel-case keys the tool method uses
     */
    private Map<String, Object> transformSnakeToCamelCaseArguments(Map<String, Object> toolArguments) {
      return toolArguments.entrySet()
                          .stream()
                          .collect(Collectors.toMap(e -> toCamelCase(e.getKey()),
                                                    Entry::getValue,
                                                    ObjectUtils::firstNonNull));
    }
  }

}

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

import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_APPROVED_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_CONVERSATION_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_DURATION_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_EXECUTION_EVENT;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_EXEC_COMPLETED_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_INPUT_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_NAME_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_OUTPUT_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_START_TIME_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_TYPE_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_USERNAME_PARAM;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.ws.frameworks.cometd.ContinuationService;

import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.UserToolApprovalAnswer;
import io.meeds.mcp.server.model.UserToolApprovalRequest;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.model.UserToolTimeoutException;
import io.meeds.social.util.JsonUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;

@Component
public class McpToolApprovalService {

  public static final String                   COMETD_CHANNEL = "/eXo/Application/AiAgent";

  private static final String                  ANSWER_PREFIX  = "answer:";

  @Autowired
  private ContinuationService                  continuationService;

  @Autowired
  private EXoContinuationBayeux                continuationBayeux;

  @Autowired
  private ListenerService                      listenerService;

  @Value("${meeds.mcp.tool.userApproval.timeout:120000}")
  private long                                 timeout;

  @Value("${meeds.mcp.tool.userApproval.timeoutCheckInterval:5000}")
  private long                                 timeoutCheckInterval;

  private Map<String, UserToolApprovalAnswer>  userAnswers    = new ConcurrentHashMap<>();

  private Map<String, UserToolApprovalRequest> userRequests   = new ConcurrentHashMap<>();

  private ScheduledExecutorService             executorService;

  @PostConstruct
  public void init() {
    MarkedReference<ServerChannel> channelIfAbsent = continuationBayeux.createChannelIfAbsent(COMETD_CHANNEL);
    if (channelIfAbsent != null) {
      ServerChannel serverChannel = channelIfAbsent.getReference();
      serverChannel.addListener(new WebSocketServerListener());
    }

    if (executorService != null) {
      // For tests by example
      executorService.shutdownNow();
    }
    executorService = Executors.newScheduledThreadPool(1);
    executorService.scheduleAtFixedRate(() -> {
      Collection<UserToolApprovalRequest> requests = userRequests.values();
      for (UserToolApprovalRequest approvalRequest : requests) {
        synchronized (approvalRequest) {
          approvalRequest.notifyAll();
        }
      }
    }, timeout, timeoutCheckInterval, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  public void shutdown() {
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }

  public boolean requestApproval(String id,
                                 String conversationId,
                                 String toolName,
                                 String toolInput,
                                 String username) {
    sendApprovalRequest(id, conversationId, toolName, toolInput, username);
    UserToolApprovalAnswer approvalAnswer = waitForAnswer(id, username);
    if (approvalAnswer.isAnswered()) {
      sendApprovalAnswer(id, conversationId, toolName, toolInput, username, approvalAnswer.isApproved());
      return approvalAnswer.isApproved();
    } else { // Timed out
      sendApprovalTimeout(id, conversationId, toolName, toolInput, username);
      throw new UserToolTimeoutException("Tool execution timeout. As LLM, please answer the user as follows: I couldn’t proceed as no confirmation was received within some minutes.");
    }
  }

  public void receiveAnswer(String id, String wsClientId, boolean approved) throws IllegalAccessException {
    UserToolApprovalRequest approvalRequest = userRequests.get(id);
    UserToolApprovalAnswer approvalAnswer = userAnswers.get(id);
    if (approvalAnswer == null || approvalRequest == null) {
      throw new IllegalStateException("An attempt to answer a Tool Execution Approval request with id '%s' which doesn't exists".formatted(id));
    } else if (!continuationBayeux.isSubscribed(approvalAnswer.getUsername(), wsClientId)) {
      throw new IllegalAccessException("User '%s' isn't subscribed with a valid WebSocket token.".formatted(approvalAnswer.getUsername()));
    }
    synchronized (approvalRequest) {
      approvalAnswer.setAnswered(true);
      approvalAnswer.setApproved(approved);
      approvalRequest.notifyAll();
    }
  }

  public void traceToolExecution(UserToolExecution toolExecution) {
    String username = toolExecution.getUsername();
    Map<String, String> parameters = new HashMap<>();
    parameters.put(AI_AGENT_TOOL_ID_PARAM, toolExecution.getId());
    parameters.put(AI_AGENT_TOOL_CONVERSATION_ID_PARAM, StringUtils.defaultIfBlank(toolExecution.getConversationId(), ""));
    parameters.put(AI_AGENT_TOOL_START_TIME_PARAM, String.valueOf(toolExecution.getStartTime()));
    parameters.put(AI_AGENT_TOOL_DURATION_PARAM, String.valueOf(System.currentTimeMillis() - toolExecution.getStartTime()));
    parameters.put(AI_AGENT_TOOL_TYPE_PARAM, toolExecution.getToolExecutionType().name());
    parameters.put(AI_AGENT_TOOL_NAME_PARAM, StringUtils.defaultIfBlank(toolExecution.getToolName(), ""));
    parameters.put(AI_AGENT_TOOL_INPUT_PARAM, StringUtils.defaultIfBlank(toolExecution.getToolInput(), ""));
    parameters.put(AI_AGENT_TOOL_OUTPUT_PARAM, StringUtils.defaultIfBlank(toolExecution.getToolOutput(), ""));
    parameters.put(AI_AGENT_TOOL_EXEC_COMPLETED_PARAM, String.valueOf(toolExecution.isCompleted()));
    parameters.put(AI_AGENT_TOOL_USERNAME_PARAM, username);
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  private void sendApprovalRequest(String id,
                                   String conversationId,
                                   String toolName,
                                   String toolInput,
                                   String username) {
    Map<String, String> parameters = Map.of(AI_AGENT_TOOL_ID_PARAM,
                                            id,
                                            AI_AGENT_TOOL_CONVERSATION_ID_PARAM,
                                            conversationId,
                                            AI_AGENT_TOOL_TYPE_PARAM,
                                            UserToolRequestType.APPROVAL_REQUEST.name(),
                                            AI_AGENT_TOOL_NAME_PARAM,
                                            StringUtils.defaultIfBlank(toolName, ""),
                                            AI_AGENT_TOOL_INPUT_PARAM,
                                            StringUtils.defaultIfBlank(toolInput, ""),
                                            AI_AGENT_TOOL_USERNAME_PARAM,
                                            username);
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  private void sendApprovalAnswer(String id,
                                  String conversationId,
                                  String toolName,
                                  String toolInput,
                                  String username,
                                  boolean approved) {
    Map<String, String> parameters = Map.of(AI_AGENT_TOOL_ID_PARAM,
                                            id,
                                            AI_AGENT_TOOL_CONVERSATION_ID_PARAM,
                                            conversationId,
                                            AI_AGENT_TOOL_TYPE_PARAM,
                                            UserToolRequestType.APPROVAL_ANSWER.name(),
                                            AI_AGENT_TOOL_NAME_PARAM,
                                            toolName,
                                            AI_AGENT_TOOL_INPUT_PARAM,
                                            String.valueOf(toolInput),
                                            AI_AGENT_TOOL_USERNAME_PARAM,
                                            username,
                                            AI_AGENT_TOOL_APPROVED_PARAM,
                                            String.valueOf(approved));
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  private void sendApprovalTimeout(String id,
                                   String conversationId,
                                   String toolName,
                                   String toolInput,
                                   String username) {
    Map<String, String> parameters = Map.of(AI_AGENT_TOOL_ID_PARAM,
                                            id,
                                            AI_AGENT_TOOL_CONVERSATION_ID_PARAM,
                                            conversationId,
                                            AI_AGENT_TOOL_TYPE_PARAM,
                                            UserToolRequestType.APPROVAL_TIMEOUT.name(),
                                            AI_AGENT_TOOL_NAME_PARAM,
                                            toolName,
                                            AI_AGENT_TOOL_INPUT_PARAM,
                                            String.valueOf(toolInput),
                                            AI_AGENT_TOOL_USERNAME_PARAM,
                                            username);
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  private UserToolApprovalAnswer waitForAnswer(String id, String username) {
    UserToolApprovalRequest approvalRequest = new UserToolApprovalRequest(username);
    UserToolApprovalAnswer approvalAnswer = new UserToolApprovalAnswer(username);

    userRequests.put(id, approvalRequest);
    userAnswers.put(id, approvalAnswer);
    try {
      synchronized (approvalRequest) {
        do {
          try {
            approvalRequest.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        } while (!approvalAnswer.isAnswered() && !approvalRequest.isTimedOut(timeout));
      }
    } finally {
      userRequests.remove(id);
      userAnswers.remove(id);
    }
    return approvalAnswer;
  }

  public class WebSocketServerListener implements ServerChannel.MessageListener {

    @Override
    @SneakyThrows
    public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
      if (!StringUtils.equals(channel.getId(), COMETD_CHANNEL)
          || message.getData() == null) {
        return false;
      }
      String answer = message.getData().toString();
      if (!answer.startsWith(ANSWER_PREFIX)) {
        return false;
      }
      String[] answerParts = answer.split(":");
      String id = answerParts[1];
      boolean approved = Boolean.parseBoolean(answerParts[2]);
      receiveAnswer(id, from.getId(), approved);
      return true;
    }

  }

}

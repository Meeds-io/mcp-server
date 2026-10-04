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

import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_AGENT_NAME_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_APPROVED_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_CONVERSATION_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_DURATION_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_EXECUTION_EVENT;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_EXEC_COMPLETED_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANTABLE_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANT_CONSTRAINT_KIND_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANT_CONSTRAINT_VALUE_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANT_EXPIRES_AT_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANT_ID_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANT_MAX_DAYS_PARAM;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_GRANT_OWNER_TYPE_PARAM;
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
import org.apache.commons.lang3.Strings;
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

import io.meeds.common.ContainerTransactional;
import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantChoice;
import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.model.McpToolGrantRequest;
import io.meeds.mcp.server.model.UserToolApprovalAnswer;
import io.meeds.mcp.server.model.UserToolApprovalRequest;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.model.UserToolTimeoutException;
import io.meeds.social.util.JsonUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the user to approve a tool call on a card in their chat, and waits for
 * the answer. The answer travels over CometD as {@code answer:<id>:<true|false>}
 * or, for "Always allow", {@code answer:<id>:always:<AGENT|TOOL>:<days>:<CONSTRAINED|ANY>}.
 * Only the client subscribed as the requesting user may answer, the first
 * answer wins, and an "Always allow" answer creates its standing approval
 * once, from the pending call the server recorded.
 */
@Component
@Slf4j
public class McpToolApprovalService {

  public static final String                   COMETD_CHANNEL = "/eXo/Application/AiAgent";

  private static final String                  ANSWER_PREFIX  = "answer:";

  private static final String                  ALWAYS_ANSWER  = "always";

  @Autowired
  private ContinuationService                  continuationService;

  @Autowired
  private EXoContinuationBayeux                continuationBayeux;

  @Autowired
  private ListenerService                      listenerService;

  @Autowired
  private McpToolGrantService                  grantService;

  @Value("${meeds.mcp.tool.userApproval.timeout:120000}")
  private long                                 timeout;

  @Value("${meeds.mcp.tool.userApproval.timeoutCheckInterval:5000}")
  private long                                 timeoutCheckInterval;

  private Map<String, UserToolApprovalAnswer>  userAnswers    = new ConcurrentHashMap<>();

  private Map<String, UserToolApprovalRequest> userRequests   = new ConcurrentHashMap<>();

  private ScheduledExecutorService             executorService;

  /**
   * Listens to answers on the CometD channel and starts the periodic timeout
   * check of pending cards.
   */
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

  /**
   * Stops the periodic timeout check.
   */
  @PreDestroy
  public void shutdown() {
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }

  /**
   * Asks the user to approve a call on a card without "Always allow", and
   * waits for the answer.
   *
   * @param id             the call identifier
   * @param conversationId the chat conversation showing the card
   * @param toolName       the tool name shown on the card
   * @param toolInput      the call input shown on the card
   * @param username       the user asked
   * @return true when the user approved
   */
  public boolean requestApproval(String id,
                                 String conversationId,
                                 String toolName,
                                 String toolInput,
                                 String username) {
    return requestApproval(id, conversationId, toolName, toolInput, username, null, false, null);
  }

  /**
   * Asks the user to approve a call on a card, offering "Always allow" when
   * the call is grantable, and waits for the answer.
   *
   * @param id                the call identifier
   * @param conversationId    the chat conversation showing the card
   * @param toolName          the tool name shown on the card
   * @param toolInput         the call input shown on the card
   * @param username          the user asked
   * @param grantRequest      the call as the server resolved it, the only
   *                            source of a grant created from this card
   * @param grantable         whether the card may offer "Always allow"
   * @param offeredConstraint the argument limit the card may offer, or null
   * @return true when the user approved, once or always
   */
  public boolean requestApproval(String id, // NOSONAR
                                 String conversationId,
                                 String toolName,
                                 String toolInput,
                                 String username,
                                 McpToolGrantRequest grantRequest,
                                 boolean grantable,
                                 McpToolGrantConstraint offeredConstraint) {
    UserToolApprovalRequest approvalRequest = new UserToolApprovalRequest(username);
    approvalRequest.setGrantRequest(grantRequest);
    approvalRequest.setGrantable(grantable && grantRequest != null);
    approvalRequest.setOfferedConstraint(approvalRequest.isGrantable() ? offeredConstraint : null);
    UserToolApprovalAnswer approvalAnswer = new UserToolApprovalAnswer(username);
    // Registered before the card is sent, so that an answer can't arrive for
    // a request the server doesn't know yet
    userRequests.put(id, approvalRequest);
    userAnswers.put(id, approvalAnswer);
    try {
      sendApprovalRequest(id, conversationId, toolName, toolInput, username, approvalRequest);
      waitForAnswer(approvalRequest, approvalAnswer);
    } finally {
      userRequests.remove(id);
      userAnswers.remove(id);
    }
    if (approvalAnswer.isAnswered()) {
      sendApprovalAnswer(id, conversationId, toolName, toolInput, username, approvalAnswer);
      return approvalAnswer.isApproved();
    } else { // Timed out
      sendApprovalTimeout(id, conversationId, toolName, toolInput, username);
      throw new UserToolTimeoutException("Tool execution timeout. As LLM, please answer the user as follows: I couldn’t proceed as no confirmation was received within some minutes.");
    }
  }

  /**
   * Records a plain approve or deny answer.
   *
   * @param id         the call identifier
   * @param wsClientId the CometD client that sent the answer
   * @param approved   the answer
   * @throws IllegalAccessException when the client isn't subscribed as the
   *                                  requesting user
   */
  public void receiveAnswer(String id, String wsClientId, boolean approved) throws IllegalAccessException {
    receiveAnswer(id, wsClientId, approved, false, null);
  }

  /**
   * Records an answer to a pending card. Only the client subscribed as the
   * requesting user may answer, and only the first answer counts: the card
   * resends its answer every half second until it is confirmed, so a later
   * copy changes nothing and never creates a second grant. An "Always allow"
   * answer approves the call and, when the card was grantable and the choice
   * valid, creates the standing approval from the pending call; otherwise it
   * approves the call once, creates nothing, and is logged.
   *
   * @param id         the call identifier
   * @param wsClientId the CometD client that sent the answer
   * @param approved   whether the call is approved
   * @param always     whether the answer is "Always allow"
   * @param choice     the checked "Always allow" choice, null when invalid
   * @throws IllegalAccessException when the client isn't subscribed as the
   *                                  requesting user
   */
  public void receiveAnswer(String id,
                            String wsClientId,
                            boolean approved,
                            boolean always,
                            McpToolGrantChoice choice) throws IllegalAccessException {
    UserToolApprovalRequest approvalRequest = userRequests.get(id);
    UserToolApprovalAnswer approvalAnswer = userAnswers.get(id);
    if (approvalAnswer == null || approvalRequest == null) {
      throw new IllegalStateException("An attempt to answer a Tool Execution Approval request with id '%s' which doesn't exists".formatted(id));
    } else if (!continuationBayeux.isSubscribed(approvalAnswer.getUsername(), wsClientId)) {
      throw new IllegalAccessException("User '%s' isn't subscribed with a valid WebSocket token.".formatted(approvalAnswer.getUsername()));
    }
    synchronized (approvalRequest) {
      if (approvalAnswer.isAnswered()) {
        return;
      }
      approvalAnswer.setAnswered(true);
      approvalAnswer.setApproved(approved || always);
      if (always) {
        approvalAnswer.setGrant(createGrant(id, approvalRequest, choice));
      }
      approvalRequest.notifyAll();
    }
  }

  /**
   * Publishes one step of a tool call to the user's chat and to the platform
   * listeners ({@code ai-agent-tool-execution}). A call run under a standing
   * approval carries the grant id and its owner type.
   *
   * @param toolExecution the step to publish
   */
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
    if (toolExecution.getGrantId() != null) {
      parameters.put(AI_AGENT_TOOL_GRANT_ID_PARAM, String.valueOf(toolExecution.getGrantId()));
      parameters.put(AI_AGENT_TOOL_GRANT_OWNER_TYPE_PARAM, StringUtils.defaultIfBlank(toolExecution.getGrantOwnerType(), ""));
    }
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  /**
   * Builds the standing approval of an "Always allow" answer, or explains in
   * the log why the call is only approved once.
   *
   * @param id              the call identifier
   * @param approvalRequest the pending card
   * @param choice          the checked choice, null when invalid
   * @return the created grant, or null
   */
  // The answer arrives on a CometD thread with no portal container bound: the
  // grant store reads settings and writes a row, which need one
  @ContainerTransactional
  private McpToolGrant createGrant(String id, UserToolApprovalRequest approvalRequest, McpToolGrantChoice choice) {
    if (!approvalRequest.isGrantable()) {
      log.info("'Always allow' answered on approval request '{}' whose card couldn't offer it: the call is approved once, no standing approval is created",
               id);
      return null;
    } else if (choice == null) {
      log.info("'Always allow' answered on approval request '{}' with a choice the card doesn't offer: the call is approved once, no standing approval is created",
               id);
      return null;
    }
    try {
      McpToolGrant grant = grantService.createGrant(approvalRequest.getGrantRequest(), choice, approvalRequest.getOfferedConstraint());
      log.info("Standing approval '{}' created by user '{}' for tool '{}' (agent: {}, limit: {}, expires: {})",
               grant == null ? null : grant.getId(),
               approvalRequest.getUsername(),
               approvalRequest.getGrantRequest().toolName(),
               grant == null ? null : grant.getAgentNameId(),
               grant == null ? null : grant.getConstraint(),
               grant == null ? null : grant.getExpiresAt());
      return grant;
    } catch (RuntimeException e) {
      log.warn("Standing approval for approval request '{}' couldn't be created: the call is approved once", id, e);
      return null;
    }
  }

  /**
   * Publishes a card to the user.
   *
   * @param id              the call identifier
   * @param conversationId  the chat conversation
   * @param toolName        the tool name shown
   * @param toolInput       the call input shown
   * @param username        the user asked
   * @param approvalRequest the pending card
   */
  private void sendApprovalRequest(String id,
                                   String conversationId,
                                   String toolName,
                                   String toolInput,
                                   String username,
                                   UserToolApprovalRequest approvalRequest) {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(AI_AGENT_TOOL_ID_PARAM, id);
    parameters.put(AI_AGENT_TOOL_CONVERSATION_ID_PARAM, StringUtils.defaultIfBlank(conversationId, ""));
    parameters.put(AI_AGENT_TOOL_TYPE_PARAM, UserToolRequestType.APPROVAL_REQUEST.name());
    parameters.put(AI_AGENT_TOOL_NAME_PARAM, StringUtils.defaultIfBlank(toolName, ""));
    parameters.put(AI_AGENT_TOOL_INPUT_PARAM, StringUtils.defaultIfBlank(toolInput, ""));
    parameters.put(AI_AGENT_TOOL_USERNAME_PARAM, StringUtils.defaultIfBlank(username, ""));
    parameters.put(AI_AGENT_TOOL_GRANTABLE_PARAM, String.valueOf(approvalRequest.isGrantable()));
    if (approvalRequest.isGrantable()) {
      // the card offers only the durations the server accepts
      parameters.put(AI_AGENT_TOOL_GRANT_MAX_DAYS_PARAM, String.valueOf(grantService.getMaxDays()));
    }
    McpToolGrantRequest grantRequest = approvalRequest.getGrantRequest();
    if (approvalRequest.isGrantable() && StringUtils.isNotBlank(grantRequest.agentNameId())) {
      parameters.put(AI_AGENT_TOOL_AGENT_NAME_ID_PARAM, grantRequest.agentNameId());
    }
    McpToolGrantConstraint offeredConstraint = approvalRequest.getOfferedConstraint();
    if (offeredConstraint != null) {
      parameters.put(AI_AGENT_TOOL_GRANT_CONSTRAINT_KIND_PARAM, offeredConstraint.kind());
      parameters.put(AI_AGENT_TOOL_GRANT_CONSTRAINT_VALUE_PARAM, offeredConstraint.value());
    }
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  /**
   * Publishes the answer of a card, with the standing approval it created.
   *
   * @param id             the call identifier
   * @param conversationId the chat conversation
   * @param toolName       the tool name shown
   * @param toolInput      the call input shown
   * @param username       the user asked
   * @param approvalAnswer the answer
   */
  private void sendApprovalAnswer(String id,
                                  String conversationId,
                                  String toolName,
                                  String toolInput,
                                  String username,
                                  UserToolApprovalAnswer approvalAnswer) {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(AI_AGENT_TOOL_ID_PARAM, id);
    parameters.put(AI_AGENT_TOOL_CONVERSATION_ID_PARAM, StringUtils.defaultIfBlank(conversationId, ""));
    parameters.put(AI_AGENT_TOOL_TYPE_PARAM, UserToolRequestType.APPROVAL_ANSWER.name());
    parameters.put(AI_AGENT_TOOL_NAME_PARAM, StringUtils.defaultIfBlank(toolName, ""));
    parameters.put(AI_AGENT_TOOL_INPUT_PARAM, String.valueOf(toolInput));
    parameters.put(AI_AGENT_TOOL_USERNAME_PARAM, StringUtils.defaultIfBlank(username, ""));
    parameters.put(AI_AGENT_TOOL_APPROVED_PARAM, String.valueOf(approvalAnswer.isApproved()));
    McpToolGrant grant = approvalAnswer.getGrant();
    if (grant != null) {
      parameters.put(AI_AGENT_TOOL_GRANT_ID_PARAM, String.valueOf(grant.getId()));
      parameters.put(AI_AGENT_TOOL_GRANT_OWNER_TYPE_PARAM, String.valueOf(grant.getOwnerType()));
      if (grant.getExpiresAt() != null) {
        parameters.put(AI_AGENT_TOOL_GRANT_EXPIRES_AT_PARAM, String.valueOf(grant.getExpiresAt().toEpochMilli()));
      }
    }
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  /**
   * Publishes that a card timed out.
   *
   * @param id             the call identifier
   * @param conversationId the chat conversation
   * @param toolName       the tool name shown
   * @param toolInput      the call input shown
   * @param username       the user asked
   */
  private void sendApprovalTimeout(String id,
                                   String conversationId,
                                   String toolName,
                                   String toolInput,
                                   String username) {
    Map<String, String> parameters = Map.of(AI_AGENT_TOOL_ID_PARAM,
                                            id,
                                            AI_AGENT_TOOL_CONVERSATION_ID_PARAM,
                                            StringUtils.defaultIfBlank(conversationId, ""),
                                            AI_AGENT_TOOL_TYPE_PARAM,
                                            UserToolRequestType.APPROVAL_TIMEOUT.name(),
                                            AI_AGENT_TOOL_NAME_PARAM,
                                            StringUtils.defaultIfBlank(toolName, ""),
                                            AI_AGENT_TOOL_INPUT_PARAM,
                                            String.valueOf(toolInput),
                                            AI_AGENT_TOOL_USERNAME_PARAM,
                                            StringUtils.defaultIfBlank(username, ""));
    continuationService.sendMessage(username,
                                    COMETD_CHANNEL,
                                    JsonUtils.toJsonString(parameters));
    listenerService.broadcast(AI_AGENT_TOOL_EXECUTION_EVENT, username, parameters);
  }

  /**
   * Blocks the calling thread until the card is answered or times out. The
   * scheduled checker wakes every waiter periodically to test the timeout.
   *
   * @param approvalRequest the pending card, used as the lock
   * @param approvalAnswer  the answer filled by {@link #receiveAnswer}
   */
  private void waitForAnswer(UserToolApprovalRequest approvalRequest, UserToolApprovalAnswer approvalAnswer) {
    synchronized (approvalRequest) {
      while (!approvalAnswer.isAnswered() && !approvalRequest.isTimedOut(timeout)) {
        try {
          approvalRequest.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /**
   * Reads card answers published by the chat on {@link #COMETD_CHANNEL}.
   */
  public class WebSocketServerListener implements ServerChannel.MessageListener {

    /**
     * Parses an answer message and records it; any other message is ignored.
     *
     * @param from    the sending CometD session
     * @param channel the channel
     * @param message the message
     * @return true when the message was an answer
     */
    @Override
    @SneakyThrows
    public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
      if (!Strings.CS.equals(channel.getId(), COMETD_CHANNEL)
          || message.getData() == null) {
        return false;
      }
      String answer = message.getData().toString();
      if (!answer.startsWith(ANSWER_PREFIX)) {
        return false;
      }
      String[] answerParts = answer.split(":");
      if (answerParts.length < 3) {
        return false;
      }
      String id = answerParts[1];
      if (ALWAYS_ANSWER.equals(answerParts[2])) {
        McpToolGrantChoice choice = answerParts.length == 6 ? grantService.parseChoice(answerParts[3],
                                                                                         answerParts[4],
                                                                                         answerParts[5]) :
                                                            null;
        receiveAnswer(id, from.getId(), true, true, choice);
      } else {
        receiveAnswer(id, from.getId(), Boolean.parseBoolean(answerParts[2]), false, null);
      }
      return true;
    }

  }

}

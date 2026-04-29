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

import static io.meeds.mcp.server.service.McpToolApprovalService.COMETD_CHANNEL;
import static io.meeds.mcp.server.util.McpToolUtils.AI_AGENT_TOOL_EXECUTION_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.ws.frameworks.cometd.ContinuationService;

import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.UserToolApprovalAnswer;
import io.meeds.mcp.server.model.UserToolApprovalRequest;
import io.meeds.mcp.server.model.UserToolExecution;
import io.meeds.mcp.server.model.UserToolTimeoutException;

import lombok.SneakyThrows;

@SpringBootTest(classes = McpToolApprovalService.class, properties = {
  "meeds.mcp.tool.userApproval.timeout=200",
  "meeds.mcp.tool.userApproval.timeoutCheckInterval=50"
})
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class McpToolApprovalServiceTest {

  private static final String            USERNAME     = "root";

  private static final String            WS_CLIENT_ID = "ws-client-id";

  private static final String            REQUEST_ID   = "request-id";

  @MockitoBean
  private ContinuationService            continuationService;

  @MockitoBean
  private EXoContinuationBayeux          continuationBayeux;

  @MockitoBean
  private ListenerService                listenerService;

  @MockitoBean
  private ServerChannel                  serverChannel;

  @MockitoBean
  private MarkedReference<ServerChannel> serverChannelReference;

  @MockitoBean
  private ServerSession                  serverSession;

  @MockitoBean
  private ServerChannel                  channel;

  @MockitoBean
  private Mutable                        message;

  @Autowired
  private McpToolApprovalService         service;

  @BeforeEach
  void setUp() {
    getRequests().clear();
    getAnswers().clear();
    service.init();
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
  }

  @Test
  void initRegistersWebSocketListener() {
    when(continuationBayeux.createChannelIfAbsent(COMETD_CHANNEL)).thenReturn(serverChannelReference);
    when(serverChannelReference.getReference()).thenReturn(serverChannel);

    service.init();

    verify(continuationBayeux, atLeastOnce()).createChannelIfAbsent(COMETD_CHANNEL);
    verify(serverChannel).addListener(any(ServerChannel.MessageListener.class));

    service.shutdown();
  }

  @Test
  void shutdownStopsExecutor() {
    when(continuationBayeux.createChannelIfAbsent(COMETD_CHANNEL)).thenReturn(serverChannelReference);
    when(serverChannelReference.getReference()).thenReturn(serverChannel);

    service.init();
    service.shutdown();
  }

  @Test
  void receiveAnswerRejectsUnknownRequest() {
    assertThatThrownBy(() -> service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true)).isInstanceOf(IllegalStateException.class)
                                                                                   .hasMessageContaining("doesn't exists");
  }

  @Test
  void receiveAnswerRejectsUnsubscribedWebSocketClient() {
    putRequestAndAnswer(REQUEST_ID, USERNAME);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(false);
    assertThatThrownBy(() -> service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true)).isInstanceOf(IllegalAccessException.class)
                                                                                   .hasMessageContaining("isn't subscribed");
  }

  @Test
  @SneakyThrows
  void receiveAnswerMarksAnswerAsApproved() {
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    UserToolApprovalAnswer answer = getAnswers().get(REQUEST_ID);

    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true);

    assertThat(answer.isAnswered()).isTrue();
    assertThat(answer.isApproved()).isTrue();
    assertThat(request.getUsername()).isEqualTo(USERNAME);
  }

  @Test
  void traceToolExecutionSendsCometdMessageAndBroadcastsEvent() throws Exception { // NOSONAR
    UserToolExecution execution = UserToolExecution.builder()
                                                   .id(REQUEST_ID)
                                                   .conversationId("conversation-id")
                                                   .username(USERNAME)
                                                   .toolName("testTool")
                                                   .toolInput("{\"message\":\"hello\"}")
                                                   .toolOutput("done")
                                                   .toolExecutionType(UserToolRequestType.TOOL_EXECUTION_FINISHED)
                                                   .startTime(System.currentTimeMillis() - 100)
                                                   .completed(true)
                                                   .build();

    service.traceToolExecution(execution);

    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

    verify(continuationService).sendMessage(eq(USERNAME),
                                            eq(COMETD_CHANNEL),
                                            messageCaptor.capture());

    String json = messageCaptor.getValue();

    assertThat(json).contains("\"toolName\":\"testTool\"");
    assertThat(json).contains("\"toolOutput\":\"done\"");
    assertThat(json).contains("\"completed\":\"true\"");
    assertThat(json).contains("\"type\":\"TOOL_EXECUTION_FINISHED\"");

    verify(listenerService).broadcast(eq(AI_AGENT_TOOL_EXECUTION_EVENT),
                                      eq(USERNAME),
                                      any(Map.class));
  }

  @Test
  void webSocketListenerIgnoresWrongChannel() {
    McpToolApprovalService.WebSocketServerListener listener = service.new WebSocketServerListener();

    when(channel.getId()).thenReturn("/wrong/channel");

    boolean handled = listener.onMessage(serverSession, channel, message);

    assertThat(handled).isFalse();
  }

  @Test
  void webSocketListenerIgnoresNullData() {
    McpToolApprovalService.WebSocketServerListener listener = service.new WebSocketServerListener();

    when(channel.getId()).thenReturn(COMETD_CHANNEL);
    when(message.getData()).thenReturn(null);

    boolean handled = listener.onMessage(serverSession, channel, message);

    assertThat(handled).isFalse();
  }

  @Test
  void webSocketListenerIgnoresNonAnswerMessage() {
    McpToolApprovalService.WebSocketServerListener listener = service.new WebSocketServerListener();

    when(channel.getId()).thenReturn(COMETD_CHANNEL);
    when(message.getData()).thenReturn("ping");

    boolean handled = listener.onMessage(serverSession, channel, message);

    assertThat(handled).isFalse();
  }

  @Test
  void webSocketListenerReceivesApprovalAnswer() {
    McpToolApprovalService.WebSocketServerListener listener = service.new WebSocketServerListener();

    putRequestAndAnswer(REQUEST_ID, USERNAME);

    when(channel.getId()).thenReturn(COMETD_CHANNEL);
    when(message.getData()).thenReturn("answer:%s:true".formatted(REQUEST_ID));
    when(serverSession.getId()).thenReturn(WS_CLIENT_ID);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    boolean handled = listener.onMessage(serverSession, channel, message);

    assertThat(handled).isTrue();
    assertThat(getAnswers().get(REQUEST_ID).isAnswered()).isTrue();
    assertThat(getAnswers().get(REQUEST_ID).isApproved()).isTrue();
  }

  @Test
  @SneakyThrows
  void requestApprovalReturnsTrueWhenApproved() {
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    Future<Boolean> future = CompletableFuture.supplyAsync(() -> service.requestApproval(REQUEST_ID,
                                                                                         "conv",
                                                                                         "tool",
                                                                                         "{}",
                                                                                         USERNAME));
    awaitRequestRegistered(REQUEST_ID);
    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true);
    assertThat(future.get(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @SneakyThrows
  void requestApprovalThrowsOnTimeout() {
    assertThatThrownBy(() -> service.requestApproval(REQUEST_ID,
                                                     "conv",
                                                     "tool",
                                                     "{}",
                                                     USERNAME)).isInstanceOf(UserToolTimeoutException.class);
  }

  private UserToolApprovalRequest putRequestAndAnswer(String id, String username) {
    UserToolApprovalRequest request = new UserToolApprovalRequest(username);
    UserToolApprovalAnswer answer = new UserToolApprovalAnswer(username);

    getRequests().put(id, request);
    getAnswers().put(id, answer);

    return request;
  }

  @SuppressWarnings("unchecked")
  private Map<String, UserToolApprovalRequest> getRequests() {
    return (Map<String, UserToolApprovalRequest>) getField("userRequests");
  }

  @SuppressWarnings("unchecked")
  private Map<String, UserToolApprovalAnswer> getAnswers() {
    return (Map<String, UserToolApprovalAnswer>) getField("userAnswers");
  }

  @SneakyThrows
  private void setField(String name, Object value) {
    Field field = McpToolApprovalService.class.getDeclaredField(name);
    field.setAccessible(true); // NOSONAR
    field.set(service, value); // NOSONAR
  }

  @SneakyThrows
  private Object getField(String name) {
    Field field = McpToolApprovalService.class.getDeclaredField(name);
    field.setAccessible(true); // NOSONAR
    return field.get(service);
  }

  @SneakyThrows
  private void awaitRequestRegistered(String id) {
    long deadline = System.currentTimeMillis() + 1000;
    while (!getRequests().containsKey(id)) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("Approval request was not registered");
      }
      Thread.sleep(50);
    }
  }

}

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import io.meeds.mcp.server.constant.McpToolGrantOwnerType;
import io.meeds.mcp.server.constant.McpToolGrantScope;
import io.meeds.mcp.server.constant.UserToolRequestType;
import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantChoice;
import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.model.McpToolGrantRequest;
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
  private McpToolGrantService            grantService;

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

  /**
   * Boots the kernel's root container once, outside the per-test timeout:
   * the grant creation is woven with @ContainerTransactional, whose aspect
   * reads the current container, and the first read boots it (tens of
   * seconds in a test JVM).
   */
  @org.junit.jupiter.api.BeforeAll
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  static void bootTheRootContainerOnce() {
    org.exoplatform.container.ExoContainerContext.getCurrentContainer();
  }

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

  @Test
  @SneakyThrows
  void requestApprovalWithoutConversationTimesOutInsteadOfFailing() {
    // A null conversation id (or tool name) must reach the UI as an empty
    // string, not blow up the request in Map.of
    assertThatThrownBy(() -> service.requestApproval(REQUEST_ID,
                                                     null,
                                                     null,
                                                     "{}",
                                                     USERNAME)).isInstanceOf(UserToolTimeoutException.class);

    ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
    verify(continuationService, atLeastOnce()).sendMessage(eq(USERNAME), eq(COMETD_CHANNEL), messages.capture());
    assertThat(messages.getAllValues()).allMatch(sentMessage -> sentMessage.contains("\"conversationId\":\"\""));
  }

  @Test
  @SneakyThrows
  void requestApprovalWithoutConversationStillDeliversTheAnswer() {
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    Future<Boolean> future = CompletableFuture.supplyAsync(() -> service.requestApproval(REQUEST_ID,
                                                                                         null,
                                                                                         "tool",
                                                                                         "{}",
                                                                                         USERNAME));
    awaitRequestRegistered(REQUEST_ID);
    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, false);
    assertThat(future.get(1, TimeUnit.SECONDS)).isFalse();
  }

  /**
   * An "Always allow" answer approves the call and creates its grant once,
   * from the pending call the server recorded, however many times the card
   * resends it.
   */
  @Test
  @SneakyThrows
  void alwaysAnswerCreatesTheGrantOnceFromThePendingCall() {
    McpToolGrantRequest grantRequest = grantRequest();
    McpToolGrantConstraint constraint = new McpToolGrantConstraint(McpToolGrantConstraint.EMAIL_DOMAIN_KIND, "example.com");
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    request.setGrantRequest(grantRequest);
    request.setGrantable(true);
    request.setOfferedConstraint(constraint);
    McpToolGrantChoice choice = new McpToolGrantChoice(McpToolGrantScope.AGENT, 7, true);
    McpToolGrant grant = McpToolGrant.builder().id(12L).ownerType(McpToolGrantOwnerType.USER).build();
    when(grantService.createGrant(grantRequest, choice, constraint)).thenReturn(grant);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, choice);
    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, choice);

    UserToolApprovalAnswer answer = getAnswers().get(REQUEST_ID);
    assertThat(answer.isApproved()).isTrue();
    assertThat(answer.getGrant()).isSameAs(grant);
    verify(grantService, times(1)).createGrant(grantRequest, choice, constraint);
  }

  /**
   * "Always allow" on a card that couldn't offer it approves the call once and
   * creates nothing.
   */
  @Test
  @SneakyThrows
  void alwaysAnswerOnNonGrantableCardApprovesOnceWithoutGrant() {
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    request.setGrantRequest(grantRequest());
    request.setGrantable(false);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, false));

    UserToolApprovalAnswer answer = getAnswers().get(REQUEST_ID);
    assertThat(answer.isApproved()).isTrue();
    assertThat(answer.getGrant()).isNull();
    verify(grantService, never()).createGrant(any(), any(), any());
  }

  /**
   * "Always allow" with a choice the card doesn't offer (parsed as null)
   * approves the call once and creates nothing.
   */
  @Test
  @SneakyThrows
  void alwaysAnswerWithInvalidChoiceApprovesOnceWithoutGrant() {
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    request.setGrantRequest(grantRequest());
    request.setGrantable(true);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, null);

    assertThat(getAnswers().get(REQUEST_ID).isApproved()).isTrue();
    verify(grantService, never()).createGrant(any(), any(), any());
  }

  /**
   * A grant store failure still approves the call once.
   */
  @Test
  @SneakyThrows
  void alwaysAnswerApprovesOnceWhenTheGrantCannotBeCreated() {
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    request.setGrantRequest(grantRequest());
    request.setGrantable(true);
    when(grantService.createGrant(any(), any(), any())).thenThrow(new IllegalStateException("store down"));
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, false));

    assertThat(getAnswers().get(REQUEST_ID).isApproved()).isTrue();
    assertThat(getAnswers().get(REQUEST_ID).getGrant()).isNull();
  }

  /**
   * The first answer wins: a later copy, even a different one, changes
   * nothing.
   */
  @Test
  @SneakyThrows
  void firstAnswerWins() {
    putRequestAndAnswer(REQUEST_ID, USERNAME);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, false);
    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, false));

    assertThat(getAnswers().get(REQUEST_ID).isApproved()).isFalse();
    verify(grantService, never()).createGrant(any(), any(), any());
  }

  /**
   * Only a client subscribed as the requesting user may answer "Always
   * allow"; nothing is created for anyone else.
   */
  @Test
  void alwaysAnswerFromAnotherClientIsRefused() {
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    request.setGrantRequest(grantRequest());
    request.setGrantable(true);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.receiveAnswer(REQUEST_ID,
                                                   WS_CLIENT_ID,
                                                   true,
                                                   true,
                                                   new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, false))).isInstanceOf(IllegalAccessException.class);
    assertThat(getAnswers().get(REQUEST_ID).isAnswered()).isFalse();
    verify(grantService, never()).createGrant(any(), any(), any());
  }

  /**
   * The listener parses the "Always allow" answer format and hands the
   * checked choice over.
   */
  @Test
  void webSocketListenerParsesAlwaysAnswer() {
    McpToolApprovalService.WebSocketServerListener listener = service.new WebSocketServerListener();
    UserToolApprovalRequest request = putRequestAndAnswer(REQUEST_ID, USERNAME);
    request.setGrantRequest(grantRequest());
    request.setGrantable(true);
    McpToolGrantChoice choice = new McpToolGrantChoice(McpToolGrantScope.AGENT, 30, false);
    when(grantService.parseChoice("AGENT", "30", "ANY")).thenReturn(choice);
    when(channel.getId()).thenReturn(COMETD_CHANNEL);
    when(message.getData()).thenReturn("answer:%s:always:AGENT:30:ANY".formatted(REQUEST_ID));
    when(serverSession.getId()).thenReturn(WS_CLIENT_ID);
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);

    boolean handled = listener.onMessage(serverSession, channel, message);

    assertThat(handled).isTrue();
    assertThat(getAnswers().get(REQUEST_ID).isApproved()).isTrue();
    verify(grantService).createGrant(request.getGrantRequest(), choice, null);
  }

  /**
   * A truncated answer is ignored rather than failing the listener.
   */
  @Test
  void webSocketListenerIgnoresTruncatedAnswer() {
    McpToolApprovalService.WebSocketServerListener listener = service.new WebSocketServerListener();
    when(channel.getId()).thenReturn(COMETD_CHANNEL);
    when(message.getData()).thenReturn("answer:%s".formatted(REQUEST_ID));

    assertThat(listener.onMessage(serverSession, channel, message)).isFalse();
  }

  /**
   * The card tells whether it may offer "Always allow" and which limit, and
   * the answer event names the grant created.
   */
  @Test
  @SneakyThrows
  void grantableCardAdvertisesTheOfferAndTheAnswerNamesTheGrant() {
    McpToolGrantRequest grantRequest = grantRequest();
    McpToolGrantConstraint constraint = new McpToolGrantConstraint(McpToolGrantConstraint.EMAIL_DOMAIN_KIND, "example.com");
    McpToolGrantChoice choice = new McpToolGrantChoice(McpToolGrantScope.AGENT, 7, true);
    when(grantService.createGrant(grantRequest, choice, constraint)).thenReturn(McpToolGrant.builder()
                                                                                         .id(44L)
                                                                                         .ownerType(McpToolGrantOwnerType.USER)
                                                                                         .expiresAt(java.time.Instant.ofEpochMilli(1000))
                                                                                         .build());
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);
    when(grantService.getMaxDays()).thenReturn(14);

    Future<Boolean> future = CompletableFuture.supplyAsync(() -> service.requestApproval(REQUEST_ID,
                                                                                         "conv",
                                                                                         "sendEmail",
                                                                                         "{}",
                                                                                         USERNAME,
                                                                                         grantRequest,
                                                                                         true,
                                                                                         constraint));
    awaitRequestRegistered(REQUEST_ID);
    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true, true, choice);
    assertThat(future.get(1, TimeUnit.SECONDS)).isTrue();

    ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
    verify(continuationService, atLeastOnce()).sendMessage(eq(USERNAME), eq(COMETD_CHANNEL), messages.capture());
    assertThat(messages.getAllValues().get(0)).contains("\"grantable\":\"true\"")
                                              .contains("\"grantConstraintValue\":\"example.com\"")
                                              .contains("\"agentNameId\":\"agent-1\"")
                                              .contains("\"grantMaxDays\":\"14\"");
    assertThat(messages.getAllValues().get(1)).contains("\"grantId\":\"44\"")
                                              .contains("\"grantExpiresAt\":\"1000\"");
  }

  /**
   * A card without a grant request never advertises "Always allow".
   */
  @Test
  @SneakyThrows
  void cardWithoutGrantRequestIsNotGrantable() {
    when(continuationBayeux.isSubscribed(USERNAME, WS_CLIENT_ID)).thenReturn(true);
    Future<Boolean> future = CompletableFuture.supplyAsync(() -> service.requestApproval(REQUEST_ID,
                                                                                         "conv",
                                                                                         "tool",
                                                                                         "{}",
                                                                                         USERNAME,
                                                                                         null,
                                                                                         true,
                                                                                         null));
    awaitRequestRegistered(REQUEST_ID);
    assertThat(getRequests().get(REQUEST_ID).isGrantable()).isFalse();
    service.receiveAnswer(REQUEST_ID, WS_CLIENT_ID, true);
    assertThat(future.get(1, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * The grant of an "Always allow" answer is created on the CometD thread,
   * which has no portal container bound: the method that creates it binds
   * one (the AspectJ-woven @ContainerTransactional), or the store's setting
   * reads and its row write would run without one.
   *
   * @throws NoSuchMethodException when the method is renamed
   */
  @Test
  void grantCreationBindsAContainer() throws NoSuchMethodException {
    java.lang.reflect.Method createGrant = McpToolApprovalService.class.getDeclaredMethod("createGrant",
                                                                                          String.class,
                                                                                          UserToolApprovalRequest.class,
                                                                                          McpToolGrantChoice.class);
    assertThat(createGrant.isAnnotationPresent(io.meeds.common.ContainerTransactional.class)).isTrue();
  }

  /**
   * @return a pending call as the gate would resolve it
   */
  private McpToolGrantRequest grantRequest() {
    return new McpToolGrantRequest(REQUEST_ID, USERNAME, "send_email", "{}", "agent-1", "conv", "mcp-internal", false);
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

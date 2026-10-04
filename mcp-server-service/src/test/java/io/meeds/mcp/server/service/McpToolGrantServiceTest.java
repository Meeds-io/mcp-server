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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import io.meeds.mcp.server.constant.McpToolGrantOwnerType;
import io.meeds.mcp.server.constant.McpToolGrantScope;
import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantChoice;
import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.model.McpToolGrantRequest;
import io.meeds.mcp.server.plugin.McpToolGrantConstraintEvaluator;
import io.meeds.mcp.server.plugin.McpToolGrantProvider;

/**
 * Pins the standing approval decision the MCP server takes on every
 * approval-gated call: the provider's answer is checked again (owner, tool,
 * strict expiry, agent, interactivity, constraint), a retried answer never
 * uses a grant, and a card grant is built from the pending call only.
 */
@ExtendWith(MockitoExtension.class)
class McpToolGrantServiceTest {

  private static final String                             USER      = "john";

  private static final String                             TOOL      = "send_email";

  private static final String                             AGENT     = "agent-1";

  private static final Map<String, Object>                ARGUMENTS = Map.of("to", "a@example.com");

  private static final McpToolGrantConstraint             DOMAIN    =
                                                                     new McpToolGrantConstraint(McpToolGrantConstraint.EMAIL_DOMAIN_KIND,
                                                                                                "example.com");

  @Mock
  private ObjectProvider<McpToolGrantProvider>            providers;

  @Mock
  private ObjectProvider<McpToolGrantConstraintEvaluator> evaluators;

  @Mock
  private McpToolGrantProvider                            provider;

  @Mock
  private McpToolGrantConstraintEvaluator                 evaluator;

  private McpToolGrantService                             service;

  /**
   * Builds the service with one provider and one evaluator for the tool.
   */
  @BeforeEach
  void setUp() {
    service = new McpToolGrantService();
    ReflectionTestUtils.setField(service, "grantProviders", providers);
    ReflectionTestUtils.setField(service, "constraintEvaluators", evaluators);
    service.setMaxDays(90);
    lenient().when(providers.getIfUnique()).thenReturn(provider);
    lenient().when(evaluators.orderedStream()).thenAnswer(invocation -> Stream.of(evaluator));
    lenient().when(evaluator.supports(TOOL)).thenReturn(true);
  }

  /**
   * A valid user grant covers a call of its user, tool and agent in a chat.
   */
  @Test
  void userGrantCoversTheCall() {
    McpToolGrant grant = userGrant().build();
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(grant));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isSameAs(grant);
  }

  /**
   * Without a grant store no grant ever applies.
   */
  @Test
  void noGrantWithoutProvider() {
    when(providers.getIfUnique()).thenReturn(null);

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
    assertThat(service.isGrantStoreAvailable()).isFalse();
  }

  /**
   * A retried answer never uses a grant, so its writes ask again.
   */
  @Test
  void noGrantOnRetry() {
    assertThat(service.findApplicableGrant(request(true), ARGUMENTS)).isNull();
    verify(provider, never()).findGrants(any(), any());
  }

  /**
   * The expiry is exclusive: a grant expiring now no longer applies.
   */
  @Test
  void expiryIsExclusive() {
    Instant now = Instant.parse("2026-10-04T10:00:00Z");
    service.setClock(Clock.fixed(now, ZoneOffset.UTC));
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().expiresAt(now).build()));
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();

    McpToolGrant stillValid = userGrant().expiresAt(now.plusMillis(1)).build();
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(stillValid));
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isSameAs(stillValid);
  }

  /**
   * A grant without expiry never applies.
   */
  @Test
  void grantWithoutExpiryNeverApplies() {
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().expiresAt(null).build()));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
  }

  /**
   * Another user's grant returned by the provider is ignored.
   */
  @Test
  void anotherUsersGrantIsIgnored() {
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().username("mary").build(),
                                                             userGrant().username(null).build()));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
  }

  /**
   * A grant of another tool returned by the provider is ignored.
   */
  @Test
  void anotherToolsGrantIsIgnored() {
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().toolName("delete_email").build()));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
  }

  /**
   * A grant without owner type never applies.
   */
  @Test
  void grantWithoutOwnerTypeIsIgnored() {
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().ownerType(null).build()));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
  }

  /**
   * An agent-scoped grant covers only its agent; a grant without agent covers
   * any.
   */
  @Test
  void agentScope() {
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().agentNameId("other-agent").build()));
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();

    McpToolGrant anyAgent = userGrant().agentNameId(null).build();
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(anyAgent));
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isSameAs(anyAgent);
  }

  /**
   * A grant is interactive-only unless unattended: it never covers a call
   * without a chat conversation.
   */
  @Test
  void interactiveOnlyUnlessUnattended() {
    McpToolGrantRequest unattendedCall = new McpToolGrantRequest("id", USER, TOOL, "{}", AGENT, null, "mcp-internal", false);
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().build()));
    assertThat(service.findApplicableGrant(unattendedCall, ARGUMENTS)).isNull();

    McpToolGrant unattended = userGrant().unattended(true).build();
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(unattended));
    assertThat(service.findApplicableGrant(unattendedCall, ARGUMENTS)).isSameAs(unattended);
  }

  /**
   * A constrained grant applies only when the tool's evaluator confirms the
   * call stays within it, and fails closed otherwise.
   */
  @Test
  void constraintIsCheckedByTheToolsEvaluator() {
    McpToolGrant constrained = userGrant().constraint(DOMAIN).build();
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(constrained));

    when(evaluator.matches(TOOL, ARGUMENTS, DOMAIN)).thenReturn(true);
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isSameAs(constrained);

    when(evaluator.matches(TOOL, ARGUMENTS, DOMAIN)).thenReturn(false);
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();

    when(evaluator.matches(TOOL, ARGUMENTS, DOMAIN)).thenThrow(new IllegalArgumentException("bad address"));
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();

    assertThat(service.findApplicableGrant(request(false), null)).isNull();
  }

  /**
   * A constrained grant never applies to a tool without evaluator.
   */
  @Test
  void constraintWithoutEvaluatorFailsClosed() {
    when(evaluator.supports(TOOL)).thenReturn(false);
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(userGrant().constraint(DOMAIN).build()));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
  }

  /**
   * The user's own grant is preferred over a platform policy.
   */
  @Test
  void userGrantBeforePlatformPolicy() {
    McpToolGrant platform = userGrant().ownerType(McpToolGrantOwnerType.PLATFORM).username(null).id(1L).build();
    McpToolGrant own = userGrant().id(2L).build();
    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(platform, own));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isSameAs(own);

    when(provider.findGrants(USER, TOOL)).thenReturn(List.of(platform));
    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isSameAs(platform);
  }

  /**
   * A grant store failure means a card, not a failed call.
   */
  @Test
  void providerFailureMeansCard() {
    when(provider.findGrants(USER, TOOL)).thenThrow(new IllegalStateException("down"));

    assertThat(service.findApplicableGrant(request(false), ARGUMENTS)).isNull();
  }

  /**
   * A blank user never gets a grant, whatever the provider returns.
   */
  @Test
  void blankUserNeverGetsAGrant() {
    McpToolGrantRequest anonymous = new McpToolGrantRequest("id", " ", TOOL, "{}", AGENT, "conv", null, false);

    assertThat(service.findApplicableGrant(anonymous, ARGUMENTS)).isNull();
    verify(provider, never()).findGrants(any(), any());
  }

  /**
   * Only the durations and values the card offers are accepted.
   */
  @Test
  void parseChoiceAcceptsOnlyOfferedValues() {
    assertThat(service.parseChoice("AGENT", "7", "CONSTRAINED")).isEqualTo(new McpToolGrantChoice(McpToolGrantScope.AGENT, 7, true));
    assertThat(service.parseChoice("TOOL", "30", "ANY")).isEqualTo(new McpToolGrantChoice(McpToolGrantScope.TOOL, 30, false));
    assertThat(service.parseChoice("TOOL", "2", "ANY")).isNull();
    assertThat(service.parseChoice("TOOL", "365", "ANY")).isNull();
    assertThat(service.parseChoice("EVERYONE", "1", "ANY")).isNull();
    assertThat(service.parseChoice("TOOL", "1", "SOME")).isNull();
    assertThat(service.parseChoice("TOOL", "x", "ANY")).isNull();
    assertThat(service.parseChoice(null, "1", "ANY")).isNull();

    service.setMaxDays(7);
    assertThat(service.parseChoice("TOOL", "30", "ANY")).isNull();
  }

  /**
   * A card grant is a user grant built from the pending call, never
   * unattended, expiring after the chosen days.
   */
  @Test
  void createGrantBuildsFromThePendingCall() {
    when(provider.createGrant(any(), any())).thenAnswer(invocation -> ((McpToolGrant) invocation.getArgument(0)).toBuilder()
                                                                                                                .id(5L)
                                                                                                                .build());
    McpToolGrantRequest pending = request(false);

    McpToolGrant grant = service.createGrant(pending, new McpToolGrantChoice(McpToolGrantScope.AGENT, 7, true), DOMAIN);

    ArgumentCaptor<McpToolGrant> created = ArgumentCaptor.forClass(McpToolGrant.class);
    verify(provider).createGrant(created.capture(), any());
    McpToolGrant stored = created.getValue();
    assertThat(grant.getId()).isEqualTo(5L);
    assertThat(stored.getOwnerType()).isEqualTo(McpToolGrantOwnerType.USER);
    assertThat(stored.getUsername()).isEqualTo(USER);
    assertThat(stored.getToolName()).isEqualTo(TOOL);
    assertThat(stored.getAgentNameId()).isEqualTo(AGENT);
    assertThat(stored.getConstraint()).isEqualTo(DOMAIN);
    assertThat(stored.isUnattended()).isFalse();
    assertThat(Duration.between(stored.getCreatedAt(), stored.getExpiresAt())).isEqualTo(Duration.ofDays(7));
  }

  /**
   * A tool-scoped, unconstrained choice drops the agent and the limit.
   */
  @Test
  void createGrantForAnyAgentWithoutLimit() {
    when(provider.createGrant(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

    McpToolGrant grant = service.createGrant(request(false), new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, false), DOMAIN);

    assertThat(grant.getAgentNameId()).isNull();
    assertThat(grant.getConstraint()).isNull();
  }

  /**
   * Choices that don't fit the pending call are refused.
   */
  @Test
  void createGrantRefusesChoicesThatDontFit() {
    McpToolGrantRequest withoutAgent = new McpToolGrantRequest("id", USER, TOOL, "{}", null, "conv", null, false);
    assertThatThrownBy(() -> service.createGrant(withoutAgent,
                                                 new McpToolGrantChoice(McpToolGrantScope.AGENT, 1, false),
                                                 null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.createGrant(request(false),
                                                 new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, true),
                                                 null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.createGrant(request(false), null, null)).isInstanceOf(IllegalArgumentException.class);
    when(providers.getIfUnique()).thenReturn(null);
    assertThatThrownBy(() -> service.createGrant(request(false),
                                                 new McpToolGrantChoice(McpToolGrantScope.TOOL, 1, false),
                                                 null)).isInstanceOf(IllegalStateException.class);
    verify(provider, never()).createGrant(any(), any());
  }

  /**
   * The evaluator proposes the limit a card may offer, and a failing
   * evaluator offers none.
   */
  @Test
  void proposeConstraintAsksTheToolsEvaluator() {
    when(evaluator.proposeConstraint(TOOL, ARGUMENTS)).thenReturn(DOMAIN);
    assertThat(service.proposeConstraint(TOOL, ARGUMENTS)).isEqualTo(DOMAIN);

    when(evaluator.proposeConstraint(TOOL, ARGUMENTS)).thenThrow(new IllegalArgumentException("bad"));
    assertThat(service.proposeConstraint(TOOL, ARGUMENTS)).isNull();

    when(evaluator.supports("create_task")).thenReturn(false);
    assertThat(service.proposeConstraint("create_task", ARGUMENTS)).isNull();
  }

  /**
   * Recording a use reaches the provider, and its failure is swallowed.
   */
  @Test
  void recordUseIsBestEffort() {
    McpToolGrant grant = userGrant().id(9L).build();
    McpToolGrantRequest call = request(false);
    service.recordUse(grant, call);
    verify(provider).recordUse(9L, call);

    org.mockito.Mockito.doThrow(new IllegalStateException("down")).when(provider).recordUse(9L, call);
    service.recordUse(grant, call);
  }

  /**
   * @param retry whether the call belongs to a retried answer
   * @return a chat call of {@link #USER} through {@link #AGENT}
   */
  private McpToolGrantRequest request(boolean retry) {
    return new McpToolGrantRequest("id", USER, TOOL, "{}", AGENT, "conv", "mcp-internal", retry);
  }

  /**
   * @return a valid interactive user grant of {@link #TOOL} for {@link #AGENT}
   */
  private McpToolGrant.McpToolGrantBuilder userGrant() {
    return McpToolGrant.builder()
                       .id(3L)
                       .ownerType(McpToolGrantOwnerType.USER)
                       .username(USER)
                       .toolName(TOOL)
                       .agentNameId(AGENT)
                       .expiresAt(Instant.now().plus(Duration.ofDays(1)));
  }

}

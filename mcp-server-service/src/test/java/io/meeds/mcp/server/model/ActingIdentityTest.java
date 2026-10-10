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
package io.meeds.mcp.server.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.meeds.mcp.server.constant.Origin;
import io.meeds.mcp.server.constant.PrincipalKind;
import io.meeds.mcp.server.util.McpToolUtils;

/**
 * Pins the invariants of the acting identity (EXO-91056): who executes a
 * tool call, for whom, through which agents and on which trigger.
 */
class ActingIdentityTest {

  private static final String USER  = "john";

  private static final String AGENT = "EMAIL_ASSISTANT";

  /**
   * A person acts for themself: the subject is the person, no agent is in the
   * chain, and no grant scope is set.
   */
  @Test
  void personActsForThemself() {
    ActingIdentity person = ActingIdentity.person(USER, Origin.CHAT);

    assertThat(person.actor()).isEqualTo(new PrincipalRef(PrincipalKind.USER, USER, null));
    assertThat(person.onBehalfOf()).isEqualTo(USER);
    assertThat(person.subject()).isEqualTo(USER);
    assertThat(person.chain()).isEmpty();
    assertThat(person.callingAgent()).isNull();
    assertThat(person.grantScope()).isNull();
    assertThat(person.origin()).isEqualTo(Origin.CHAT);
    assertThat(ActingIdentity.external(USER)).isEqualTo(ActingIdentity.person(USER, Origin.EXTERNAL_MCP));
  }

  /**
   * An agent account acts as itself: it is its own subject, acts for nobody,
   * is alone in the chain, and its grants are matched by its agent-user
   * source scope.
   */
  @Test
  void agentAccountActsAsItself() {
    ActingIdentity agent = ActingIdentity.agent("agent-mail", AGENT, Origin.MENTION);

    assertThat(agent.actor()).isEqualTo(new PrincipalRef(PrincipalKind.AGENT, "agent-mail", AGENT));
    assertThat(agent.onBehalfOf()).isNull();
    assertThat(agent.subject()).isEqualTo("agent-mail");
    assertThat(agent.chain()).containsExactly(AGENT);
    assertThat(agent.callingAgent()).isEqualTo(AGENT);
    assertThat(agent.grantScope()).isEqualTo("agent-user:" + AGENT);
    assertThat(agent.isSourceScope()).isTrue();
    assertThat(agent.origin()).isEqualTo(Origin.MENTION);
  }

  /**
   * R2: the chain only grows, one hop at a time; actor, person and origin are
   * kept, and the source identity is never changed.
   */
  @Test
  void chainOnlyGrows() {
    ActingIdentity turn = ActingIdentity.person(USER, Origin.CHAT).withHop("COMPLETION");
    ActingIdentity delegated = turn.withHop(AGENT);

    assertThat(turn.chain()).containsExactly("COMPLETION");
    assertThat(delegated.chain()).containsExactly("COMPLETION", AGENT);
    assertThat(delegated.callingAgent()).isEqualTo(AGENT);
    assertThat(delegated.grantScope()).isEqualTo(AGENT);
    assertThat(delegated.actor()).isEqualTo(turn.actor());
    assertThat(delegated.onBehalfOf()).isEqualTo(turn.onBehalfOf());
    assertThat(delegated.origin()).isEqualTo(turn.origin());
    assertThatThrownBy(() -> delegated.chain().add("OTHER")).isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * The chain handed to the constructor is copied: changing the caller's list
   * afterwards changes nothing.
   */
  @Test
  void chainIsCopied() {
    List<String> hops = new ArrayList<>(List.of("COMPLETION"));
    ActingIdentity identity = new ActingIdentity(PrincipalRef.user(USER), USER, hops, Origin.CHAT, "COMPLETION");
    hops.add(AGENT);

    assertThat(identity.chain()).containsExactly("COMPLETION");
  }

  /**
   * A hop after a source scope keeps the scope: the delegation of an
   * unattended run is still matched by its source's grants.
   */
  @Test
  void hopKeepsASourceScope() {
    ActingIdentity scheduled = ActingIdentity.person(USER, Origin.SCHEDULE).withHop("DIGEST").withGrantScope("schedule:12");

    ActingIdentity delegated = scheduled.withHop(AGENT);

    assertThat(delegated.grantScope()).isEqualTo("schedule:12");
    assertThat(delegated.chain()).containsExactly("DIGEST", AGENT);
    assertThat(ActingIdentity.agent("agent-mail", AGENT, Origin.MENTION).withHop("DOCUMENTS").grantScope())
                                                                                                         .isEqualTo("agent-user:" + AGENT);
  }

  /**
   * The grant scope decides the origin whatever the identity's origin was: a
   * schedule scope is a scheduled run, any other source scope another
   * source, and a bare agent name id leaves the origin as is.
   */
  @Test
  void grantScopeDerivesTheOrigin() {
    assertThat(ActingIdentity.person(USER, Origin.CHAT).withGrantScope("schedule:12").origin()).isEqualTo(Origin.SCHEDULE);
    assertThat(ActingIdentity.person(USER, Origin.SCHEDULE).withGrantScope("email-filter:42").origin()).isEqualTo(Origin.SOURCE);
    assertThat(ActingIdentity.person(USER, Origin.PROPOSAL).withGrantScope(AGENT).origin()).isEqualTo(Origin.PROPOSAL);
    assertThat(ActingIdentity.person(USER, Origin.CHAT).withGrantScope(null).origin()).isEqualTo(Origin.CHAT);
    assertThat(ActingIdentity.person(USER, Origin.CHAT).withGrantScope("schedule:12").isSourceScope()).isTrue();
  }

  /**
   * R3: an agent account acting for a person is refused at construction,
   * until a later step defines who produces it.
   */
  @Test
  void agentAccountActingForAPersonIsRefused() {
    PrincipalRef agentAccount = PrincipalRef.agent("agent-mail", AGENT);
    List<String> chain = List.of(AGENT);

    assertThatThrownBy(() -> new ActingIdentity(agentAccount, USER, chain, Origin.MENTION, null))
                                                                                                .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A person acts only for themself: a person's account acting for someone
   * else, or for nobody, is refused.
   */
  @Test
  void personActingForSomeoneElseIsRefused() {
    PrincipalRef person = PrincipalRef.user(USER);
    List<String> chain = List.of();

    assertThatThrownBy(() -> new ActingIdentity(person, "mary", chain, Origin.CHAT, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActingIdentity(person, null, chain, Origin.CHAT, null)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * An incomplete identity, or a chain element that isn't an agent name id,
   * is refused.
   */
  @Test
  void incompleteIdentityIsRefused() {
    PrincipalRef person = PrincipalRef.user(USER);
    List<String> empty = List.of();
    List<String> sourceInChain = List.of("schedule:12");
    ActingIdentity identity = ActingIdentity.person(USER, Origin.CHAT);

    assertThatThrownBy(() -> new ActingIdentity(null, USER, empty, Origin.CHAT, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActingIdentity(person, USER, null, Origin.CHAT, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActingIdentity(person, USER, empty, null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActingIdentity(person, USER, sourceInChain, Origin.CHAT, null))
                                                                                               .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> identity.withHop("schedule:12")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> identity.withHop(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PrincipalRef.agent("agent-mail", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PrincipalRef(PrincipalKind.USER, USER, AGENT)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PrincipalRef.user(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PrincipalRef(null, USER, null)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * R4: the model reproduces today's calls exactly: a chat turn is the user
   * with the agent as grant scope, a scheduled run and an e-mail filter run
   * keep their source scopes, and the grant scope is what the legacy agent
   * header carried.
   */
  @Test
  void legacyEquivalence() {
    ActingIdentity chat = ActingIdentity.person(USER, Origin.CHAT).withHop(AGENT);
    assertThat(chat.subject()).isEqualTo(USER);
    assertThat(chat.grantScope()).isEqualTo(AGENT);

    ActingIdentity schedule = ActingIdentity.person(USER, Origin.SCHEDULE).withHop("DIGEST").withGrantScope("schedule:12");
    assertThat(schedule.subject()).isEqualTo(USER);
    assertThat(schedule.grantScope()).isEqualTo("schedule:12");
    assertThat(schedule.origin()).isEqualTo(Origin.SCHEDULE);

    ActingIdentity emailFilter = ActingIdentity.person(USER, Origin.SOURCE).withHop(AGENT).withGrantScope("email-filter:42");
    assertThat(emailFilter.subject()).isEqualTo(USER);
    assertThat(emailFilter.grantScope()).isEqualTo("email-filter:42");
    assertThat(emailFilter.origin()).isEqualTo(Origin.SOURCE);
  }

  /**
   * The chain travels as a JSON array: a name id holding a comma stays one
   * hop, and the parser refuses what isn't an array of agent name ids.
   */
  @Test
  void chainTravelsAsAJsonArray() {
    assertThat(McpToolUtils.toAgentChainJson(List.of("COMPLETION", AGENT))).isEqualTo("[\"COMPLETION\",\"" + AGENT + "\"]");
    assertThat(McpToolUtils.toAgentChainJson(null)).isEqualTo("[]");
    assertThat(McpToolUtils.parseAgentChain("[\"A,B\"]")).containsExactly("A,B");
    assertThat(McpToolUtils.parseAgentChain(McpToolUtils.toAgentChainJson(List.of("A,B", "C")))).containsExactly("A,B", "C");
    assertThat(McpToolUtils.parseAgentChain("[]")).isEmpty();

    assertThatThrownBy(() -> McpToolUtils.parseAgentChain("A,B")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpToolUtils.parseAgentChain("{\"a\":1}")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpToolUtils.parseAgentChain("[1]")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpToolUtils.parseAgentChain("[\"schedule:12\"]")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpToolUtils.parseAgentChain("[\" \"]")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpToolUtils.parseAgentChain(null)).isInstanceOf(IllegalArgumentException.class);
  }

}

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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import io.meeds.mcp.server.constant.Origin;
import io.meeds.mcp.server.constant.PrincipalKind;

/**
 * Who executes a tool call, for whom, through which agents, and on which
 * trigger. Immutable, and built only by platform code: a value an external
 * client, a model or a tool argument sends never becomes one.
 * <p>
 * The account whose permissions apply to the call is the {@link #subject()}:
 * the person the work is for, or the agent account itself when an agent acts
 * as itself. A call never combines two accounts' permissions. An agent account
 * acting for a person is a combination no entry point produces yet, so it is
 * refused at construction.
 * <p>
 * A call may be limited to the content of one space, its {@link #spaceScope()}:
 * an answer an agent posts in a space must not carry what only the person it
 * reads for can see elsewhere. The limit is kept by every derivation of the
 * identity, and the origins that post their answer in a space
 * ({@link #READ_SCOPED_ORIGINS}) can't be built without it.
 *
 * @param actor      the account that executes the call
 * @param onBehalfOf the person the work is for, null when an agent acts as
 *                     itself
 * @param chain      the agent name ids from the entry point to the agent
 *                     making the call, never null, possibly empty
 * @param origin     what triggered the call
 * @param grantScope what the standing-approval lookup matches against: an
 *                     agent name id, or a source scope holding
 *                     {@value #SOURCE_SCOPE_SEPARATOR}; null for none
 * @param spaceScope  the id of the only space the call may read, null when
 *                     the call isn't limited to a space
 */
public record ActingIdentity(PrincipalRef actor,
                             String onBehalfOf,
                             List<String> chain,
                             Origin origin,
                             String grantScope,
                             Long spaceScope) {

  /**
   * The origins whose answer is posted in a space, read by every member of it:
   * an identity of one of them is always limited to that space.
   */
  public static final Set<Origin> READ_SCOPED_ORIGINS     = Set.copyOf(EnumSet.of(Origin.MENTION,
                                                                                  Origin.TASK_COMMENT,
                                                                                  Origin.TASK_ASSIGNMENT));

  /** What a source scope holds, and an agent name id never does. */
  public static final String SOURCE_SCOPE_SEPARATOR  = ":";

  /** The prefix of the grant scope of a scheduled agent run. */
  public static final String SCHEDULE_SCOPE_PREFIX   = "schedule" + SOURCE_SCOPE_SEPARATOR;

  /** The prefix of the grant scope of an agent account acting as itself. */
  public static final String AGENT_USER_SCOPE_PREFIX = "agent-user" + SOURCE_SCOPE_SEPARATOR;

  /**
   * Checks the identity is complete and holds a combination some entry point
   * produces, and freezes its chain.
   *
   * @param actor      the account that executes the call
   * @param onBehalfOf the person the work is for, or null
   * @param chain      the agents from the entry point to the calling one
   * @param origin     what triggered the call
   * @param grantScope the standing-approval scope, or null
   * @param spaceScope  the only space the call may read, or null
   * @throws IllegalArgumentException when a part is missing, a chain element
   *                                    is not an agent name id, a person acts
   *                                    for someone else, an agent account acts
   *                                    for a person, the space scope isn't a
   *                                    space id, or an origin of
   *                                    {@link #READ_SCOPED_ORIGINS} comes
   *                                    without a space scope
   */
  public ActingIdentity {
    if (actor == null) {
      throw new IllegalArgumentException("An acting identity needs its actor");
    } else if (origin == null) {
      throw new IllegalArgumentException("An acting identity needs its origin");
    } else if (chain == null) {
      throw new IllegalArgumentException("An acting identity needs its agent chain, empty when no agent calls");
    } else if (actor.kind() == PrincipalKind.AGENT && onBehalfOf != null) {
      throw new IllegalArgumentException("An agent account acting for a person isn't supported: agent '%s' for '%s'".formatted(actor.agentNameId(),
                                                                                                                              onBehalfOf));
    } else if (actor.kind() == PrincipalKind.USER && !actor.username().equals(onBehalfOf)) {
      throw new IllegalArgumentException("A person acts only for themself: '%s' for '%s'".formatted(actor.username(), onBehalfOf));
    } else if (spaceScope != null && spaceScope <= 0) {
      throw new IllegalArgumentException("A space scope is a space id: %s isn't one".formatted(spaceScope));
    } else if (spaceScope == null && READ_SCOPED_ORIGINS.contains(origin)) {
      throw new IllegalArgumentException("A call of origin %s answers in a space, so it needs the space scope of that space".formatted(origin));
    }
    for (String agentNameId : chain) {
      checkAgentNameId(agentNameId);
    }
    chain = List.copyOf(chain);
    grantScope = StringUtils.trimToNull(grantScope);
  }

  /**
   * Builds an identity limited to no space, whatever the identity it is
   * derived from was limited to.
   *
   * @param actor      the account that executes the call
   * @param onBehalfOf the person the work is for, or null
   * @param chain      the agents from the entry point to the calling one
   * @param origin     what triggered the call
   * @param grantScope the standing-approval scope, or null
   * @throws IllegalArgumentException as the canonical constructor does, an
   *                                    origin of {@link #READ_SCOPED_ORIGINS}
   *                                    included, since it needs a space scope
   * @deprecated use the canonical constructor, which states the space scope,
   *             or {@link #withHop(String)} / {@link #withGrantScope(String)}
   *             to derive an identity: a copy built with this one silently
   *             drops a space scope. Since 7.3.0, kept for the add-ons built
   *             against the previous signature, for removal once none calls
   *             it.
   */
  @Deprecated(since = "7.3.0", forRemoval = true)
  public ActingIdentity(PrincipalRef actor, String onBehalfOf, List<String> chain, Origin origin, String grantScope) {
    this(actor, onBehalfOf, chain, origin, grantScope, null);
  }

  /**
   * @param username the person
   * @param origin   what triggered the call, not one of
   *                   {@link #READ_SCOPED_ORIGINS}
   * @return the person acting for themself, with no agent in the chain, no
   *         grant scope and no space scope
   */
  public static ActingIdentity person(String username, Origin origin) {
    return person(username, origin, null);
  }

  /**
   * @param username  the person
   * @param origin    what triggered the call
   * @param spaceScope the only space the call may read, or null
   * @return the person acting for themself, with no agent in the chain and no
   *         grant scope, limited to that space
   */
  public static ActingIdentity person(String username, Origin origin, Long spaceScope) {
    return new ActingIdentity(PrincipalRef.user(username), username, List.of(), origin, null, spaceScope);
  }

  /**
   * @param username    the agent account's login
   * @param agentNameId the agent it is the account of
   * @param origin      what triggered the call, not one of
   *                      {@link #READ_SCOPED_ORIGINS}
   * @return the agent account acting as itself, the agent alone in the chain,
   *         its grants matched by the scope {@value #AGENT_USER_SCOPE_PREFIX}
   *         followed by its name id, with no space scope
   */
  public static ActingIdentity agent(String username, String agentNameId, Origin origin) {
    return agent(username, agentNameId, origin, null);
  }

  /**
   * @param username    the agent account's login
   * @param agentNameId the agent it is the account of
   * @param origin      what triggered the call
   * @param spaceScope   the only space the call may read, or null
   * @return the agent account acting as itself, the agent alone in the chain,
   *         its grants matched by the scope {@value #AGENT_USER_SCOPE_PREFIX}
   *         followed by its name id, limited to that space
   */
  public static ActingIdentity agent(String username, String agentNameId, Origin origin, Long spaceScope) {
    return new ActingIdentity(PrincipalRef.agent(username, agentNameId),
                              null,
                              List.of(agentNameId),
                              origin,
                              AGENT_USER_SCOPE_PREFIX + agentNameId,
                              spaceScope);
  }

  /**
   * @param username the user the external client's token names
   * @return that user acting for themself through an external MCP client
   */
  public static ActingIdentity external(String username) {
    return person(username, Origin.EXTERNAL_MCP);
  }

  /**
   * The only derivation of the chain: an agent appended at its end. The
   * actor, the person the work is for, the origin and the space scope are
   * kept, so an agent called by a limited one is limited the same way.
   *
   * @param agentNameId the agent that now makes the calls
   * @return the identity with the agent appended, whose grant scope is that
   *         agent unless this identity carries a source scope, which is kept
   * @throws IllegalArgumentException when the name id is blank or holds
   *                                    {@value #SOURCE_SCOPE_SEPARATOR}
   */
  public ActingIdentity withHop(String agentNameId) {
    checkAgentNameId(agentNameId);
    List<String> hops = new ArrayList<>(chain);
    hops.add(agentNameId);
    return new ActingIdentity(actor, onBehalfOf, hops, origin, isSourceScope() ? grantScope : agentNameId, spaceScope);
  }

  /**
   * Sets the standing-approval scope, and derives the origin from it whatever
   * the origin was: a scope starting with {@value #SCHEDULE_SCOPE_PREFIX} is a
   * scheduled run, any other scope holding {@value #SOURCE_SCOPE_SEPARATOR}
   * another unattended source, and a bare agent name id leaves the origin as
   * it is. The space scope is kept.
   *
   * @param scope the grant scope, null for none
   * @return the identity with that scope and the origin it implies
   */
  public ActingIdentity withGrantScope(String scope) {
    Origin scopeOrigin = origin;
    if (StringUtils.startsWith(scope, SCHEDULE_SCOPE_PREFIX)) {
      scopeOrigin = Origin.SCHEDULE;
    } else if (StringUtils.contains(scope, SOURCE_SCOPE_SEPARATOR)) {
      scopeOrigin = Origin.SOURCE;
    }
    return new ActingIdentity(actor, onBehalfOf, chain, scopeOrigin, scope, spaceScope);
  }

  /**
   * @return the account whose permissions apply to the call: the person the
   *         work is for, else the actor itself
   */
  public String subject() {
    return onBehalfOf != null ? onBehalfOf : actor.username();
  }

  /**
   * @return the agent making the call, the last of the chain, or null when no
   *         agent calls
   */
  public String callingAgent() {
    return chain.isEmpty() ? null : chain.get(chain.size() - 1);
  }

  /**
   * @return true when the grant scope names a source rather than an agent
   */
  public boolean isSourceScope() {
    return StringUtils.contains(grantScope, SOURCE_SCOPE_SEPARATOR);
  }

  /**
   * @param agentNameId a chain element
   * @throws IllegalArgumentException when it is blank or holds
   *                                    {@value #SOURCE_SCOPE_SEPARATOR}, which
   *                                    no agent name id does
   */
  private static void checkAgentNameId(String agentNameId) {
    if (StringUtils.isBlank(agentNameId)) {
      throw new IllegalArgumentException("An agent of the chain needs a name id");
    } else if (agentNameId.contains(SOURCE_SCOPE_SEPARATOR)) {
      throw new IllegalArgumentException("'%s' isn't an agent name id".formatted(agentNameId));
    }
  }

}

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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.meeds.mcp.server.constant.McpToolGrantOwnerType;
import io.meeds.mcp.server.constant.McpToolGrantScope;
import io.meeds.mcp.server.model.McpToolGrant;
import io.meeds.mcp.server.model.McpToolGrantChoice;
import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.model.McpToolGrantRequest;
import io.meeds.mcp.server.plugin.McpToolGrantConstraintEvaluator;
import io.meeds.mcp.server.plugin.McpToolGrantProvider;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides whether a standing approval covers an approval-gated tool call, and
 * turns an "Always allow" answer into a grant. The decision is taken here, on
 * the MCP server, for every caller; the grants themselves are kept by an
 * {@link McpToolGrantProvider} another add-on contributes, and argument limits
 * are evaluated by the tool's own {@link McpToolGrantConstraintEvaluator}.
 * <p>
 * Every provider answer is checked again here, so the provider can never widen
 * a decision: owner, tool, expiry (strictly before), agent, interactivity and
 * constraint. A grant is <em>interactive-only</em> unless it is explicitly
 * {@link McpToolGrant#isUnattended() unattended}: it then covers a call only
 * when the call comes from a chat conversation, so a grant chosen while the
 * user watched never authorizes a run without them. No grant applies to a call
 * of a retried answer: a retry repeats a question the user already saw
 * answered, and its writes ask again.
 */
@Service
@Slf4j
public class McpToolGrantService {

  /** The validities, in days, an approval card may offer. */
  public static final Set<Integer>                        CARD_GRANT_DAYS = Set.of(1, 7, 30);

  @Autowired
  private ObjectProvider<McpToolGrantProvider>            grantProviders;

  @Autowired
  private ObjectProvider<McpToolGrantConstraintEvaluator> constraintEvaluators;

  @Value("${meeds.mcp.tool.grant.maxDays:90}")
  @Setter
  private int                                             maxDays;

  @Setter
  private Clock                                           clock           = Clock.systemUTC();

  /**
   * @return true when an add-on installed exactly one grant store; with none
   *         (or an ambiguous configuration) no grant ever applies
   */
  public boolean isGrantStoreAvailable() {
    return getProvider() != null;
  }

  /**
   * Finds the standing approval that covers a call, the user's own grants
   * first, then the platform policies.
   *
   * @param request   the call as the server resolved it
   * @param arguments the call arguments as the tool receives them
   * @return the grant to apply, or null when the call must be approved on a
   *         card
   */
  public McpToolGrant findApplicableGrant(McpToolGrantRequest request, Map<String, Object> arguments) {
    McpToolGrantProvider provider = getProvider();
    if (provider == null
        || request == null
        || request.retry()
        || StringUtils.isBlank(request.username())
        || StringUtils.isBlank(request.toolName())) {
      return null;
    }
    List<McpToolGrant> grants;
    try {
      grants = provider.findGrants(request.username(), request.toolName());
    } catch (RuntimeException e) {
      log.warn("Standing approvals of user '{}' for tool '{}' couldn't be read, the call is approved on a card",
               request.username(),
               request.toolName(),
               e);
      return null;
    }
    if (grants == null || !allowsStandingApproval(request.toolName(), arguments)) {
      return null;
    }
    Instant now = clock.instant();
    return grants.stream()
                 .filter(Objects::nonNull)
                 .filter(grant -> covers(grant, request, arguments, now))
                 .min(Comparator.comparing(grant -> grant.getOwnerType() == McpToolGrantOwnerType.USER ? 0 : 1))
                 .orElse(null);
  }

  /**
   * Records one use of a grant in its store. A failure is logged and ignored:
   * the run is traced and logged by the caller whatever the counter says.
   *
   * @param grant   the grant that applied
   * @param request the call it applied to
   */
  public void recordUse(McpToolGrant grant, McpToolGrantRequest request) {
    McpToolGrantProvider provider = getProvider();
    if (provider == null || grant == null || grant.getId() == null) {
      return;
    }
    try {
      provider.recordUse(grant.getId(), request);
    } catch (RuntimeException e) {
      log.warn("Use of standing approval '{}' couldn't be recorded", grant.getId(), e);
    }
  }

  /**
   * Tells whether the tool lets a standing approval cover this call at all,
   * from its own evaluator; a tool without evaluator always does, a failing
   * evaluator never does.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them
   * @return true when a standing approval may cover the call
   */
  public boolean allowsStandingApproval(String toolName, Map<String, Object> arguments) {
    McpToolGrantConstraintEvaluator evaluator = getEvaluator(toolName);
    if (evaluator == null) {
      return true;
    } else if (arguments == null) {
      return false;
    }
    try {
      return evaluator.allowsStandingApproval(toolName, arguments);
    } catch (RuntimeException e) {
      log.warn("Tool '{}' couldn't tell whether a standing approval may cover the call, none does", toolName, e);
      return false;
    }
  }

  /**
   * Derives the argument limit an approval card may offer, from the tool's own
   * evaluator.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them
   * @return the constraint, or null when the tool has no evaluator or the call
   *         offers none
   */
  public McpToolGrantConstraint proposeConstraint(String toolName, Map<String, Object> arguments) {
    McpToolGrantConstraintEvaluator evaluator = getEvaluator(toolName);
    if (evaluator == null || arguments == null) {
      return null;
    }
    try {
      return evaluator.proposeConstraint(toolName, arguments);
    } catch (RuntimeException e) {
      log.warn("Argument limit of tool '{}' couldn't be derived, none is offered", toolName, e);
      return null;
    }
  }

  /**
   * Reads the "Always allow" part of an approval answer. Each value must be
   * one the card offers; anything else makes the whole choice invalid.
   *
   * @param scope       {@code AGENT} or {@code TOOL}
   * @param days        one of {@link #CARD_GRANT_DAYS}
   * @param constrained {@code CONSTRAINED} or {@code ANY}
   * @return the choice, or null when a value is not one the card offers
   */
  public McpToolGrantChoice parseChoice(String scope, String days, String constrained) {
    McpToolGrantScope grantScope;
    int grantDays;
    try {
      grantScope = McpToolGrantScope.valueOf(scope);
      grantDays = Integer.parseInt(days);
    } catch (RuntimeException e) {
      return null;
    }
    if (!CARD_GRANT_DAYS.contains(grantDays) || grantDays > maxDays) {
      return null;
    } else if ("CONSTRAINED".equals(constrained)) {
      return new McpToolGrantChoice(grantScope, grantDays, true);
    } else if ("ANY".equals(constrained)) {
      return new McpToolGrantChoice(grantScope, grantDays, false);
    } else {
      return null;
    }
  }

  /**
   * Builds and stores the grant the user chose on a card. Every field comes
   * from the pending call and the offered constraint; the choice only selects
   * among them. A card grant is always a user grant and never unattended.
   *
   * @param request            the pending call the card was shown for
   * @param choice             the checked choice
   * @param offeredConstraint  the constraint the card offered, may be null
   * @return the stored grant
   * @throws IllegalArgumentException when the choice doesn't fit the call (an
   *                                    agent scope without a known agent, a
   *                                    constrained grant with no offered
   *                                    constraint)
   * @throws IllegalStateException    when no grant store is installed
   */
  public McpToolGrant createGrant(McpToolGrantRequest request,
                                  McpToolGrantChoice choice,
                                  McpToolGrantConstraint offeredConstraint) {
    McpToolGrantProvider provider = getProvider();
    if (provider == null) {
      throw new IllegalStateException("No standing approval store is installed");
    } else if (request == null || StringUtils.isBlank(request.username()) || StringUtils.isBlank(request.toolName())) {
      throw new IllegalArgumentException("A standing approval needs a user and a tool");
    } else if (choice == null) {
      throw new IllegalArgumentException("No standing approval choice");
    } else if (choice.scope() == McpToolGrantScope.AGENT && StringUtils.isBlank(request.agentNameId())) {
      throw new IllegalArgumentException("An agent-scoped standing approval needs the calling agent");
    } else if (choice.constrained() && offeredConstraint == null) {
      throw new IllegalArgumentException("A limited standing approval needs the limit the card offered");
    }
    Instant now = clock.instant();
    McpToolGrant grant = McpToolGrant.builder()
                                     .ownerType(McpToolGrantOwnerType.USER)
                                     .username(request.username())
                                     .toolName(request.toolName())
                                     .agentNameId(choice.scope() == McpToolGrantScope.AGENT ? request.agentNameId() : null)
                                     .constraint(choice.constrained() ? offeredConstraint : null)
                                     .unattended(false)
                                     .createdAt(now)
                                     .expiresAt(now.plus(Duration.ofDays(choice.days())))
                                     .build();
    return provider.createGrant(grant, request);
  }

  /**
   * Tells whether one grant covers one call; see the class comment for the
   * rules.
   *
   * @param grant     the candidate grant
   * @param request   the call
   * @param arguments the call arguments as the tool receives them
   * @param now       the decision instant
   * @return true when the grant covers the call
   */
  private boolean covers(McpToolGrant grant, McpToolGrantRequest request, Map<String, Object> arguments, Instant now) {
    if (!Strings.CS.equals(grant.getToolName(), request.toolName())) {
      return false;
    } else if (grant.getOwnerType() == McpToolGrantOwnerType.USER) {
      if (StringUtils.isBlank(grant.getUsername()) || !Strings.CS.equals(grant.getUsername(), request.username())) {
        return false;
      }
    } else if (grant.getOwnerType() != McpToolGrantOwnerType.PLATFORM) {
      return false;
    }
    if (grant.getExpiresAt() == null || !now.isBefore(grant.getExpiresAt())) {
      return false;
    } else if (grant.getAgentNameId() != null && !Strings.CS.equals(grant.getAgentNameId(), request.agentNameId())) {
      return false;
    } else if (!grant.isUnattended() && StringUtils.isBlank(request.conversationId())) {
      return false;
    } else if (grant.getConstraint() == null) {
      return true;
    } else {
      return matchesConstraint(grant.getConstraint(), request.toolName(), arguments);
    }
  }

  /**
   * Asks the tool's evaluator whether the call satisfies a constraint, failing
   * closed when there is no evaluator or it fails.
   *
   * @param constraint the grant's constraint
   * @param toolName   the MCP tool name
   * @param arguments  the call arguments as the tool receives them
   * @return true only when the evaluator confirms the call stays within it
   */
  private boolean matchesConstraint(McpToolGrantConstraint constraint, String toolName, Map<String, Object> arguments) {
    McpToolGrantConstraintEvaluator evaluator = getEvaluator(toolName);
    if (evaluator == null || arguments == null) {
      return false;
    }
    try {
      return evaluator.matches(toolName, arguments, constraint);
    } catch (RuntimeException e) {
      log.warn("Argument limit of tool '{}' couldn't be checked, the grant doesn't apply", toolName, e);
      return false;
    }
  }

  /**
   * @return the single installed grant store, or null when none or several
   *         are installed
   */
  private McpToolGrantProvider getProvider() {
    return grantProviders == null ? null : grantProviders.getIfUnique();
  }

  /**
   * @param toolName the MCP tool name
   * @return the first evaluator owning that tool's constraints, or null
   */
  private McpToolGrantConstraintEvaluator getEvaluator(String toolName) {
    if (constraintEvaluators == null || StringUtils.isBlank(toolName)) {
      return null;
    }
    return constraintEvaluators.orderedStream()
                               .filter(evaluator -> supports(evaluator, toolName))
                               .findFirst()
                               .orElse(null);
  }

  /**
   * @param evaluator a contributed evaluator
   * @param toolName  the MCP tool name
   * @return whether the evaluator owns the tool, false when it fails to say
   */
  private boolean supports(McpToolGrantConstraintEvaluator evaluator, String toolName) {
    try {
      return evaluator.supports(toolName);
    } catch (RuntimeException e) {
      log.warn("Grant constraint evaluator '{}' failed to tell whether it owns tool '{}'", evaluator, toolName, e);
      return false;
    }
  }

}

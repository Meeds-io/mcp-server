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

import static io.meeds.mcp.server.util.McpToolUtils.EVENT_MCP_SERVER_AUDIENCE_UPDATED;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.security.Identity;

import lombok.extern.slf4j.Slf4j;

/**
 * Holds the <em>audience</em> of the MCP server: the list of permission
 * expressions saying which users may open an MCP session at all, beside the
 * global {@code mcp.server} on/off flag that
 * {@link McpServerToolService#isMcpServerEnabled()} reads.
 * <p>
 * The storage idiom is the one {@code AiSettingService} established in the
 * {@code ai} addon — a {@link SettingService} entry with a property default —
 * and so is its memoisation discipline, for the reason that service's own
 * Javadoc records: a blank read is indistinguishable from an absent setting,
 * so memoising a <em>defaulted</em> value would let one failed read pin that
 * default for the JVM's lifetime. Here the default is the widest audience
 * there is, so pinning it would turn a transient storage failure into a
 * permanently open door. Only a value that really came back from the store is
 * memoised, and {@link #savePermissions(List)} drops the memo.
 * <p>
 * The permission-expression grammar deliberately mirrors
 * {@code ExoFeatureServiceImpl}'s own {@code exo.feature.<name>.permissions}
 * fallback, which registering an {@code McpServerFeaturePlugin} switches off:
 * a deployment that had configured that property must keep the exact access it
 * had. That is also why {@link #defaultPermissions()} reads that very property
 * as its default.
 */
@Service
@Slf4j
public class McpServerAudienceService {

  /**
   * Widest possible audience: every platform user. It is the shipped default
   * because it is the status quo — before this gate existed, the global
   * {@code mcp.server} flag let every authenticated user in — and an upgrade
   * must not change anybody's access.
   */
  public static final String   DEFAULT_PERMISSION        = "*:/platform/users";

  public static final String   PERMISSIONS_KEY           = "MCP_SERVER_PERMISSIONS";

  private static final Context MCP_SERVER_CONTEXT        = Context.GLOBAL.id("MCP_SERVER");

  private static final Scope   MCP_SERVER_SETTINGS_SCOPE = Scope.APPLICATION.id("MCP_SERVER_SETTINGS");

  private static final String  MEMBERSHIP_ANY_PREFIX     = "*:";

  @Autowired
  private SettingService       settingService;

  @Autowired
  private UserACL              userAcl;

  @Autowired
  private ListenerService      listenerService;

  /**
   * Default audience, read from the very property
   * {@code ExoFeatureServiceImpl} consults when no {@code FeaturePlugin} is
   * registered for the feature. Registering the plugin makes that fallback
   * unreachable, so reading the same property here is what keeps a deployment
   * that had narrowed MCP access by property narrowed after the upgrade,
   * instead of silently reopening it to {@link #DEFAULT_PERMISSION}.
   */
  @Value("#{'${exo.feature.mcp.server.permissions:" + DEFAULT_PERMISSION + "}'.split(',')}")
  private List<String>         defaultPermissions;

  /**
   * Volatile because it is written from the request thread that saves the
   * audience and read from every other request thread evaluating the gate;
   * without it a save is not guaranteed to be seen by a thread that already
   * read the old value.
   */
  private volatile List<String> permissions;

  /**
   * Tells whether a user belongs to the MCP audience. This is the question
   * {@code McpServerFeaturePlugin} answers on behalf of
   * {@code ExoFeatureService.isFeatureActiveForUser("mcp.server", username)},
   * so it decides access for every present and future caller of that API
   * without any of them knowing how the audience is stored.
   *
   * @param username the platform login of the end user, may be null or blank
   * @return true when the user matches at least one permission expression of
   *         the audience, false when the user is unknown, when no username was
   *         resolved, or when an administrator deliberately emptied the
   *         audience
   */
  public boolean isUserInAudience(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    List<String> audience = getPermissions();
    if (CollectionUtils.isEmpty(audience)) {
      // An empty audience means nobody, not everybody. It can only be reached
      // by an administrator explicitly saving an empty list: an audience that
      // was never configured reads as defaultPermissions(), never as empty.
      // ExoFeatureServiceImpl's property fallback answers "everybody" here
      // instead, because for a system property "unset" and "empty" are the
      // same thing; they are not the same thing once the value is stored, and
      // reading a cleared picker as "open to all" is the one interpretation an
      // administrator can never have meant.
      return false;
    }
    // Resolved at most once, and only if a group expression actually needs it:
    // a bare username expression is matched on the name alone, exactly as
    // ExoFeatureServiceImpl matches it, so naming a user in the audience keeps
    // working whether or not their identity resolves.
    Supplier<Identity> identitySupplier = memoize(() -> userAcl.getUserIdentity(username));
    return audience.stream()
                   .filter(StringUtils::isNotBlank)
                   .anyMatch(permission -> matchesPermission(username, identitySupplier, permission));
  }

  /**
   * Wraps a supplier so that it is evaluated at most once, including when it
   * answers null. Used to resolve the user identity lazily: most audiences are
   * one group expression, and an audience that matches on the username alone
   * must not pay a lookup at all.
   *
   * @param supplier the supplier to memoize
   * @return a supplier answering the first computed value on every call
   */
  private Supplier<Identity> memoize(Supplier<Identity> supplier) {
    Identity[] resolved = new Identity[1];
    boolean[] evaluated = new boolean[1];
    return () -> {
      if (!evaluated[0]) {
        resolved[0] = supplier.get();
        evaluated[0] = true;
      }
      return resolved[0];
    };
  }

  /**
   * @return the audience as stored, or the property default when nothing is
   *         stored. The result is memoised only when it really came from the
   *         store — see this class' Javadoc for why a defaulted read must not
   *         be pinned.
   */
  public List<String> getPermissions() {
    List<String> cached = permissions;
    if (cached != null) {
      return cached;
    }
    String storedPermissions = getStoredPermissions();
    if (storedPermissions == null) {
      return defaultPermissions();
    }
    List<String> storedAudience = Arrays.stream(StringUtils.split(storedPermissions, ","))
                                        .map(StringUtils::trimToEmpty)
                                        .filter(StringUtils::isNotBlank)
                                        .toList();
    permissions = storedAudience;
    return storedAudience;
  }

  /**
   * Replaces the audience and drops the memo, so the change takes effect on
   * the next request rather than at the next restart. Broadcast so that any
   * addon caching a per-user answer — the {@code ai} administration UI among
   * them — can react.
   *
   * @param audience the new list of permission expressions; null or empty
   *                 means nobody, as {@link #isUserInAudience(String)}
   *                 documents
   */
  public void savePermissions(List<String> audience) {
    List<String> oldAudience = getPermissions();
    setStoredPermissions(audience);
    this.permissions = null;
    log.info("Update MCP Server audience to {}", audience);
    listenerService.broadcast(EVENT_MCP_SERVER_AUDIENCE_UPDATED, oldAudience, audience);
  }

  /**
   * Matches one permission expression against a user identity, with exactly
   * the grammar {@code ExoFeatureServiceImpl.isUserMemberOf} applies to the
   * {@code exo.feature.<name>.permissions} property this plugin supersedes:
   * {@code membershipType:/group}, a bare {@code /group} (any membership
   * type), or a bare username. A leading {@code *:} is stripped first, which
   * makes {@code *:/platform/users} a group expression rather than a
   * membership one.
   *
   * @param username             the platform login being checked, never blank
   *                             here
   * @param identitySupplier     resolves that user's identity on demand, and
   *                             may answer null for a user no identity is
   *                             found for
   * @param permissionExpression one expression of the audience, never blank
   *                             here
   * @return true when the user satisfies the expression
   */
  private boolean matchesPermission(String username, Supplier<Identity> identitySupplier, String permissionExpression) {
    String expression = StringUtils.trimToEmpty(permissionExpression).replace(MEMBERSHIP_ANY_PREFIX, "");
    if (expression.contains(":")) {
      String[] permissionParts = expression.split(":");
      if (permissionParts.length != 2) {
        // Refused rather than propagated: the original throws
        // ArrayIndexOutOfBoundsException on "member:", and a malformed
        // expression breaking the whole gate is worse than one that matches
        // nobody. Refusing also cannot widen access.
        log.warn("Ignoring malformed MCP audience permission expression '{}'", permissionExpression);
        return false;
      }
      Identity identity = identitySupplier.get();
      return identity != null && identity.isMemberOf(permissionParts[1], permissionParts[0]);
    } else if (expression.contains("/")) {
      Identity identity = identitySupplier.get();
      return identity != null && identity.isMemberOf(expression);
    } else {
      return Strings.CS.equals(username, expression);
    }
  }

  /**
   * @return the audience to use when nothing is stored: the
   *         {@code exo.feature.mcp.server.permissions} property when it holds
   *         anything usable, else {@link #DEFAULT_PERMISSION}. A property that
   *         is present but blank falls back to the default rather than to an
   *         empty audience, because a blank property means "unrestricted" to
   *         {@code ExoFeatureServiceImpl} and must keep meaning that here.
   */
  private List<String> defaultPermissions() {
    List<String> configuredDefault = defaultPermissions == null ? Collections.emptyList() :
                                                                defaultPermissions.stream()
                                                                                  .map(StringUtils::trimToEmpty)
                                                                                  .filter(StringUtils::isNotBlank)
                                                                                  .toList();
    return configuredDefault.isEmpty() ? List.of(DEFAULT_PERMISSION) : configuredDefault;
  }

  /**
   * @return the stored audience as a comma-separated string, or null when
   *         nothing is stored — which is also what a failed read answers,
   *         hence the memoisation rule of this class
   */
  private String getStoredPermissions() {
    SettingValue<?> settingValue = settingService.get(MCP_SERVER_CONTEXT,
                                                      MCP_SERVER_SETTINGS_SCOPE,
                                                      PERMISSIONS_KEY);
    return settingValue == null || settingValue.getValue() == null ? null : settingValue.getValue().toString();
  }

  /**
   * Stores the audience as a comma-separated string.
   *
   * @param audience the new audience, null being stored as an empty value
   */
  private void setStoredPermissions(List<String> audience) {
    settingService.set(MCP_SERVER_CONTEXT,
                       MCP_SERVER_SETTINGS_SCOPE,
                       PERMISSIONS_KEY,
                       SettingValue.create(audience == null ? "" : StringUtils.join(audience, ",")));
  }

}

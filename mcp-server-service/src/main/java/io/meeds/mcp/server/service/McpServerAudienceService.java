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
 * but deliberately <b>not</b> its field memo. That service's own Javadoc
 * records why memoising a <em>defaulted</em> read is a trap: a blank read is
 * indistinguishable from an absent setting, so one failed read pins the
 * default for the JVM's lifetime. Here the default is the widest audience
 * there is, so that trap is a permanently open door. A field memo guarded
 * against only that much still leaves two ways to serve a stale <em>wide</em>
 * audience, and on a security input staleness is the whole risk:
 * <ul>
 * <li>a reader that has already read the store when a narrowing save lands
 * publishes the pre-narrowing list <em>after</em> the save dropped the memo,
 * and that value then answers every later call;</li>
 * <li>{@code ListenerService} is an in-JVM event bus, so a save on one
 * cluster node never invalidates the memo held by the others, which keep
 * answering the old audience with no TTL to age it out. Reading through
 * {@code SettingService} is only better than that where the deployment
 * configures its cache as a clustered or invalidating one — on a local-only
 * cache configuration a per-call read is exactly as stale as a field.</li>
 * </ul>
 * So the audience is read on each call instead. That is not a database hit:
 * {@code SettingService} is itself cache-backed, and it is the very cost
 * {@code ExoFeatureServiceImpl.isActiveFeature} already pays per call for the
 * global flag on this same path — with the cluster-awareness that a field
 * above that cache would have removed.
 * <p>
 * The permission-expression grammar deliberately mirrors
 * {@code ExoFeatureServiceImpl}'s own {@code exo.feature.<name>.permissions}
 * fallback. The audience is resolved by this service alone — no
 * {@code FeaturePlugin} is registered for the feature, so that fallback never
 * decides MCP access — and the property is read here instead, as the default
 * of {@code defaultPermissions}, so that a deployment which had narrowed MCP
 * access with it keeps the exact access it had.
 */
@Service
@Slf4j
public class McpServerAudienceService {

  /**
   * Widest audience of <em>internal</em> users, and the shipped default: it is
   * the value the platform's own {@code exo.feature.<name>.permissions}
   * examples use, and it keeps MCP open to every member of
   * {@code /platform/users} as the global flag alone did.
   * <p>
   * It is <b>not</b> the exact status quo, and the difference is on purpose.
   * An <em>external</em> user — a login that carries
   * {@code /platform/externals} and not {@code /platform/users}, which
   * {@code PortalAuthenticationManager} admits — could open an MCP session
   * before this gate existed, because nothing asked a per-user question at
   * all, and cannot after it. Narrowing MCP away from external accounts is the
   * intended reading of "who may use the MCP server"; a deployment that wants
   * them back adds {@code *:/platform/externals} to the audience. This belongs
   * in the addon's upgrade notes, not only here.
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
   * Default audience, read from the {@code exo.feature.mcp.server.permissions}
   * property. The audience is resolved by this service alone — nothing on the
   * MCP request path consults {@code ExoFeatureServiceImpl}'s own property
   * fallback — so the property is read here, in order that a deployment which
   * had narrowed MCP access by setting it keeps that meaning after the
   * upgrade, instead of silently reopening to {@link #DEFAULT_PERMISSION}.
   */
  @Value("#{'${exo.feature.mcp.server.permissions:" + DEFAULT_PERMISSION + "}'.split(',')}")
  private List<String>         defaultPermissions;

  /**
   * Tells whether a user belongs to the MCP audience. This is the one place
   * the question is answered: the gate,
   * {@code McpServerToolService.isMcpServerEnabledForUser}, asks it directly,
   * so no caller needs to know how the audience is stored.
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
   *         stored. Read from {@code SettingService} on every call and never
   *         held in a field — see this class' Javadoc for the two ways a memo
   *         here serves a stale, and therefore too wide, audience.
   */
  public List<String> getPermissions() {
    String storedPermissions = getStoredPermissions();
    if (storedPermissions == null) {
      return defaultPermissions();
    }
    return Arrays.stream(StringUtils.split(storedPermissions, ","))
                 .map(StringUtils::trimToEmpty)
                 .filter(StringUtils::isNotBlank)
                 .toList();
  }

  /**
   * Replaces the audience. The change takes effect on the next request, on
   * this node and on every other, because nothing above
   * {@code SettingService} holds the previous value. Broadcast so that any
   * addon keeping a per-user answer of its own — the {@code ai}
   * administration UI among them — can react.
   *
   * @param audience the new list of permission expressions; null or empty
   *                 means nobody, as {@link #isUserInAudience(String)}
   *                 documents
   */
  public void savePermissions(List<String> audience) {
    List<String> oldAudience = getPermissions();
    setStoredPermissions(audience);
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
        // Any expression that does not split into exactly two parts, which is
        // two divergences from the original, both narrowing: it throws
        // ArrayIndexOutOfBoundsException on "member:", and it silently matches
        // "a:b:c" on its first two parts. A malformed expression breaking the
        // whole gate is worse than one that matches nobody, and refusing
        // cannot widen access.
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
   *         nothing is stored. A <em>failed</em> read answers null too:
   *         {@code CacheSettingServiceImpl.get} catches every exception and
   *         returns null, so this layer cannot tell "nothing stored" from "the
   *         store is down". The caller therefore falls back to
   *         {@link #defaultPermissions()} — the widest audience — on every
   *         call for as long as the failure lasts. Dropping the memo removed
   *         the worst form of that (one failed read pinning the default for
   *         the JVM's lifetime); the per-call residual is accepted, and is
   *         bounded by the fact that the global {@code mcp.server} flag, read
   *         through the same {@code SettingService}, gates the same requests.
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

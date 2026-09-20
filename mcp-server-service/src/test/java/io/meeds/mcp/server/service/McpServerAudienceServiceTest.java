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

import static io.meeds.mcp.server.service.McpServerAudienceService.DEFAULT_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.MembershipEntry;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
@DisplayName("MCP server audience")
class McpServerAudienceServiceTest {

  private static final String      DEFAULT_PERMISSIONS_FIELD = "defaultPermissions";

  private static final String      USERNAME                  = "john";

  private static final String      USERS_GROUP               = "/platform/users";

  private static final String      EXTERNALS_GROUP           = "/platform/externals";

  private static final String      ADMINISTRATORS_GROUP      = "/platform/administrators";

  @Mock
  private SettingService           settingService;

  @Mock
  private UserACL                  userAcl;

  @Mock
  private ListenerService          listenerService;

  @InjectMocks
  private McpServerAudienceService audienceService;

  @BeforeEach
  void init() {
    ReflectionTestUtils.setField(audienceService, DEFAULT_PERMISSIONS_FIELD, List.of(DEFAULT_PERMISSION));
  }

  @Test
  @DisplayName("Nothing stored means the status quo: every platform user")
  void defaultsToEveryPlatformUser() {
    when(settingService.get(any(), any(), any())).thenReturn(null);

    assertEquals(List.of(DEFAULT_PERMISSION), audienceService.getPermissions());

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(identityOf(USERNAME, USERS_GROUP));
    assertTrue(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("The audience is never held in a field, so no read can pin a stale one")
  void neverMemoisesTheAudience() {
    when(settingService.get(any(), any(), any())).thenReturn(null);
    audienceService.getPermissions();
    audienceService.getPermissions();

    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(ADMINISTRATORS_GROUP));
    assertEquals(List.of(ADMINISTRATORS_GROUP), audienceService.getPermissions());

    // Read on every call: a memo above SettingService would serve the old
    // audience to a concurrent reader that had already read the store, and to
    // every other cluster node for ever, since ListenerService is in-JVM only
    verify(settingService, times(3)).get(any(), any(), any());
  }

  @Test
  @DisplayName("A narrowing save is visible to the very next read")
  void savingIsVisibleAtOnce() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(USERS_GROUP));
    assertEquals(List.of(USERS_GROUP), audienceService.getPermissions());

    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(ADMINISTRATORS_GROUP));
    audienceService.savePermissions(List.of(ADMINISTRATORS_GROUP));

    assertEquals(List.of(ADMINISTRATORS_GROUP), audienceService.getPermissions());
    verify(listenerService).broadcast(any(), any(), any());
  }

  @Test
  @DisplayName("An audience an administrator emptied means nobody, not everybody")
  void anEmptyAudienceMeansNobody() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(""));

    assertTrue(audienceService.getPermissions().isEmpty());
    assertFalse(audienceService.isUserInAudience(USERNAME));
    verify(userAcl, never()).getUserIdentity(any());
  }

  @Test
  @DisplayName("A user outside the audience group is refused")
  void refusesAUserOutsideTheAudienceGroup() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create("*:" + ADMINISTRATORS_GROUP));

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(identityOf(USERNAME, USERS_GROUP));

    assertFalse(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("An external user is outside the shipped default audience")
  void refusesAnExternalUserUnderTheShippedDefault() {
    when(settingService.get(any(), any(), any())).thenReturn(null);
    ReflectionTestUtils.setField(audienceService, DEFAULT_PERMISSIONS_FIELD, List.of(DEFAULT_PERMISSION));

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(identityOf(USERNAME, EXTERNALS_GROUP));

    // Deliberate, and the one population whose access the upgrade narrows: an
    // external login carries /platform/externals and not /platform/users, so
    // the shipped default excludes it. Pinned so that widening or narrowing
    // that choice is a visible edit rather than a side effect
    assertFalse(audienceService.isUserInAudience(USERNAME));

    ReflectionTestUtils.setField(audienceService,
                                 DEFAULT_PERMISSIONS_FIELD,
                                 List.of(DEFAULT_PERMISSION, "*:" + EXTERNALS_GROUP));
    assertTrue(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("A membership expression matches only that membership type")
  void matchesAMembershipExpression() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create("manager:" + USERS_GROUP));

    when(userAcl.getUserIdentity("plain-member")).thenReturn(new Identity("plain-member",
                                                                          Set.of(new MembershipEntry(USERS_GROUP, "member"))));
    // The "only" half of the claim: same group, other membership type. Drop
    // the type argument from the isMemberOf call and this is what fails
    assertFalse(audienceService.isUserInAudience("plain-member"));

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(new Identity(USERNAME,
                                                                    Set.of(new MembershipEntry(USERS_GROUP, "manager"))));

    assertTrue(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("A bare username is matched without resolving an identity")
  void matchesABareUsername() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(USERNAME));

    assertTrue(audienceService.isUserInAudience(USERNAME));
    assertFalse(audienceService.isUserInAudience("someone-else"));
    verify(userAcl, never()).getUserIdentity(any());
  }

  /**
   * Guards the pluggable-authenticator path rather than one the shipped
   * {@code OrganizationAuthenticatorImpl} produces: that one answers an
   * identity with no membership for an unknown login rather than null, and a
   * blank login is already refused before the lookup. A custom
   * {@code AuthenticatorPlugin} may still answer null, and the gate must
   * refuse rather than throw when it does.
   */
  @Test
  @DisplayName("A user with no platform identity is refused, not crashed on")
  void refusesAUserWithNoIdentity() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(DEFAULT_PERMISSION));

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(null);

    assertFalse(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("No username means no access")
  void refusesABlankUsername() {
    assertFalse(audienceService.isUserInAudience(null));
    assertFalse(audienceService.isUserInAudience(""));
    verify(settingService, never()).get(any(), any(), any());
  }

  @Test
  @DisplayName("A malformed expression matches nobody instead of breaking the gate")
  void ignoresAMalformedExpression() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create("manager:"));

    assertFalse(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("A deployment that narrowed access by property stays narrowed")
  void honoursTheFeaturePermissionProperty() {
    ReflectionTestUtils.setField(audienceService, DEFAULT_PERMISSIONS_FIELD, List.of("*:" + ADMINISTRATORS_GROUP));
    when(settingService.get(any(), any(), any())).thenReturn(null);

    assertEquals(List.of("*:" + ADMINISTRATORS_GROUP), audienceService.getPermissions());

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(identityOf(USERNAME, USERS_GROUP));
    assertFalse(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("A property that is present but blank keeps meaning unrestricted")
  void treatsABlankPropertyAsTheDefault() {
    ReflectionTestUtils.setField(audienceService, DEFAULT_PERMISSIONS_FIELD, List.of(""));
    when(settingService.get(any(), any(), any())).thenReturn(null);

    assertEquals(List.of(DEFAULT_PERMISSION), audienceService.getPermissions());
  }

  /**
   * Builds a user identity belonging to one group with any membership type.
   *
   * @param username the user login
   * @param group    the group the user belongs to
   * @return the identity
   */
  private Identity identityOf(String username, String group) {
    return new Identity(username, Set.of(new MembershipEntry(group)));
  }

}

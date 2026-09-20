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

  private static final String      PERMISSIONS_FIELD         = "permissions";

  private static final String      USERNAME                  = "john";

  private static final String      USERS_GROUP               = "/platform/users";

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
  @DisplayName("A defaulted audience is never memoised, so a failed read cannot pin it")
  void doesNotMemoiseADefaultedAudience() {
    when(settingService.get(any(), any(), any())).thenReturn(null);

    audienceService.getPermissions();
    audienceService.getPermissions();

    // Read again every time: memoising the default would make one failed read
    // a permanently wide-open door for the JVM's lifetime.
    verify(settingService, times(2)).get(any(), any(), any());
  }

  @Test
  @DisplayName("A stored audience is memoised, so the gate costs one read")
  void memoisesAStoredAudience() {
    when(settingService.get(any(), any(), any())).thenReturn((SettingValue) SettingValue.create(ADMINISTRATORS_GROUP));

    assertEquals(List.of(ADMINISTRATORS_GROUP), audienceService.getPermissions());
    assertEquals(List.of(ADMINISTRATORS_GROUP), audienceService.getPermissions());

    verify(settingService, times(1)).get(any(), any(), any());
  }

  @Test
  @DisplayName("Saving a new audience takes effect at once, without a restart")
  void savingInvalidatesTheMemo() {
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
    ReflectionTestUtils.setField(audienceService, PERMISSIONS_FIELD, List.of("*:" + ADMINISTRATORS_GROUP));

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(identityOf(USERNAME, USERS_GROUP));

    assertFalse(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("A membership expression matches only that membership type")
  void matchesAMembershipExpression() {
    ReflectionTestUtils.setField(audienceService, PERMISSIONS_FIELD, List.of("manager:" + USERS_GROUP));

    when(userAcl.getUserIdentity(USERNAME)).thenReturn(new Identity(USERNAME,
                                                                    Set.of(new MembershipEntry(USERS_GROUP, "manager"))));

    assertTrue(audienceService.isUserInAudience(USERNAME));
  }

  @Test
  @DisplayName("A bare username is matched without resolving an identity")
  void matchesABareUsername() {
    ReflectionTestUtils.setField(audienceService, PERMISSIONS_FIELD, List.of(USERNAME));

    assertTrue(audienceService.isUserInAudience(USERNAME));
    assertFalse(audienceService.isUserInAudience("someone-else"));
    verify(userAcl, never()).getUserIdentity(any());
  }

  @Test
  @DisplayName("A user with no platform identity is refused, not crashed on")
  void refusesAUserWithNoIdentity() {
    ReflectionTestUtils.setField(audienceService, PERMISSIONS_FIELD, List.of(DEFAULT_PERMISSION));

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
    ReflectionTestUtils.setField(audienceService, PERMISSIONS_FIELD, List.of("manager:"));

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

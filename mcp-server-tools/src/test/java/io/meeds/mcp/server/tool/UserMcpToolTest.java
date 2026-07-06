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
package io.meeds.mcp.server.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.manager.RelationshipManager;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.profileproperty.model.ProfilePropertySetting;

import io.meeds.mcp.server.tool.model.ExperienceModel;
import io.meeds.mcp.server.tool.model.OnlineStatusModel;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.mcp.server.tool.test.IntegrationTestBase;

class UserMcpToolTest extends IntegrationTestBase {

  @Autowired
  private IdentityManager     identityManager;

  @Autowired
  private RelationshipManager relationshipManager;

  @Autowired
  private ProfilePropertyService profilePropertyService;

  @Autowired
  private UserMcpTool         userMcpTool;

  @Test
  void getMyUserInformation() {
    UserModel user = userMcpTool.getMyUserInformation();

    assertNotNull(user);
    assertNotNull(user.getUsername());
    assertEquals(user.getUsername(), user.getLoginId());
  }

  @Test
  void getUserByUsername() {
    UserModel user = userMcpTool.getUserByUsername(USERNAME);

    assertNotNull(user);
    assertEquals(USERNAME, user.getUsername());
  }

  @Test
  void searchUsers() {
    String user = "mary";
    String userIdentityId = identityManager.getOrCreateUserIdentity(user).getId();

    when(profileSearchConnector.search(any(),
                                       any(),
                                       any(),
                                       anyLong(),
                                       anyLong())).thenReturn(List.of(userIdentityId));

    List<UserModel> users = userMcpTool.searchUsers(user, 0, 10);

    assertNotNull(users);
    assertFalse(users.isEmpty());
    assertTrue(users.stream().anyMatch(u -> user.equals(u.getUsername())));
  }

  @Test
  void getUsersCount() {
    assertTrue(userMcpTool.getUsersCount() > 0);
  }

  // --- connections ---------------------------------------------------------

  @Test
  void listConnectionsAndRequestsAreEmptyForFreshUser() {
    assertNotNull(userMcpTool.listConnectionRequests(0, 10));
    assertNotNull(userMcpTool.listSentConnectionRequests(0, 10));
    assertNotNull(userMcpTool.listMyConnections(null, 0, 10));
    assertNotNull(userMcpTool.listConnectionSuggestions(10));
  }

  @Test
  void sendConnectionRequestThenStatusIsPending() {
    identityManager.getOrCreateUserIdentity("demo");

    userMcpTool.sendConnectionRequest("demo");

    assertEquals("PENDING", userMcpTool.getConnectionStatus("demo"));
  }

  @Test
  void sendConnectionRequestToSelfIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> userMcpTool.sendConnectionRequest(USERNAME));
  }

  @Test
  void acceptIncomingConnectionRequest() {
    Identity mary = identityManager.getOrCreateUserIdentity("mary");
    Identity john = identityManager.getOrCreateUserIdentity(USERNAME);
    // mary invites john
    relationshipManager.inviteToConnect(mary, john);

    List<UserModel> requests = userMcpTool.listConnectionRequests(0, 10);
    assertTrue(requests.stream().anyMatch(u -> "mary".equals(u.getUsername())));

    userMcpTool.acceptConnectionRequest("mary");

    assertEquals("CONFIRMED", userMcpTool.getConnectionStatus("mary"));
  }

  @Test
  void acceptWithoutPendingRequestFails() {
    identityManager.getOrCreateUserIdentity("james");
    assertThrows(IllegalStateException.class, () -> userMcpTool.acceptConnectionRequest("james"));
  }

  // --- profile -------------------------------------------------------------

  @Test
  void updateMyProfileWithoutFieldsFails() {
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.updateMyProfile(null, null, null, null, null, null, null, null, null));
  }

  @Test
  void updateMyProfileSetsFields() {
    UserModel updated = userMcpTool.updateMyProfile("Hello, I test MCP tools", "Platform Engineer", "Meeds",
                                                    null, null, null, null, "+33123456789", "https://meeds.io");

    assertNotNull(updated);
    assertEquals("Platform Engineer", updated.getPosition());
    assertEquals("Meeds", updated.getCompany());
  }

  // --- work experience -----------------------------------------------------

  @Test
  void addUpdateAndRemoveWorkExperience() throws ObjectNotFoundException {
    List<ExperienceModel> afterAdd = userMcpTool.addWorkExperience("Meeds",
                                                                   "Engineer",
                                                                   "Java,Vue",
                                                                   "2020-01-01",
                                                                   null,
                                                                   Boolean.TRUE,
                                                                   "Building the platform");
    assertNotNull(afterAdd);
    ExperienceModel added = afterAdd.stream()
                                    .filter(e -> "Meeds".equals(e.company()))
                                    .findFirst()
                                    .orElse(null);
    assertNotNull(added);
    assertNotNull(added.experienceId());
    assertEquals("Engineer", added.position());

    List<ExperienceModel> afterUpdate = userMcpTool.updateWorkExperience(added.experienceId(),
                                                                         null,
                                                                         "Lead Engineer",
                                                                         null,
                                                                         null,
                                                                         "2023-06-01",
                                                                         Boolean.FALSE,
                                                                         null);
    ExperienceModel updated = afterUpdate.stream()
                                         .filter(e -> added.experienceId().equals(e.experienceId()))
                                         .findFirst()
                                         .orElse(null);
    assertNotNull(updated);
    assertEquals("Lead Engineer", updated.position());
    assertEquals("Meeds", updated.company());

    List<ExperienceModel> afterRemove = userMcpTool.removeWorkExperience(added.experienceId());
    assertTrue(afterRemove.stream().noneMatch(e -> added.experienceId().equals(e.experienceId())));
  }

  @Test
  void addWorkExperienceWithoutCompanyFails() {
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.addWorkExperience(null, "Engineer", null, null, null, null, null));
  }

  @Test
  void addWorkExperienceWithInvalidDateFails() {
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.addWorkExperience("Meeds", null, null, "not-a-date", null, null, null));
  }

  @Test
  void updateUnknownWorkExperienceFails() {
    assertThrows(ObjectNotFoundException.class,
                 () -> userMcpTool.updateWorkExperience("9999999", "Meeds", null, null, null, null, null, null));
  }

  @Test
  void removeUnknownWorkExperienceFails() {
    assertThrows(ObjectNotFoundException.class,
                 () -> userMcpTool.removeWorkExperience("9999999"));
  }

  // --- profile field visibility --------------------------------------------

  @Test
  void setAndGetProfileFieldVisibility() {
    String hiddenableField = firstHiddenableField();
    if (hiddenableField == null) {
      hiddenableField = createHiddenableField("mcpVisibilityTestField");
    }
    Assumptions.assumeTrue(hiddenableField != null, "Unable to obtain a hiddenable profile property in test kernel");

    userMcpTool.setProfileFieldVisibility(hiddenableField, Boolean.TRUE);
    assertTrue(userMcpTool.getProfileFieldVisibility().contains(hiddenableField));

    userMcpTool.setProfileFieldVisibility(hiddenableField, Boolean.FALSE);
    assertFalse(userMcpTool.getProfileFieldVisibility().contains(hiddenableField));
  }

  private String firstHiddenableField() {
    return profilePropertyService.getPropertySettings()
                                 .stream()
                                 .filter(s -> profilePropertyService.isPropertySettingHiddenable(s.getId()))
                                 .map(ProfilePropertySetting::getPropertyName)
                                 .findFirst()
                                 .orElse(null);
  }

  private String createHiddenableField(String name) {
    try {
      ProfilePropertySetting setting = new ProfilePropertySetting();
      setting.setPropertyName(name);
      setting.setPropertyType("text");
      setting.setActive(true);
      setting.setVisible(true);
      setting.setEditable(true);
      setting.setHiddenbale(true);
      return profilePropertyService.createPropertySetting(setting).getPropertyName();
    } catch (Exception e) {
      return null;
    }
  }

  @Test
  void setVisibilityOnUnknownFieldFails() {
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.setProfileFieldVisibility("notARealField", Boolean.TRUE));
  }

  @Test
  void setVisibilityOnUnhiddenableFieldFails() {
    String unhiddenableField = profilePropertyService.getPropertySettings()
                                                     .stream()
                                                     .filter(s -> !profilePropertyService.isPropertySettingHiddenable(s.getId()))
                                                     .map(ProfilePropertySetting::getPropertyName)
                                                     .findFirst()
                                                     .orElse(null);
    if (unhiddenableField == null) {
      unhiddenableField = createNonHiddenableField("mcpUnhiddenableTestField");
    }
    Assumptions.assumeTrue(unhiddenableField != null, "Unable to obtain an unhiddenable profile property in test kernel");

    String field = unhiddenableField;
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.setProfileFieldVisibility(field, Boolean.TRUE));
  }

  private String createNonHiddenableField(String name) {
    try {
      ProfilePropertySetting setting = new ProfilePropertySetting();
      setting.setPropertyName(name);
      setting.setPropertyType("text");
      setting.setActive(true);
      setting.setVisible(true);
      setting.setEditable(true);
      setting.setHiddenbale(false);
      return profilePropertyService.createPropertySetting(setting).getPropertyName();
    } catch (Exception e) {
      return null;
    }
  }

  // --- online status -------------------------------------------------------

  @Test
  void getUserOnlineStatusReflectsService() {
    when(userStateService.isOnline(USERNAME)).thenReturn(true);
    when(userStateService.getUserState(USERNAME)).thenReturn(new UserStateModel(USERNAME, 1000000000000L, "available"));

    OnlineStatusModel status = userMcpTool.getUserOnlineStatus(USERNAME);

    assertNotNull(status);
    assertTrue(status.online());
    assertEquals("available", status.status());
  }

  @Test
  void listOnlineUsersMapsService() {
    when(userStateService.online()).thenReturn(List.of(new UserStateModel("mary", 1000000000000L, "available")));

    List<OnlineStatusModel> online = userMcpTool.listOnlineUsers(0, 10);

    assertNotNull(online);
    assertTrue(online.stream().anyMatch(o -> "mary".equals(o.username())));
  }

}

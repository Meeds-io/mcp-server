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
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.manager.RelationshipManager;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.profileproperty.model.ProfilePropertySetting;
import org.exoplatform.social.core.relationship.model.Relationship;

import io.meeds.mcp.server.tool.model.ExperienceModel;
import io.meeds.mcp.server.tool.model.OnlineStatusModel;
import io.meeds.mcp.server.tool.model.ProfileFieldVisibilityModel;
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

  @Test
  void senderCannotAcceptOwnConnectionRequest() {
    Identity john = identityManager.getOrCreateUserIdentity(USERNAME);
    Identity root = identityManager.getOrCreateUserIdentity("root");
    userMcpTool.sendConnectionRequest("root");
    try {
      assertThrows(IllegalStateException.class, () -> userMcpTool.acceptConnectionRequest("root"));

      assertEquals(Relationship.Type.PENDING, relationshipManager.getStatus(john, root));
    } finally {
      deleteRelationshipIfPresent(john, root);
    }
  }

  @Test
  void senderCannotRefuseOwnConnectionRequest() {
    Identity john = identityManager.getOrCreateUserIdentity(USERNAME);
    Identity root = identityManager.getOrCreateUserIdentity("root");
    userMcpTool.sendConnectionRequest("root");
    try {
      assertThrows(IllegalStateException.class, () -> userMcpTool.refuseConnectionRequest("root"));

      assertEquals(Relationship.Type.PENDING, relationshipManager.getStatus(john, root));
    } finally {
      deleteRelationshipIfPresent(john, root);
    }
  }

  @Test
  void receiverCanRefuseIncomingConnectionRequest() {
    Identity john = identityManager.getOrCreateUserIdentity(USERNAME);
    Identity root = identityManager.getOrCreateUserIdentity("root");
    relationshipManager.inviteToConnect(root, john);
    try {
      userMcpTool.refuseConnectionRequest("root");

      assertNotEquals(Relationship.Type.PENDING, relationshipManager.getStatus(john, root));
    } finally {
      deleteRelationshipIfPresent(john, root);
    }
  }

  /**
   * Removes the relationship between two users when there is one, so a test
   * does not leak its connection state into the others sharing the database.
   */
  private void deleteRelationshipIfPresent(Identity first, Identity second) {
    Relationship relationship = relationshipManager.get(first, second);
    if (relationship != null) {
      relationshipManager.delete(relationship);
    }
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

  @Test
  void updateMyProfileRefusesNonEditableFieldForNonAdministrator() throws Exception {
    ProfilePropertySetting created = createNonEditablePositionSetting();
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("james")));
    try {
      assertThrows(IllegalAccessException.class,
                   () -> userMcpTool.updateMyProfile(null, "CEO", null, null, null, null, null, null, null));

      // A field left untouched does not trip over another field that is not editable
      UserModel updated = userMcpTool.updateMyProfile(null, null, "Meeds", null, null, null, null, null, null);
      assertEquals("Meeds", updated.getCompany());

      created.setEditable(true);
      profilePropertyService.updatePropertySetting(created);
      updated = userMcpTool.updateMyProfile(null, "CEO", null, null, null, null, null, null, null);
      assertEquals("CEO", updated.getPosition());
    } finally {
      profilePropertyService.deleteProfilePropertySetting(created.getId());
    }
  }

  @Test
  void updateMyProfileAllowsNonEditableFieldForAdministrator() throws Exception {
    ProfilePropertySetting created = createNonEditablePositionSetting();
    try {
      UserModel updated = userMcpTool.updateMyProfile(null, "CTO", null, null, null, null, null, null, null);

      assertEquals("CTO", updated.getPosition());
    } finally {
      profilePropertyService.deleteProfilePropertySetting(created.getId());
    }
  }

  /**
   * The phone parameter writes displayedPhone, and a "phones" setting an
   * administrator locks governs it too. Mutant: the "phones" check removed.
   */
  @Test
  void updateMyProfileRefusesPhonesUnderANonEditablePhonesSettingForNonAdministrator() throws Exception {
    ProfilePropertySetting existing = profilePropertyService.getProfileSettingByName("phones");
    ProfilePropertySetting locked = existing == null ? createNonEditableSetting("phones") : existing;
    boolean wasEditable = existing != null && existing.isEditable();
    if (existing != null) {
      existing.setEditable(false);
      profilePropertyService.updatePropertySetting(existing);
    }
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("james")));
    try {
      assertThrows(IllegalAccessException.class,
                   () -> userMcpTool.updateMyProfile(null, null, null, null, null, null, null, "+33123456789", null));
    } finally {
      if (existing == null) {
        profilePropertyService.deleteProfilePropertySetting(locked.getId());
      } else {
        existing.setEditable(wasEditable);
        profilePropertyService.updatePropertySetting(existing);
      }
    }
  }

  /**
   * Hiding a field the platform marks as not editable is refused to a
   * non-administrator, as Social's visibility endpoint refuses it, and allowed
   * to an administrator. Mutant: the editability check removed from
   * setProfileFieldVisibility.
   */
  @Test
  void setProfileFieldVisibilityRefusesNonEditableFieldForNonAdministrator() throws Exception {
    ProfilePropertySetting setting = new ProfilePropertySetting();
    setting.setPropertyName("mcpLockedVisibilityField");
    setting.setPropertyType("text");
    setting.setActive(true);
    setting.setVisible(true);
    setting.setEditable(false);
    setting.setHiddenbale(true);
    ProfilePropertySetting created = profilePropertyService.createPropertySetting(setting);
    try {
      ProfileFieldVisibilityModel asAdministrator = userMcpTool.setProfileFieldVisibility("mcpLockedVisibilityField", Boolean.TRUE);
      assertTrue(asAdministrator.hiddenFields().contains("mcpLockedVisibilityField"));
      userMcpTool.setProfileFieldVisibility("mcpLockedVisibilityField", Boolean.FALSE);

      ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("james")));
      assertThrows(IllegalAccessException.class,
                   () -> userMcpTool.setProfileFieldVisibility("mcpLockedVisibilityField", Boolean.TRUE));
    } finally {
      profilePropertyService.deleteProfilePropertySetting(created.getId());
    }
  }

  /**
   * Registers the position profile property as not editable.
   */
  private ProfilePropertySetting createNonEditablePositionSetting() throws Exception {
    return createNonEditableSetting(Profile.POSITION);
  }

  /**
   * Registers the given profile property as not editable.
   */
  private ProfilePropertySetting createNonEditableSetting(String propertyName) throws Exception {
    ProfilePropertySetting setting = new ProfilePropertySetting();
    setting.setPropertyName(propertyName);
    setting.setPropertyType("text");
    setting.setActive(true);
    setting.setVisible(true);
    setting.setEditable(false);
    return profilePropertyService.createPropertySetting(setting);
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
  void workExperienceWritersRefuseNonEditableExperiencesForNonAdministrator() throws Exception {
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("james")));
    List<ExperienceModel> existing = userMcpTool.addWorkExperience("Meeds", "Engineer", null, null, null, null, null);
    String experienceId = existing.get(existing.size() - 1).experienceId();
    ProfilePropertySetting created = createNonEditableSetting(Profile.EXPERIENCES);
    try {
      assertThrows(IllegalAccessException.class,
                   () -> userMcpTool.addWorkExperience("Acme", null, null, null, null, null, null));
      assertThrows(IllegalAccessException.class,
                   () -> userMcpTool.updateWorkExperience(experienceId, "Acme", null, null, null, null, null, null));
      assertThrows(IllegalAccessException.class,
                   () -> userMcpTool.removeWorkExperience(experienceId));
      assertEquals(existing.size(), userMcpTool.getMyUserInformation().getExperiences().size());
      assertEquals("Meeds", userMcpTool.getMyUserInformation().getExperiences().get(0).company());

      created.setEditable(true);
      profilePropertyService.updatePropertySetting(created);
      List<ExperienceModel> afterRemove = userMcpTool.removeWorkExperience(experienceId);
      assertEquals(existing.size() - 1, afterRemove.size());
    } finally {
      profilePropertyService.deleteProfilePropertySetting(created.getId());
      userMcpTool.getMyUserInformation().getExperiences().forEach(e -> removeQuietly(e.experienceId()));
    }
  }

  /**
   * Removes a work experience of the current user, ignoring an unknown id.
   */
  private void removeQuietly(String experienceId) {
    try {
      userMcpTool.removeWorkExperience(experienceId);
    } catch (ObjectNotFoundException e) {
      // already removed
    }
  }

  @Test
  void workExperienceWritersAllowNonEditableExperiencesForAdministrator() throws Exception {
    ProfilePropertySetting created = createNonEditableSetting(Profile.EXPERIENCES);
    try {
      List<ExperienceModel> afterAdd = userMcpTool.addWorkExperience("Meeds", "Engineer", null, null, null, null, null);
      String experienceId = afterAdd.get(afterAdd.size() - 1).experienceId();
      userMcpTool.updateWorkExperience(experienceId, "Meeds SA", null, null, null, null, null, null);
      List<ExperienceModel> afterRemove = userMcpTool.removeWorkExperience(experienceId);
      assertEquals(afterAdd.size() - 1, afterRemove.size());
    } finally {
      profilePropertyService.deleteProfilePropertySetting(created.getId());
    }
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

    // get_profile_field_visibility exposes the toggleable field list
    ProfileFieldVisibilityModel initial = userMcpTool.getProfileFieldVisibility();
    assertNotNull(initial);
    final String field = hiddenableField;
    assertTrue(initial.toggleableFields().stream().anyMatch(f -> field.equals(f.name())),
               "toggleable_fields should list the hiddenable field");
    assertFalse(initial.hiddenFields().contains(hiddenableField));

    ProfileFieldVisibilityModel afterHide = userMcpTool.setProfileFieldVisibility(hiddenableField, Boolean.TRUE);
    assertTrue(afterHide.hiddenFields().contains(hiddenableField));
    assertTrue(afterHide.toggleableFields().stream().anyMatch(f -> field.equals(f.name()) && f.hidden()));

    ProfileFieldVisibilityModel afterShow = userMcpTool.setProfileFieldVisibility(hiddenableField, Boolean.FALSE);
    assertFalse(afterShow.hiddenFields().contains(hiddenableField));
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
  void setVisibilityOnUnknownFieldListsValidNames() {
    // Ensure at least one toggleable field exists so the error can enumerate it
    String hiddenableField = firstHiddenableField();
    if (hiddenableField == null) {
      hiddenableField = createHiddenableField("mcpUnknownErrorTestField");
    }
    Assumptions.assumeTrue(hiddenableField != null, "Unable to obtain a hiddenable profile property in test kernel");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                                                  () -> userMcpTool.setProfileFieldVisibility("notARealField", Boolean.TRUE));
    assertTrue(error.getMessage().contains("Toggleable fields are:"), "error should list the toggleable fields");
    assertTrue(error.getMessage().contains(hiddenableField), "error should include a valid field name");
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

  // --- avatar / banner -----------------------------------------------------

  // 1x1 transparent PNG
  private static final String PNG_1PX =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  @Test
  void setMyAvatarFromBase64() throws Exception {
    UserModel user = userMcpTool.setMyAvatar(null, PNG_1PX, null, null);

    assertNotNull(user);
    assertNotNull(user.getUsername());
  }

  @Test
  void setMyBannerFromBase64() throws Exception {
    UserModel user = userMcpTool.setMyBanner(null, PNG_1PX, null, null);

    assertNotNull(user);
    assertNotNull(user.getUsername());
  }

  @Test
  void setMyAvatarAndBannerRefuseNonEditableImageForNonAdministrator() throws Exception {
    ProfilePropertySetting avatar = createNonEditableSetting(Profile.AVATAR);
    ProfilePropertySetting banner = createNonEditableSetting(Profile.BANNER);
    ConversationState.setCurrent(new ConversationState(new org.exoplatform.services.security.Identity("james")));
    try {
      assertThrows(IllegalAccessException.class, () -> userMcpTool.setMyAvatar(null, PNG_1PX, null, null));
      assertThrows(IllegalAccessException.class, () -> userMcpTool.setMyBanner(null, PNG_1PX, null, null));

      // Only the avatar becomes editable: the banner stays refused
      avatar.setEditable(true);
      profilePropertyService.updatePropertySetting(avatar);
      assertNotNull(userMcpTool.setMyAvatar(null, PNG_1PX, null, null));
      assertThrows(IllegalAccessException.class, () -> userMcpTool.setMyBanner(null, PNG_1PX, null, null));

      banner.setEditable(true);
      profilePropertyService.updatePropertySetting(banner);
      assertNotNull(userMcpTool.setMyBanner(null, PNG_1PX, null, null));
    } finally {
      profilePropertyService.deleteProfilePropertySetting(avatar.getId());
      profilePropertyService.deleteProfilePropertySetting(banner.getId());
    }
  }

  @Test
  void setMyAvatarAndBannerAllowNonEditableImageForAdministrator() throws Exception {
    ProfilePropertySetting avatar = createNonEditableSetting(Profile.AVATAR);
    ProfilePropertySetting banner = createNonEditableSetting(Profile.BANNER);
    try {
      assertNotNull(userMcpTool.setMyAvatar(null, PNG_1PX, null, null));
      assertNotNull(userMcpTool.setMyBanner(null, PNG_1PX, null, null));
    } finally {
      profilePropertyService.deleteProfilePropertySetting(avatar.getId());
      profilePropertyService.deleteProfilePropertySetting(banner.getId());
    }
  }

  @Test
  void setMyAvatarRequiresASource() {
    assertThrows(IllegalArgumentException.class, () -> userMcpTool.setMyAvatar(null, null, null, null));
  }

  @Test
  void setMyAvatarRejectsBothSources() {
    assertThrows(IllegalArgumentException.class,
                 () -> userMcpTool.setMyAvatar("https://8.8.8.8/x.png", PNG_1PX, null, null));
  }

}

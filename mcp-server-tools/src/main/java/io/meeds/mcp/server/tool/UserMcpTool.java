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

import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;
import static io.meeds.mcp.server.tool.util.UserToolUtils.buildExperiences;
import static io.meeds.mcp.server.tool.util.UserToolUtils.toUserModel;
import static io.meeds.mcp.server.util.McpToolUtils.formatDate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.services.user.UserStateModel;
import org.exoplatform.services.user.UserStateService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.manager.RelationshipManager;
import org.exoplatform.social.core.profile.ProfileFilter;
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.profileproperty.model.ProfilePropertySetting;
import org.exoplatform.social.core.relationship.model.Relationship;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.ExperienceModel;
import io.meeds.mcp.server.tool.model.OnlineStatusModel;
import io.meeds.mcp.server.tool.model.ProfileFieldVisibilityModel;
import io.meeds.mcp.server.tool.model.ProfileFieldVisibilityModel.ToggleableField;
import io.meeds.mcp.server.tool.model.UserModel;
import io.meeds.social.translation.service.TranslationService;

import lombok.SneakyThrows;

@Service
public class UserMcpTool implements McpToolPlugin {

  private IdentityManager         identityManager;

  private ProfilePropertyService  profilePropertyService;

  private UserACL                 userAcl;

  private TranslationService      translationService;

  private UserPortalConfigService portalConfigService;

  private RelationshipManager     relationshipManager;

  private UserStateService        userStateService;

  public UserMcpTool(IdentityManager identityManager,
                     ProfilePropertyService profilePropertyService,
                     UserACL userAcl,
                     TranslationService translationService,
                     UserPortalConfigService portalConfigService,
                     RelationshipManager relationshipManager,
                     UserStateService userStateService) {
    this.identityManager = identityManager;
    this.profilePropertyService = profilePropertyService;
    this.userAcl = userAcl;
    this.translationService = translationService;
    this.portalConfigService = portalConfigService;
    this.relationshipManager = relationshipManager;
    this.userStateService = userStateService;
  }

  public UserModel getMyUserInformation() {
    String username = getCurrentUserName();
    return user(username);
  }

  public UserModel getUserByUsername(String username) {
    return user(username);
  }

  @SneakyThrows
  public List<UserModel> searchUsers(String query,
                                     Integer offset,
                                     Integer limit) {
    String username = getCurrentUserName();

    ProfileFilter filter = new ProfileFilter();
    filter.setName(StringUtils.isBlank(query) ? "" : query);
    filter.setSearchUserName(true);
    filter.setSearchEmail(false);
    filter.setEnabled(true);
    filter.setViewerIdentity(identityManager.getOrCreateUserIdentity(username));
    ListAccess<Identity> identityListAccess = identityManager.getIdentitiesByProfileFilter(OrganizationIdentityProvider.NAME,
                                                                                           filter,
                                                                                           true);
    Identity[] identities = identityListAccess.load(getInteger(offset, DEFAULT_OFFSET),
                                                    getInteger(limit, DEFAULT_LIMIT));
    return toUserModels(identities);
  }

  @SuppressWarnings("deprecation")
  public long getUsersCount() {
    String username = getCurrentUserName();
    if (userAcl.getUserIdentity(username).isMemberOf("/platform/users")) {
      return identityManager.getIdentitiesCount(OrganizationIdentityProvider.NAME);
    } else {
      throw new IllegalStateException("Guest users can't access this information");
    }
  }

  // ---------------------------------------------------------------------------
  // Connections / relationships (acting as the current user)
  // ---------------------------------------------------------------------------

  @SneakyThrows
  public List<UserModel> listConnectionRequests(Integer offset, Integer limit) {
    ListAccess<Identity> listAccess = relationshipManager.getIncomingWithListAccess(me());
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  @SneakyThrows
  public List<UserModel> listSentConnectionRequests(Integer offset, Integer limit) {
    ListAccess<Identity> listAccess = relationshipManager.getOutgoing(me());
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  @SneakyThrows
  public List<UserModel> listMyConnections(String query, Integer offset, Integer limit) {
    Identity currentIdentity = me();
    ListAccess<Identity> listAccess;
    if (StringUtils.isBlank(query)) {
      listAccess = relationshipManager.getConnections(currentIdentity);
    } else {
      ProfileFilter filter = new ProfileFilter();
      filter.setName(query);
      filter.setViewerIdentity(currentIdentity);
      listAccess = relationshipManager.getConnectionsByFilter(currentIdentity, filter);
    }
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  @SneakyThrows
  public List<UserModel> listUserConnections(String username, Integer offset, Integer limit) {
    ListAccess<Identity> listAccess = relationshipManager.getConnections(requireIdentity(username));
    return toUserModels(listAccess.load(getInteger(offset, DEFAULT_OFFSET), getInteger(limit, DEFAULT_LIMIT)));
  }

  public String getConnectionStatus(String username) {
    Relationship.Type type = relationshipManager.getStatus(me(), requireIdentity(username));
    return type == null ? "NOT_CONNECTED" : type.name();
  }

  public List<UserModel> listConnectionSuggestions(Integer limit) {
    Map<Identity, Integer> suggestions = relationshipManager.getSuggestions(me(), 0, 0, getInteger(limit, DEFAULT_LIMIT));
    return suggestions.keySet()
                      .stream()
                      .map(Identity::getRemoteId)
                      .map(this::user)
                      .toList();
  }

  @SneakyThrows
  public void sendConnectionRequest(String username) {
    Identity target = requireIdentity(username);
    Identity currentIdentity = me();
    if (StringUtils.equals(target.getRemoteId(), currentIdentity.getRemoteId())) {
      throw new IllegalArgumentException("You can't send a connection request to yourself.");
    }
    Relationship.Type status = relationshipManager.getStatus(currentIdentity, target);
    if (status == Relationship.Type.CONFIRMED) {
      throw new IllegalStateException("You are already connected with '%s'.".formatted(username));
    } else if (status == Relationship.Type.PENDING) {
      throw new IllegalStateException("A connection request with '%s' is already pending.".formatted(username));
    }
    relationshipManager.inviteToConnect(currentIdentity, target);
  }

  @SneakyThrows
  public void acceptConnectionRequest(String username) {
    Identity sender = requireIdentity(username);
    Identity currentIdentity = me();
    Relationship relationship = relationshipManager.get(sender, currentIdentity);
    if (relationship == null || relationship.getStatus() != Relationship.Type.PENDING) {
      throw new IllegalStateException("There is no pending connection request from '%s'. Use list_connection_requests to see incoming requests.".formatted(username));
    }
    relationshipManager.confirm(currentIdentity, sender);
  }

  @SneakyThrows
  public void refuseConnectionRequest(String username) {
    Identity sender = requireIdentity(username);
    Identity currentIdentity = me();
    Relationship relationship = relationshipManager.get(sender, currentIdentity);
    if (relationship == null || relationship.getStatus() != Relationship.Type.PENDING) {
      throw new IllegalStateException("There is no pending connection request from '%s'.".formatted(username));
    }
    relationshipManager.deny(currentIdentity, sender);
  }

  @SneakyThrows
  public void removeConnection(String username) {
    Identity other = requireIdentity(username);
    Relationship relationship = relationshipManager.get(me(), other);
    if (relationship == null) {
      throw new IllegalStateException("You have no relationship with '%s' to remove.".formatted(username));
    }
    relationshipManager.delete(relationship);
  }

  // ---------------------------------------------------------------------------
  // Profile editing (current user)
  // ---------------------------------------------------------------------------

  public UserModel updateMyProfile(String aboutMe, // NOSONAR
                                   String position,
                                   String company,
                                   String department,
                                   String team,
                                   String city,
                                   String country,
                                   String phones,
                                   String urls) {
    String username = getCurrentUserName();
    Identity identity = identityManager.getOrCreateUserIdentity(username);
    Profile profile = identityManager.getProfile(identity);
    boolean changed = setIfPresent(profile, Profile.ABOUT_ME, aboutMe);
    changed = setIfPresent(profile, Profile.POSITION, position) || changed;
    changed = setIfPresent(profile, Profile.COMPANY, company) || changed;
    changed = setIfPresent(profile, Profile.DEPARTMENT, department) || changed;
    changed = setIfPresent(profile, Profile.TEAM, team) || changed;
    changed = setIfPresent(profile, Profile.CITY, city) || changed;
    changed = setIfPresent(profile, Profile.COUNTRY, country) || changed;
    changed = setIfPresent(profile, Profile.DISPLAYED_PHONE, phones) || changed;
    changed = setUrlsIfPresent(profile, urls) || changed;
    if (!changed) {
      throw new IllegalArgumentException("No profile field provided to update. Provide at least one of: about_me, position, company, department, team, city, country, phones, urls.");
    }
    identityManager.updateProfile(profile, username, true);
    return user(username);
  }

  // ---------------------------------------------------------------------------
  // Work experience (current user)
  // ---------------------------------------------------------------------------

  public List<ExperienceModel> addWorkExperience(String company, // NOSONAR
                                                 String position,
                                                 String skills,
                                                 String startDate,
                                                 String endDate,
                                                 Boolean isCurrent,
                                                 String description) {
    if (StringUtils.isBlank(company)) {
      throw new IllegalArgumentException("'company' is mandatory to add a work experience.");
    }
    String username = getCurrentUserName();
    Profile profile = identityManager.getProfile(identityManager.getOrCreateUserIdentity(username));
    List<Map<String, Object>> experiences = mutableExperiences(profile);

    // No id is set: the storage layer assigns a fresh DB sequence id
    // (SEQ_SOC_EXPERIENCE_ID) to the new experience.
    Map<String, Object> experience = new HashMap<>();
    experience.put(Profile.EXPERIENCES_COMPANY, company);
    applyExperienceFields(experience, position, skills, startDate, endDate, isCurrent, description);
    experiences.add(experience);

    return saveExperiences(username, profile, experiences);
  }

  public List<ExperienceModel> updateWorkExperience(String experienceId, // NOSONAR
                                                    String company,
                                                    String position,
                                                    String skills,
                                                    String startDate,
                                                    String endDate,
                                                    Boolean isCurrent,
                                                    String description) throws ObjectNotFoundException {
    if (StringUtils.isBlank(experienceId)) {
      throw new IllegalArgumentException("'experience_id' is mandatory. Call get_my_user_information first to get the experience_id.");
    }
    String username = getCurrentUserName();
    Profile profile = identityManager.getProfile(identityManager.getOrCreateUserIdentity(username));
    List<Map<String, Object>> experiences = mutableExperiences(profile);
    Map<String, Object> experience = findExperience(experiences, experienceId);

    // Patch only the supplied fields, leaving omitted ones untouched.
    if (company != null) {
      experience.put(Profile.EXPERIENCES_COMPANY, company);
    }
    applyExperienceFields(experience, position, skills, startDate, endDate, isCurrent, description);

    return saveExperiences(username, profile, experiences);
  }

  public List<ExperienceModel> removeWorkExperience(String experienceId) throws ObjectNotFoundException {
    if (StringUtils.isBlank(experienceId)) {
      throw new IllegalArgumentException("'experience_id' is mandatory. Call get_my_user_information first to get the experience_id.");
    }
    String username = getCurrentUserName();
    Profile profile = identityManager.getProfile(identityManager.getOrCreateUserIdentity(username));
    List<Map<String, Object>> experiences = mutableExperiences(profile);
    // findExperience throws ObjectNotFoundException if the id is unknown
    Map<String, Object> experience = findExperience(experiences, experienceId);
    experiences.remove(experience);

    return saveExperiences(username, profile, experiences);
  }

  // ---------------------------------------------------------------------------
  // Profile field visibility (current user)
  // ---------------------------------------------------------------------------

  public ProfileFieldVisibilityModel setProfileFieldVisibility(String fieldName, Boolean hidden) {
    if (StringUtils.isBlank(fieldName)) {
      throw new IllegalArgumentException("'field_name' is mandatory.");
    }
    List<ProfilePropertySetting> toggleable = toggleableSettings();
    ProfilePropertySetting setting = toggleable.stream()
                                               .filter(s -> fieldName.equals(s.getPropertyName()))
                                               .findFirst()
                                               .orElse(null);
    if (setting == null) {
      // Distinguish a field that exists but can't be hidden from a plain unknown name.
      ProfilePropertySetting existing = profilePropertyService.getProfileSettingByName(fieldName);
      if (existing != null) {
        throw new IllegalArgumentException("The '%s' field can't be hidden.".formatted(fieldName));
      }
      String validNames = toggleable.stream().map(ProfilePropertySetting::getPropertyName).collect(Collectors.joining(", "));
      throw new IllegalArgumentException("Unknown field '%s'. Toggleable fields are: %s. Call get_profile_field_visibility to see them.".formatted(fieldName,
                                                                                                                                                 validNames));
    }
    long myUserIdentityId = Long.parseLong(me().getId());
    if (Boolean.TRUE.equals(hidden)) {
      profilePropertyService.hidePropertySetting(myUserIdentityId, setting.getId());
    } else {
      profilePropertyService.showPropertySetting(myUserIdentityId, setting.getId());
    }
    return getProfileFieldVisibility();
  }

  public ProfileFieldVisibilityModel getProfileFieldVisibility() {
    long myUserIdentityId = Long.parseLong(me().getId());
    List<Long> hiddenIds = profilePropertyService.getHiddenProfilePropertyIds(myUserIdentityId);
    List<ToggleableField> toggleableFields = toggleableSettings().stream()
                                                                 .map(s -> new ToggleableField(s.getPropertyName(),
                                                                                               hiddenIds.contains(s.getId())))
                                                                 .toList();
    List<String> hiddenFields = toggleableFields.stream()
                                                .filter(ToggleableField::hidden)
                                                .map(ToggleableField::name)
                                                .toList();
    return new ProfileFieldVisibilityModel(hiddenFields, toggleableFields);
  }

  /**
   * All profile property settings whose visibility the user can toggle: hiddenable
   * ({@code isPropertySettingHiddenable} already excludes the un-hiddenable list,
   * child properties and non-hiddenable flags) and not in the explicit
   * un-hiddenable list.
   */
  private List<ProfilePropertySetting> toggleableSettings() {
    List<String> unhiddenable = profilePropertyService.getUnhiddenableProfileProperties();
    return profilePropertyService.getPropertySettings()
                                 .stream()
                                 .filter(s -> profilePropertyService.isPropertySettingHiddenable(s.getId())
                                              && !unhiddenable.contains(s.getPropertyName()))
                                 .toList();
  }

  // ---------------------------------------------------------------------------
  // Online presence
  // ---------------------------------------------------------------------------

  @SuppressWarnings("deprecation") // UserStateModel.getLastActivity has no non-deprecated replacement yet
  public OnlineStatusModel getUserOnlineStatus(String username) {
    Identity identity = requireIdentity(username);
    String remoteId = identity.getRemoteId();
    boolean online = userStateService.isOnline(remoteId);
    UserStateModel state = userStateService.getUserState(remoteId);
    return new OnlineStatusModel(remoteId,
                                 online,
                                 state == null ? null : state.getStatus(),
                                 state == null || state.getLastActivity() <= 0 ? null : formatDate(state.getLastActivity()));
  }

  @SuppressWarnings("deprecation") // UserStateModel.getLastActivity has no non-deprecated replacement yet
  public List<OnlineStatusModel> listOnlineUsers(Integer offset, Integer limit) {
    List<UserStateModel> online = userStateService.online();
    if (online == null) {
      return Collections.emptyList();
    }
    return online.stream()
                 .skip(getInteger(offset, DEFAULT_OFFSET))
                 .limit(getInteger(limit, DEFAULT_LIMIT))
                 .map(state -> new OnlineStatusModel(state.getUserId(),
                                                     true,
                                                     state.getStatus(),
                                                     state.getLastActivity() <= 0 ? null : formatDate(state.getLastActivity())))
                 .toList();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Identity me() {
    return identityManager.getOrCreateUserIdentity(getCurrentUserName());
  }

  private Identity requireIdentity(String username) {
    if (StringUtils.isBlank(username)) {
      throw new IllegalArgumentException("'username' is mandatory");
    }
    String remoteId = username.startsWith("@") ? username.substring(1) : username;
    Identity identity = identityManager.getOrCreateUserIdentity(remoteId);
    if (identity == null) {
      throw new IllegalArgumentException("User '%s' doesn't exist. Use search_users to find a valid username.".formatted(username));
    }
    return identity;
  }

  private UserModel user(String username) {
    String viewer = getCurrentUserName();
    return toUserModel(identityManager,
                       profilePropertyService,
                       userAcl,
                       translationService,
                       portalConfigService,
                       username,
                       viewer,
                       getCurrentUserLocale(viewer),
                       false);
  }

  private List<UserModel> toUserModels(Identity[] identities) {
    if (ArrayUtils.isEmpty(identities)) {
      return Collections.emptyList();
    }
    return Stream.of(identities)
                 .map(Identity::getRemoteId)
                 .map(this::user)
                 .toList();
  }

  private boolean setIfPresent(Profile profile, String propertyName, String value) {
    if (value == null) {
      return false;
    }
    profile.setProperty(propertyName, value);
    return true;
  }

  /**
   * {@code Profile.CONTACT_URLS} is stored as a {@code List<Map<String,String>>}
   * where each entry maps the url to itself (key == value == url), matching how
   * the social REST layer and storage persist urls. Accepts a comma-separated
   * list of urls; an empty (but non-null) value clears the urls.
   */
  private boolean setUrlsIfPresent(Profile profile, String urls) {
    if (urls == null) {
      return false;
    }
    List<Map<String, String>> urlList = new ArrayList<>();
    for (String url : urls.split(",")) {
      String trimmed = url.trim();
      if (!trimmed.isEmpty()) {
        Map<String, String> urlMap = new HashMap<>();
        urlMap.put(trimmed, trimmed);
        urlList.add(urlMap);
      }
    }
    profile.setProperty(Profile.CONTACT_URLS, urlList);
    return true;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> mutableExperiences(Profile profile) {
    List<Map<String, Object>> experiences = new ArrayList<>();
    Object raw = profile.getProperty(Profile.EXPERIENCES);
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          experiences.add(new HashMap<>((Map<String, Object>) map));
        }
      }
    }
    return experiences;
  }

  private Map<String, Object> findExperience(List<Map<String, Object>> experiences,
                                             String experienceId) throws ObjectNotFoundException {
    return experiences.stream()
                      .filter(e -> experienceId.equals(String.valueOf(e.get(Profile.EXPERIENCES_ID))))
                      .findFirst()
                      .orElseThrow(() -> new ObjectNotFoundException("No work experience found with experience_id '%s'. Call get_my_user_information to list your experiences and their experience_id.".formatted(experienceId)));
  }

  private void applyExperienceFields(Map<String, Object> experience, // NOSONAR
                                     String position,
                                     String skills,
                                     String startDate,
                                     String endDate,
                                     Boolean isCurrent,
                                     String description) {
    if (position != null) {
      experience.put(Profile.EXPERIENCES_POSITION, position);
    }
    if (skills != null) {
      experience.put(Profile.EXPERIENCES_SKILLS, skills);
    }
    if (description != null) {
      experience.put(Profile.EXPERIENCES_DESCRIPTION, description);
    }
    if (startDate != null) {
      experience.put(Profile.EXPERIENCES_START_DATE, normalizeExperienceDate(startDate, "start_date"));
    }
    if (endDate != null) {
      experience.put(Profile.EXPERIENCES_END_DATE, normalizeExperienceDate(endDate, "end_date"));
    }
    if (Boolean.TRUE.equals(isCurrent)) {
      // The platform derives "is current" from the absence of an end date, so
      // an ongoing experience must not carry one.
      experience.remove(Profile.EXPERIENCES_END_DATE);
      experience.put(Profile.EXPERIENCES_IS_CURRENT, Boolean.TRUE);
    } else if (Boolean.FALSE.equals(isCurrent)) {
      experience.put(Profile.EXPERIENCES_IS_CURRENT, Boolean.FALSE);
    }
  }

  /**
   * Work-experience dates are persisted in a {@code VARCHAR(10)} column, i.e. as
   * an ISO local date ({@code yyyy-MM-dd}). We accept an ISO local date or a full
   * ISO date-time and normalize it to {@code yyyy-MM-dd}.
   */
  private String normalizeExperienceDate(String date, String fieldName) {
    if (StringUtils.isBlank(date)) {
      return null;
    }
    try {
      return LocalDate.parse(date.trim()).toString();
    } catch (DateTimeParseException e) {
      try {
        return OffsetDateTime.parse(date.trim()).toLocalDate().toString();
      } catch (DateTimeParseException e2) {
        throw new IllegalArgumentException("Invalid '%s' value '%s'. Provide the date in ISO format yyyy-MM-dd (e.g. 2024-01-15).".formatted(fieldName, date));
      }
    }
  }

  private List<ExperienceModel> saveExperiences(String username, Profile profile, List<Map<String, Object>> experiences) {
    profile.setProperty(Profile.EXPERIENCES, experiences);
    identityManager.updateProfile(profile, username, true);
    // Re-read to reflect storage-generated ids and derived isCurrent values.
    Profile updated = identityManager.getProfile(identityManager.getOrCreateUserIdentity(username));
    return buildExperiences(updated);
  }

}

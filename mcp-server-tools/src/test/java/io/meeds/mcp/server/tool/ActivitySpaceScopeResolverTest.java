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
package io.meeds.mcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.portal.config.UserACL;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.mcp.server.tool.plugin.ActivitySpaceScopeResolver;

/**
 * Pins which space an activity belongs to for the space scope decision.
 */
@ExtendWith(MockitoExtension.class)
class ActivitySpaceScopeResolverTest {

  @Mock
  private ActivityManager           activityManager;

  @Mock
  private IdentityManager           identityManager;

  @Mock
  private UserACL                   userAcl;

  @InjectMocks
  private ActivitySpaceScopeResolver resolver;

  /**
   * An activity of a space's stream, a shared one included, belongs to that
   * space. Mutant: the space id not read.
   */
  @Test
  void spaceActivityBelongsToItsSpace() {
    ExoSocialActivity activity = activity(false, "12");
    when(activityManager.getActivity("7")).thenReturn(activity);

    assertThat(resolver.getName()).isEqualTo("activity");
    assertThat(resolver.spaceOf("7")).isEqualTo(12L);
  }

  /**
   * A comment, an activity of a user's stream, a missing activity and a
   * failing lookup belong to no space. Mutants: the comment check removed;
   * the exception let through.
   */
  @Test
  void otherActivitiesBelongToNoSpace() {
    ExoSocialActivity comment = activity(true, "12");
    ExoSocialActivity userStream = activity(false, null);
    when(activityManager.getActivity("1")).thenReturn(comment);
    when(activityManager.getActivity("2")).thenReturn(userStream);
    when(activityManager.getActivity("3")).thenReturn(null);
    when(activityManager.getActivity("4")).thenThrow(new IllegalStateException("database down"));

    assertThat(resolver.spaceOf("1")).isNull();
    assertThat(resolver.spaceOf("2")).isNull();
    assertThat(resolver.spaceOf("3")).isNull();
    assertThat(resolver.spaceOf("4")).isNull();
  }

  /**
   * A person may have a worker change an activity only when social lets that
   * person change it, asked for that person's own ACL identity. Social
   * answers yes for any other activity and identity, so each "no" is the
   * resolver's own. Mutants: social's answer ignored, another person's ACL
   * identity asked, the comment, the deleted, the disabled, the no-ACL-identity
   * or the blank name guard removed, a failure read as yes. Without the
   * missing-activity or the unknown-person guard the null throws and the catch
   * answers no, so those two are pinned by the catch.
   */
  @Test
  void canEditIsSocialsEditRuleForThePerson() {
    org.exoplatform.services.security.Identity annaAcl = mock(org.exoplatform.services.security.Identity.class);
    org.exoplatform.services.security.Identity paulAcl = mock(org.exoplatform.services.security.Identity.class);
    ExoSocialActivity post = activity(false, "12");
    ExoSocialActivity comment = activity(true, "12");
    lenient().when(activityManager.isActivityEditable(any(), any())).thenReturn(true);
    when(activityManager.isActivityEditable(post, paulAcl)).thenReturn(false);
    lenient().when(activityManager.getActivity("7")).thenReturn(post);
    lenient().when(activityManager.getActivity("1")).thenReturn(comment);
    lenient().when(activityManager.getActivity("4")).thenThrow(new IllegalStateException("database down"));
    Identity active = person(false, true);
    Identity deleted = person(true, true);
    Identity disabled = person(false, false);
    lenient().when(identityManager.getOrCreateUserIdentity(any())).thenReturn(active);
    lenient().when(identityManager.getOrCreateUserIdentity("gone")).thenReturn(deleted);
    lenient().when(identityManager.getOrCreateUserIdentity("off")).thenReturn(disabled);
    lenient().when(identityManager.getOrCreateUserIdentity("ghost")).thenReturn(null);
    lenient().when(userAcl.getUserIdentity(any())).thenReturn(annaAcl);
    lenient().when(userAcl.getUserIdentity("paul")).thenReturn(paulAcl);
    lenient().when(userAcl.getUserIdentity("noacl")).thenReturn(null);

    assertThat(resolver.canEdit("7", "anna")).isTrue();
    assertThat(resolver.canEdit("7", "paul")).as("social says no").isFalse();
    assertThat(resolver.canEdit("1", "anna")).as("a comment").isFalse();
    assertThat(resolver.canEdit("3", "anna")).as("a missing activity").isFalse();
    assertThat(resolver.canEdit("4", "anna")).as("a failing lookup").isFalse();
    assertThat(resolver.canEdit("7", "gone")).as("a deleted person").isFalse();
    assertThat(resolver.canEdit("7", "off")).as("a disabled person").isFalse();
    assertThat(resolver.canEdit("7", "ghost")).as("an unknown person").isFalse();
    assertThat(resolver.canEdit("7", "noacl")).as("no ACL identity").isFalse();
    assertThat(resolver.canEdit("7", " ")).as("no person").isFalse();
  }

  /**
   * @param deleted whether the identity is deleted
   * @param enabled whether it is enabled
   * @return a mocked user identity
   */
  private static Identity person(boolean deleted, boolean enabled) {
    Identity identity = mock(Identity.class);
    lenient().when(identity.isDeleted()).thenReturn(deleted);
    lenient().when(identity.isEnable()).thenReturn(enabled);
    return identity;
  }

  /**
   * @param comment whether the activity is a comment
   * @param spaceId the id of the space whose stream holds it, null for none
   * @return a mocked activity
   */
  private static ExoSocialActivity activity(boolean comment, String spaceId) {
    ExoSocialActivity activity = mock(ExoSocialActivity.class);
    lenient().when(activity.isComment()).thenReturn(comment);
    lenient().when(activity.getSpaceId()).thenReturn(spaceId);
    return activity;
  }

}

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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code space_role} of a space scope declaration (EXO-91405): read from
 * the definition file, defaulting to a manager's for a write, a manager's
 * alone on a {@code space_admin} write, and malformed otherwise.
 */
class SpaceScopeDeclarationTest {

  /** The platform's wire contract: an absent boolean reads false. */
  private static final JsonMapper MAPPER = JsonMapper.builder()
                                                     .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                                                     .build();

  /**
   * Each role is read from {@code space_role}, and is the role a write needs;
   * a read needs none. Mutant: the property not mapped.
   */
  @Test
  void theDeclaredRoleIsReadAndIsTheWritesRole() {
    for (String role : SpaceScopeDeclaration.SPACE_ROLES) {
      SpaceScopeDeclaration scope = read("{\"space_arg\": \"space_id\", \"space_role\": \"" + role + "\"}");
      assertThat(scope.getSpaceRole()).isEqualTo(role);
      assertThat(scope.getEffectiveSpaceRole(true)).isEqualTo(role);
      assertThat(scope.getEffectiveSpaceRole(false)).isNull();
      assertThat(scope.isWellFormedFor(true)).as(role).isTrue();
    }
  }

  /**
   * A write that declares no role needs a manager's, and so does a
   * space_admin one. Mutants: the default dropped, space_admin not implying
   * the manager's role.
   */
  @Test
  void aWriteWithoutRoleOrAdministeringTheSpaceNeedsAManager() {
    assertThat(read("{\"space_arg\": \"space_id\"}").getEffectiveSpaceRole(true)).isEqualTo(SpaceScopeDeclaration.SPACE_ROLE_MANAGER);
    SpaceScopeDeclaration admin = read("{\"space_arg\": \"space_id\", \"space_admin\": true}");
    assertThat(admin.getEffectiveSpaceRole(true)).isEqualTo(SpaceScopeDeclaration.SPACE_ROLE_MANAGER);
    assertThat(admin.isWellFormedFor(true)).isTrue();
    SpaceScopeDeclaration adminManager = read("{\"space_arg\": \"space_id\", \"space_admin\": true, \"space_role\": \"manager\"}");
    assertThat(adminManager.isWellFormedFor(true)).isTrue();
    // even a malformed space_admin declaration claiming a lower role needs a manager
    assertThat(read("{\"space_arg\": \"space_id\", \"space_admin\": true, \"space_role\": \"member\"}").getEffectiveSpaceRole(true))
        .isEqualTo(SpaceScopeDeclaration.SPACE_ROLE_MANAGER);
  }

  /**
   * {@code editor} is read like the other roles and is the write's role, on a
   * declaration of the object kind only: on a space, an unscoped or a
   * space_admin declaration it is malformed, and space_admin still needs a
   * manager. Mutants: editor accepted on any kind, accepted with space_admin,
   * refused on an object declaration.
   */
  @Test
  void theEditorRoleIsForAnObjectOnly() {
    SpaceScopeDeclaration object = read("{\"object_arg\": \"event_id\", \"resolver\": \"event\", \"space_role\": \"editor\"}");
    assertThat(object.isWellFormedFor(true)).isTrue();
    assertThat(object.getEffectiveSpaceRole(true)).isEqualTo(SpaceScopeDeclaration.SPACE_ROLE_EDITOR);
    assertThat(object.getEffectiveSpaceRole(false)).isNull();
    assertThat(read("{\"space_arg\": \"space_id\", \"space_role\": \"editor\"}").isWellFormedFor(true)).isFalse();
    assertThat(read("{\"unscoped\": true, \"space_role\": \"editor\"}").isWellFormedFor(false)).isFalse();
    SpaceScopeDeclaration admin = read("{\"object_arg\": \"event_id\", \"resolver\": \"event\", \"space_admin\": true, \"space_role\": \"editor\"}");
    assertThat(admin.isWellFormedFor(true)).isFalse();
    assertThat(admin.getEffectiveSpaceRole(true)).isEqualTo(SpaceScopeDeclaration.SPACE_ROLE_MANAGER);
    assertThat(read("{\"object_arg\": \"event_id\", \"resolver\": \"event\", \"space_role\": \"Editor\"}").isWellFormedFor(true)).isFalse();
  }

  /**
   * An unknown role, another role than a manager's on a space_admin
   * declaration, or a role on an unscoped one, makes it malformed. Mutants:
   * each of the three checks removed.
   */
  @Test
  void aMisdeclaredRoleIsMalformed() {
    assertThat(read("{\"space_arg\": \"space_id\", \"space_role\": \"owner\"}").isWellFormedFor(true)).isFalse();
    assertThat(read("{\"space_arg\": \"space_id\", \"space_role\": \"Manager\"}").isWellFormedFor(true)).isFalse();
    assertThat(read("{\"space_arg\": \"space_id\", \"space_admin\": true, \"space_role\": \"member\"}").isWellFormedFor(true)).isFalse();
    assertThat(read("{\"unscoped\": true, \"space_role\": \"member\"}").isWellFormedFor(false)).isFalse();
    assertThat(read("{\"unscoped\": true}").isWellFormedFor(false)).isTrue();
    assertThat(read("{\"object_arg\": \"activity_id\", \"resolver\": \"activity\", \"space_role\": \"member\"}").isWellFormedFor(true)).isTrue();
  }

  /**
   * @param json a space scope declaration as a definition file writes it
   * @return the declaration
   */
  private static SpaceScopeDeclaration read(String json) {
    return MAPPER.readValue(json, SpaceScopeDeclaration.class);
  }

}

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

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How a tool may be called when the call is limited to one space (the
 * {@code space_scope} of an {@code ai-tool-definitions.json} entry). A tool
 * without one is refused while a call is limited. Exactly one of three kinds:
 * <ul>
 * <li>a space argument: {@code space_arg} names the argument holding the
 * space id, which must be the call's space; for a read, {@code when_absent}
 * says what an absent or null value means, {@value #USE_SCOPE} to read the
 * call's space, anything else to refuse;</li>
 * <li>an object by id: {@code object_arg} names the argument holding the id,
 * and {@code resolver} the {@code SpaceScopeResolver} that tells the
 * object's space, which must be the call's space;</li>
 * <li>a read of nothing that belongs to a space, such as the user directory:
 * {@code unscoped: true}.</li>
 * </ul>
 * A write (a tool not marked read-only) may declare the first two kinds
 * only, without {@code when_absent}: it names the place it changes, and an
 * absent space is refused, never filled in, since filling it in would change
 * the target the asker approved.
 * <p>
 * The call's arguments are then limited to {@code allowed_args}, the space or
 * object argument and {@code false_only_args}, each of the latter false or
 * absent.
 * <p>
 * {@code space_admin: true} marks a write that administers the space itself
 * (its settings, its members and their roles, its join requests, its
 * deletion) rather than adding content to it. The server enforces nothing
 * more for it; a caller that lets someone approve such a write reads the flag
 * to ask the approver for the right to manage the space.
 * <p>
 * Owned by the add-on that ships the tool: always read from its file, never
 * from the persisted copy an administrator edits.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public class SpaceScopeDeclaration {

  /** The {@code when_absent} value that reads the call's space. */
  public static final String USE_SCOPE = "use_scope";

  /** The {@code id_format} value of a decimal id, the default. */
  public static final String ID_FORMAT_NUMERIC = "numeric";

  /** The {@code id_format} value of an id of 32 lowercase hexadecimal digits. */
  public static final String ID_FORMAT_HEX32 = "hex32";

  @JsonProperty("space_arg")
  private String             spaceArg;

  @JsonProperty("when_absent")
  private String             whenAbsent;

  @JsonProperty("object_arg")
  private String             objectArg;

  @JsonProperty("resolver")
  private String             resolver;

  @JsonProperty("unscoped")
  private boolean            unscoped;

  @JsonProperty("allowed_args")
  private List<String>       allowedArgs;

  @JsonProperty("false_only_args")
  private List<String>       falseOnlyArgs;

  @JsonProperty("space_admin")
  private boolean            spaceAdmin;

  @JsonProperty("id_format")
  private String             idFormat;

  /**
   * @return true when the declaration names a space argument and nothing else
   */
  @JsonIgnore
  public boolean isSpaceKind() {
    return StringUtils.isNotBlank(spaceArg) && StringUtils.isBlank(objectArg) && StringUtils.isBlank(resolver) && !unscoped;
  }

  /**
   * @return true when the declaration names an object argument with its
   *         resolver, and nothing else
   */
  @JsonIgnore
  public boolean isObjectKind() {
    return StringUtils.isNotBlank(objectArg) && StringUtils.isNotBlank(resolver) && StringUtils.isBlank(spaceArg) && !unscoped;
  }

  /**
   * @return true when the declaration reaches nothing that belongs to a
   *         space, and names no space or object argument
   */
  @JsonIgnore
  public boolean isUnscopedKind() {
    return unscoped && StringUtils.isBlank(spaceArg) && StringUtils.isBlank(objectArg) && StringUtils.isBlank(resolver);
  }

  /**
   * Tells whether the declaration is well formed for its tool: exactly one
   * kind, and, for a write, one that names the place it changes (a space or
   * an object) with no {@code when_absent}.
   *
   * @param write true when the tool isn't marked read-only
   * @return true when a call limited to one space may run the tool, its
   *         arguments permitting
   */
  @JsonIgnore
  public boolean isWellFormedFor(boolean write) {
    int kinds = (isSpaceKind() ? 1 : 0) + (isObjectKind() ? 1 : 0) + (isUnscopedKind() ? 1 : 0);
    return kinds == 1
           && (!write || (!isUnscopedKind() && StringUtils.isBlank(whenAbsent)))
           && isIdFormatWellFormed();
  }

  /**
   * @return true when the object id is written as 32 lowercase hexadecimal
   *         digits; false for the default, a decimal number
   */
  @JsonIgnore
  public boolean isHexId() {
    return ID_FORMAT_HEX32.equals(idFormat);
  }

  /**
   * @return true when no {@code id_format} is given, or an object
   *         declaration gives a known one; an {@code id_format} on any other
   *         kind, or an unknown value, isn't well formed
   */
  private boolean isIdFormatWellFormed() {
    return idFormat == null
           || isObjectKind() && (ID_FORMAT_NUMERIC.equals(idFormat) || ID_FORMAT_HEX32.equals(idFormat));
  }

  /**
   * @return true when an absent space argument reads the call's space; a
   *         write declaring it is malformed
   */
  @JsonIgnore
  public boolean isUseScopeWhenAbsent() {
    return USE_SCOPE.equals(whenAbsent);
  }

}

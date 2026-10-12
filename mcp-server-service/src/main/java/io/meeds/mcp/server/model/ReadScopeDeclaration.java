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
 * How a read tool may be called when the call is limited to one space (the
 * {@code read_scope} of an {@code ai-tool-definitions.json} entry). A tool
 * without one is refused while a call is limited. Exactly one of three kinds:
 * <ul>
 * <li>a space read: {@code space_arg} names the argument holding the space
 * id, which must be the call's space; {@code when_absent} says what an absent
 * or null value means, {@value #USE_SCOPE} to read the call's space, anything
 * else to refuse;</li>
 * <li>a read by id: {@code object_arg} names the argument holding the id, and
 * {@code resolver} the {@code ReadScopeResolver} that tells the object's
 * space, which must be the call's space;</li>
 * <li>a read of nothing that belongs to a space, such as the user directory:
 * {@code unscoped: true}.</li>
 * </ul>
 * The call's arguments are then limited to {@code allowed_args}, the space or
 * object argument and {@code false_only_args}, each of the latter false or
 * absent.
 * <p>
 * Owned by the add-on that ships the tool: always read from its file, never
 * from the persisted copy an administrator edits.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public class ReadScopeDeclaration {

  /** The {@code when_absent} value that reads the call's space. */
  public static final String USE_SCOPE = "use_scope";

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

  /**
   * @return true when the declaration is a space read and nothing else
   */
  @JsonIgnore
  public boolean isSpaceRead() {
    return StringUtils.isNotBlank(spaceArg) && StringUtils.isBlank(objectArg) && StringUtils.isBlank(resolver) && !unscoped;
  }

  /**
   * @return true when the declaration is a read by id, with its resolver, and
   *         nothing else
   */
  @JsonIgnore
  public boolean isObjectRead() {
    return StringUtils.isNotBlank(objectArg) && StringUtils.isNotBlank(resolver) && StringUtils.isBlank(spaceArg) && !unscoped;
  }

  /**
   * @return true when the declaration reads nothing that belongs to a space,
   *         and names no space or object argument
   */
  @JsonIgnore
  public boolean isUnscopedRead() {
    return unscoped && StringUtils.isBlank(spaceArg) && StringUtils.isBlank(objectArg) && StringUtils.isBlank(resolver);
  }

  /**
   * @return true when an absent space argument reads the call's space
   */
  @JsonIgnore
  public boolean isUseScopeWhenAbsent() {
    return USE_SCOPE.equals(whenAbsent);
  }

}

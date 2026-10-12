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
package io.meeds.mcp.server.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.meeds.mcp.server.model.ActingIdentity;
import io.meeds.mcp.server.model.ReadScopeDeclaration;
import io.meeds.mcp.server.model.ReadScopeRefusedException;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.plugin.ReadScopeResolver;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps a tool call whose acting identity is limited to one space
 * ({@link ActingIdentity#readScope()}) within that space, from the tool's
 * {@code read_scope} declaration ({@link ReadScopeDeclaration}). A call that
 * isn't limited is never looked at.
 * <p>
 * A limited call is refused when the tool isn't a read (its
 * {@code readOnlyHint} isn't true), declares no read scope or a malformed
 * one, or when its arguments aren't a JSON object with distinct keys, all of
 * them accepted, its false-only arguments false or absent, and its space or
 * object in the call's space. An absent space argument is filled with the
 * call's space when the tool says so. Each refusal names the space the call
 * may read, so the model can call again within it.
 */
@Service
@Slf4j
public class McpToolReadScopeService {

  private static final String                      NOT_AVAILABLE      =
                                                                  "Not executed: '%s' isn't available while this run is limited to the space with id %s. Don't call it again in this run: use a tool that reads that space, or answer from what you already have.";

  private static final String                      BAD_ARGUMENTS      =
                                                                  "Not executed: while this run is limited to the space with id %s, '%s' needs its arguments as a JSON object with distinct keys. Call it again that way.";

  private static final String                      UNKNOWN_ARGUMENT   =
                                                                  "Not executed: while this run is limited to the space with id %s, '%s' accepts only the arguments %s, and '%s' isn't one of them. Call it again without it.";

  private static final String                      FALSE_ONLY         =
                                                                  "Not executed: while this run is limited to the space with id %s, '%s' can only be called with '%s' false or absent. Call it again without it.";

  private static final String                      OUT_OF_SPACE       =
                                                                  "Not executed: while this run is limited to the space with id %s, '%s' can only read that space, and the call gave %s. Call '%s' again with %s %s, or answer from what you already have.";

  private static final String                      OUT_OF_SPACE_OBJECT =
                                                                   "Not executed: while this run is limited to the space with id %s, '%s' can only open an item of that space, and %s %s isn't one of them. Find that space's items with a tool that lists them, or answer from what you already have.";

  private static final Pattern                     DECIMAL_ID         = Pattern.compile("[1-9]\\d{0,18}");

  private static final JsonMapper                  ARGUMENTS_MAPPER   = JsonMapper.builder()
                                                                                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                                                                  .build();

  private final ObjectProvider<ReadScopeResolver> readScopeResolvers;

  /**
   * @param readScopeResolvers the contributed resolvers, looked up at each
   *                             call
   */
  public McpToolReadScopeService(ObjectProvider<ReadScopeResolver> readScopeResolvers) {
    this.readScopeResolvers = readScopeResolvers;
  }

  /**
   * Checks a call against the read scope of its acting identity.
   *
   * @param toolName       the MCP tool name
   * @param toolDefinition the tool's definition, may be null
   * @param toolInput      the call's arguments as the model sent them
   * @param actingIdentity the call's acting identity, may be null
   * @return the arguments to run the tool with: the same string, untouched,
   *         when the call isn't limited or needs nothing filled, else the
   *         arguments with the call's space filled in
   * @throws ReadScopeRefusedException when the limited call reads, or may
   *                                     read, outside its space
   */
  public String enforce(String toolName, SimpleToolDefinition toolDefinition, String toolInput, ActingIdentity actingIdentity) {
    Long readScope = actingIdentity == null ? null : actingIdentity.readScope();
    if (readScope == null) {
      return toolInput;
    }
    ReadScopeDeclaration declaration = toolDefinition == null ? null : toolDefinition.getReadScope();
    if (!isReadTool(toolDefinition) || !isWellFormed(declaration)) {
      throw refuse(NOT_AVAILABLE.formatted(toolName, readScope));
    }
    ObjectNode arguments = parseArguments(toolName, toolInput, readScope);
    checkArgumentNames(toolName, declaration, arguments, readScope);
    checkFalseOnlyArguments(toolName, declaration, arguments, readScope);
    if (declaration.isSpaceRead()) {
      return checkSpace(toolName, declaration, toolInput, arguments, readScope);
    } else if (declaration.isObjectRead()) {
      checkObject(toolName, declaration, arguments, readScope);
    }
    return toolInput;
  }

  /**
   * @param toolDefinition the tool's definition, may be null
   * @return true when the definition says the tool only reads: a write never
   *         runs while a call is limited to a space, whatever it declares
   */
  private boolean isReadTool(SimpleToolDefinition toolDefinition) {
    return toolDefinition != null
           && toolDefinition.getAnnotations() != null
           && Boolean.TRUE.equals(toolDefinition.getAnnotations().readOnlyHint());
  }

  /**
   * @param declaration the tool's read scope, may be null
   * @return true when it is exactly one of the three kinds
   */
  private boolean isWellFormed(ReadScopeDeclaration declaration) {
    if (declaration == null) {
      return false;
    }
    int kinds = (declaration.isSpaceRead() ? 1 : 0) + (declaration.isObjectRead() ? 1 : 0) + (declaration.isUnscopedRead() ? 1 : 0);
    return kinds == 1;
  }

  /**
   * Reads the call's arguments as a JSON object, refusing a duplicated key: a
   * key read twice could be checked with one value and run with the other.
   *
   * @param toolName  the MCP tool name
   * @param toolInput the call's arguments, an empty object when blank
   * @param readScope the call's space
   * @return the arguments
   * @throws ReadScopeRefusedException when they aren't a JSON object with
   *                                     distinct keys
   */
  private ObjectNode parseArguments(String toolName, String toolInput, Long readScope) {
    if (StringUtils.isBlank(toolInput)) {
      return ARGUMENTS_MAPPER.createObjectNode();
    }
    try {
      JsonNode node = ARGUMENTS_MAPPER.readTree(toolInput);
      if (node instanceof ObjectNode objectNode) {
        return objectNode;
      }
    } catch (JacksonException e) {
      log.debug("Arguments of tool '{}' refused under read scope {}", toolName, readScope, e);
    }
    throw refuse(BAD_ARGUMENTS.formatted(readScope, toolName));
  }

  /**
   * Refuses any argument the declaration doesn't accept: one added to the tool
   * later, or another spelling of an accepted one, can't widen the read.
   *
   * @param toolName    the MCP tool name
   * @param declaration the tool's read scope
   * @param arguments   the call's arguments
   * @param readScope   the call's space
   * @throws ReadScopeRefusedException on the first argument not accepted
   */
  private void checkArgumentNames(String toolName, ReadScopeDeclaration declaration, ObjectNode arguments, Long readScope) {
    Set<String> accepted = acceptedArguments(declaration);
    for (Map.Entry<String, JsonNode> argument : arguments.properties()) {
      if (!accepted.contains(argument.getKey())) {
        throw refuse(UNKNOWN_ARGUMENT.formatted(readScope, toolName, accepted, argument.getKey()));
      }
    }
  }

  /**
   * @param declaration the tool's read scope
   * @return the arguments a limited call may carry: the allowed ones, the
   *         space or object argument and the false-only ones
   */
  private Set<String> acceptedArguments(ReadScopeDeclaration declaration) {
    Set<String> accepted = new LinkedHashSet<>();
    if (declaration.isSpaceRead()) {
      accepted.add(declaration.getSpaceArg());
    } else if (declaration.isObjectRead()) {
      accepted.add(declaration.getObjectArg());
    }
    addAll(accepted, declaration.getAllowedArgs());
    addAll(accepted, declaration.getFalseOnlyArgs());
    return accepted;
  }

  /**
   * @param target the set to fill
   * @param names  the names to add, may be null
   */
  private void addAll(Set<String> target, List<String> names) {
    if (CollectionUtils.isNotEmpty(names)) {
      names.stream().filter(StringUtils::isNotBlank).forEach(target::add);
    }
  }

  /**
   * Refuses a false-only argument holding anything but false or null: a
   * personal filter, such as the favorites, reads outside the space.
   *
   * @param toolName    the MCP tool name
   * @param declaration the tool's read scope
   * @param arguments   the call's arguments
   * @param readScope   the call's space
   * @throws ReadScopeRefusedException on the first such argument
   */
  private void checkFalseOnlyArguments(String toolName, ReadScopeDeclaration declaration, ObjectNode arguments, Long readScope) {
    if (CollectionUtils.isEmpty(declaration.getFalseOnlyArgs())) {
      return;
    }
    for (String name : declaration.getFalseOnlyArgs()) {
      JsonNode value = arguments.get(name);
      if (value != null && !value.isNull() && !(value.isBoolean() && !value.booleanValue())) {
        throw refuse(FALSE_ONLY.formatted(readScope, toolName, name));
      }
    }
  }

  /**
   * Checks the space argument of a space read, filling it with the call's
   * space when it is absent or null and the tool says so.
   *
   * @param toolName    the MCP tool name
   * @param declaration the tool's read scope
   * @param toolInput   the call's arguments as sent
   * @param arguments   the call's arguments
   * @param readScope   the call's space
   * @return the arguments as sent, or with the space filled in
   * @throws ReadScopeRefusedException when the space is another one, isn't a
   *                                     space id, or is absent and the tool
   *                                     says to refuse
   */
  private String checkSpace(String toolName,
                            ReadScopeDeclaration declaration,
                            String toolInput,
                            ObjectNode arguments,
                            Long readScope) {
    String spaceArg = declaration.getSpaceArg();
    JsonNode value = arguments.get(spaceArg);
    if (value == null || value.isNull()) {
      if (declaration.isUseScopeWhenAbsent()) {
        arguments.put(spaceArg, readScope.longValue());
        return ARGUMENTS_MAPPER.writeValueAsString(arguments);
      }
      throw refuse(OUT_OF_SPACE.formatted(readScope, toolName, "no " + spaceArg, toolName, spaceArg, readScope));
    }
    if (!readScope.equals(toId(value))) {
      throw refuse(OUT_OF_SPACE.formatted(readScope, toolName, "%s %s".formatted(spaceArg, value), toolName, spaceArg, readScope));
    }
    return toolInput;
  }

  /**
   * Checks that the object a read by id opens belongs to the call's space,
   * through the resolver the tool names. A missing object, one in another
   * space, a resolver that fails and an absent resolver get the same refusal.
   *
   * @param toolName    the MCP tool name
   * @param declaration the tool's read scope
   * @param arguments   the call's arguments
   * @param readScope   the call's space
   * @throws ReadScopeRefusedException when the object isn't shown to belong
   *                                     to the call's space
   */
  private void checkObject(String toolName, ReadScopeDeclaration declaration, ObjectNode arguments, Long readScope) {
    String objectArg = declaration.getObjectArg();
    JsonNode value = arguments.get(objectArg);
    Long objectId = value == null ? null : toId(value);
    if (objectId == null) {
      throw refuse(OUT_OF_SPACE_OBJECT.formatted(readScope, toolName, objectArg, value == null ? "(none)" : value));
    }
    ReadScopeResolver resolver = getResolver(declaration.getResolver());
    if (resolver == null) {
      throw refuse(NOT_AVAILABLE.formatted(toolName, readScope));
    }
    if (!readScope.equals(spaceOf(resolver, objectId))) {
      throw refuse(OUT_OF_SPACE_OBJECT.formatted(readScope, toolName, objectArg, objectId));
    }
  }

  /**
   * @param resolver the object's resolver
   * @param objectId the object's id
   * @return the object's space, null when the resolver says none or fails
   */
  private Long spaceOf(ReadScopeResolver resolver, Long objectId) {
    try {
      return resolver.spaceOf(String.valueOf(objectId));
    } catch (RuntimeException e) {
      log.warn("Read scope resolver '{}' failed on object {}: the call is refused", resolver.getName(), objectId, e);
      return null;
    }
  }

  /**
   * @param name the resolver name a tool definition gives
   * @return the only resolver of that name, or null when none or several
   *         answer to it
   */
  private ReadScopeResolver getResolver(String name) {
    List<ReadScopeResolver> resolvers = readScopeResolvers == null ? List.of() :
                                                                   readScopeResolvers.orderedStream()
                                                                                     .filter(resolver -> name.equals(nameOf(resolver)))
                                                                                     .toList();
    if (resolvers.size() > 1) {
      log.warn("{} read scope resolvers are named '{}': the tools naming it are refused under a read scope", resolvers.size(), name);
    }
    return resolvers.size() == 1 ? resolvers.get(0) : null;
  }

  /**
   * @param resolver a contributed resolver
   * @return its name, null when it fails to give one
   */
  private String nameOf(ReadScopeResolver resolver) {
    try {
      return resolver.getName();
    } catch (RuntimeException e) {
      log.warn("Read scope resolver '{}' failed to give its name", resolver, e);
      return null;
    }
  }

  /**
   * Reads an id the way the tool will: a JSON integer, or a string of decimal
   * digits with no sign and no leading zero, so that the value checked is the
   * value the tool receives.
   *
   * @param value the argument value
   * @return the id, or null when the value isn't one
   */
  private Long toId(JsonNode value) {
    if (value.isIntegralNumber() && value.canConvertToLong()) {
      return value.longValue();
    } else if (value.isString() && DECIMAL_ID.matcher(value.stringValue()).matches()) {
      try {
        return Long.valueOf(value.stringValue());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * @param message the explanation the model reads
   * @return the refusal, logged for troubleshooting
   */
  private ReadScopeRefusedException refuse(String message) {
    log.debug("Tool call refused under a read scope: {}", message);
    return new ReadScopeRefusedException(message);
  }

}

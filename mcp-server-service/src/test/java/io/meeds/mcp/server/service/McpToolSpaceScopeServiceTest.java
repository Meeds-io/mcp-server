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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.meeds.mcp.server.constant.Origin;
import io.meeds.mcp.server.model.ActingIdentity;
import io.meeds.mcp.server.model.SpaceScopeDeclaration;
import io.meeds.mcp.server.model.SpaceScopeRefusedException;
import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.plugin.SpaceScopeResolver;

import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * Pins the space scope check: a call limited to one space reads that space and
 * nothing else, and a call that isn't limited is never looked at.
 */
class McpToolSpaceScopeServiceTest {

  private static final Long               SPACE_ID   = 12L;

  private static final ActingIdentity     SCOPED     = ActingIdentity.agent("agent-mail", "EMAIL_ASSISTANT", Origin.MENTION, SPACE_ID);

  private static final ActingIdentity     UNSCOPED   = ActingIdentity.person("john", Origin.CHAT);

  private final List<SpaceScopeResolver>   resolvers  = new ArrayList<>();

  private McpToolSpaceScopeService         service;

  /**
   * Builds the service over a resolver list each test fills.
   */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ObjectProvider<SpaceScopeResolver> provider = mock(ObjectProvider.class);
    lenient().when(provider.orderedStream()).thenAnswer(invocation -> resolvers.stream());
    service = new McpToolSpaceScopeService(provider);
  }

  /**
   * A call that isn't limited gets its input back untouched, the same string,
   * whatever the tool declares or the input holds. Mutant: the early return
   * removed, which refuses the undeclared tool.
   */
  @Test
  void unscopedCallIsUntouched() {
    String input = "{\"space_id\":99,\"space_id\":98,\"anything\":true}";
    SimpleToolDefinition write = definition("delete_space", false, null);

    assertThat(service.enforce("delete_space", write, input, UNSCOPED)).isSameAs(input);
    assertThat(service.enforce("delete_space", null, input, null)).isSameAs(input);
    assertThat(service.enforce("get_activities", spaceRead("use_scope"), "garbage", UNSCOPED)).isSameAs("garbage");
  }

  /**
   * Under a space scope, a tool with no declaration is refused, and the refusal
   * names the space. Mutant: an undeclared tool let through.
   */
  @Test
  void undeclaredToolIsRefused() {
    assertRefused(() -> service.enforce("list_my_favorites", definition("list_my_favorites", true, null), "{}", SCOPED),
                  "isn't available");
    assertRefused(() -> service.enforce("unknown", null, "{}", SCOPED), "isn't available");
  }

  /**
   * A write never runs under a space scope, even with a declaration; a tool
   * with no read-only hint counts as a write. Mutant: the read-only check
   * removed.
   */
  @Test
  void writeIsRefusedEvenWhenDeclared() {
    SimpleToolDefinition write = definition("create_activity", false, spaceDeclaration("use_scope"));
    SimpleToolDefinition noHint = new SimpleToolDefinition("create_activity", "t", "d", "{}", true, false, null);
    noHint.setSpaceScope(spaceDeclaration("use_scope"));

    assertRefused(() -> service.enforce("create_activity", write, "{\"space_id\":12}", SCOPED), "isn't available");
    assertRefused(() -> service.enforce("create_activity", noHint, "{\"space_id\":12}", SCOPED), "isn't available");
  }

  /**
   * A declaration that isn't exactly one kind is refused. Mutant: the
   * well-formedness check removed, which runs the unscoped branch.
   */
  @Test
  void malformedDeclarationIsRefused() {
    SpaceScopeDeclaration both = spaceDeclaration("use_scope");
    both.setUnscoped(true);
    SpaceScopeDeclaration objectWithoutResolver = new SpaceScopeDeclaration(null, null, "activity_id", null, false, null, null);

    assertRefused(() -> service.enforce("t", definition("t", true, both), "{\"space_id\":12}", SCOPED), "isn't available");
    assertRefused(() -> service.enforce("t", definition("t", true, objectWithoutResolver), "{\"activity_id\":1}", SCOPED),
                  "isn't available");
    assertRefused(() -> service.enforce("t", definition("t", true, new SpaceScopeDeclaration()), "{}", SCOPED), "isn't available");
  }

  /**
   * The space argument equal to the call's space runs as sent, as a number or
   * a decimal string. Mutant: the equality inverted.
   */
  @Test
  void sameSpaceRuns() {
    String input = "{\"space_id\":12,\"offset\":0}";

    assertThat(service.enforce("get_activities", spaceRead("use_scope"), input, SCOPED)).isSameAs(input);
    assertThat(service.enforce("get_activities", spaceRead("refuse"), "{\"space_id\":\"12\"}", SCOPED)).isEqualTo("{\"space_id\":\"12\"}");
  }

  /**
   * Another space, zero (which every tool reads as "all spaces"), a negative
   * id, a float, a signed or padded string and a non-id value are refused,
   * and the refusal tells the model which space id to use. Mutants: the
   * equality removed; the strict id reading relaxed.
   */
  @Test
  void anotherSpaceIsRefused() {
    for (String value : List.of("13", "0", "-12", "12.0", "1.2e1", "\"+12\"", "\"012\"", "\" 12\"", "true", "[12]", "{\"id\":12}")) {
      assertRefused(() -> service.enforce("get_activities", spaceRead("use_scope"), "{\"space_id\":" + value + "}", SCOPED),
                    "space_id 12");
    }
  }

  /**
   * An absent or null space argument is filled with the call's space when
   * the tool says {@code use_scope}, the other arguments kept. Mutant: the
   * fill removed, which passes the absent space through.
   */
  @Test
  void absentSpaceIsFilledWithUseScope() {
    assertThat(service.enforce("get_activities", spaceRead("use_scope"), "{\"offset\":5}", SCOPED)).isEqualTo("{\"offset\":5,\"space_id\":12}");
    assertThat(service.enforce("get_activities", spaceRead("use_scope"), "{\"space_id\":null}", SCOPED)).isEqualTo("{\"space_id\":12}");
    assertThat(service.enforce("get_activities", spaceRead("use_scope"), "", SCOPED)).isEqualTo("{\"space_id\":12}");
    assertThat(service.enforce("get_activities", spaceRead("use_scope"), null, SCOPED)).isEqualTo("{\"space_id\":12}");
  }

  /**
   * An absent space argument is refused when the tool says anything but
   * {@code use_scope}, or nothing. Mutant: the fill applied whatever the
   * declaration says.
   */
  @Test
  void absentSpaceIsRefusedOtherwise() {
    assertRefused(() -> service.enforce("get_activities", spaceRead("refuse"), "{}", SCOPED), "space_id 12");
    assertRefused(() -> service.enforce("get_activities", spaceRead(null), "{\"space_id\":null}", SCOPED), "space_id 12");
    assertRefused(() -> service.enforce("get_activities", spaceRead("USE_SCOPE"), "{}", SCOPED), "space_id 12");
  }

  /**
   * Only the declared arguments are accepted: an extra one, another spelling
   * of an accepted one (which the server reads as the same argument) and a
   * duplicated key are refused. Mutants: the name check removed; duplicate
   * detection off.
   */
  @Test
  void extraArgumentsAreRefused() {
    assertRefused(() -> service.enforce("get_activities", spaceRead("use_scope"), "{\"space_id\":12,\"stream\":\"all\"}", SCOPED),
                  "'stream' isn't one of them");
    assertRefused(() -> service.enforce("get_activities", spaceRead("use_scope"), "{\"space_id\":12,\"spaceId\":13}", SCOPED),
                  "'spaceId' isn't one of them");
    assertRefused(() -> service.enforce("get_activities", spaceRead("use_scope"), "{\"space_id\":12,\"space_id\":13}", SCOPED),
                  "distinct keys");
    assertRefused(() -> service.enforce("get_activities", spaceRead("use_scope"), "[12]", SCOPED), "distinct keys");
    assertRefused(() -> service.enforce("get_activities", spaceRead("use_scope"), "{not json", SCOPED), "distinct keys");
  }

  /**
   * A false-only argument may be false, null or absent, nothing else. Mutant:
   * the false-only check removed.
   */
  @Test
  void falseOnlyArgumentIsEnforced() {
    SimpleToolDefinition search = definition("search_activities",
                                             true,
                                             new SpaceScopeDeclaration("space_id",
                                                                      "use_scope",
                                                                      null,
                                                                      null,
                                                                      false,
                                                                      List.of("query"),
                                                                      List.of("is_favorite")));

    assertThat(service.enforce("search_activities", search, "{\"space_id\":12,\"is_favorite\":false}", SCOPED)).contains("false");
    assertThat(service.enforce("search_activities", search, "{\"space_id\":12,\"is_favorite\":null}", SCOPED)).contains("null");
    for (String value : List.of("true", "\"false\"", "0", "\"true\"")) {
      assertRefused(() -> service.enforce("search_activities", search, "{\"space_id\":12,\"is_favorite\":" + value + "}", SCOPED),
                    "'is_favorite' false or absent");
    }
  }

  /**
   * An unscoped declaration (the user directory) checks the argument names
   * only. Mutant: the name check skipped for that kind.
   */
  @Test
  void unscopedDeclarationChecksNamesOnly() {
    SimpleToolDefinition directory = definition("search_users",
                                                true,
                                                new SpaceScopeDeclaration(null, null, null, null, true, List.of("query", "limit"), null));
    String input = "{\"query\":\"mary\",\"limit\":5}";

    assertThat(service.enforce("search_users", directory, input, SCOPED)).isSameAs(input);
    assertRefused(() -> service.enforce("search_users", directory, "{\"query\":\"mary\",\"space_id\":13}", SCOPED),
                  "'space_id' isn't one of them");
  }

  /**
   * A read by id runs when its resolver puts the object in the call's space,
   * the id given as a number or a decimal string. Mutant: the resolver's
   * answer compared inverted.
   */
  @Test
  void objectInTheSpaceRuns() {
    SpaceScopeResolver resolver = resolver("activity");
    when(resolver.spaceOf("7")).thenReturn(SPACE_ID);
    String input = "{\"activity_id\":7,\"limit\":3}";

    assertThat(service.enforce("get_activity_comments", objectRead(), input, SCOPED)).isSameAs(input);
    assertThat(service.enforce("get_activity_comments", objectRead(), "{\"activity_id\":\"7\"}", SCOPED)).isEqualTo("{\"activity_id\":\"7\"}");
  }

  /**
   * An object in another space, an object of no space (missing), a resolver
   * that throws and an absent id all get the same refusal, which doesn't tell
   * them apart. Mutants: the comparison removed; the exception let through.
   */
  @Test
  void objectElsewhereMissingOrFailingIsRefusedTheSameWay() {
    SpaceScopeResolver resolver = resolver("activity");
    when(resolver.spaceOf("7")).thenReturn(13L);
    when(resolver.spaceOf("8")).thenReturn(null);
    when(resolver.spaceOf("9")).thenThrow(new IllegalStateException("database down"));

    String elsewhere = refusal(() -> service.enforce("get_activity", objectRead(), "{\"activity_id\":7}", SCOPED));
    String missing = refusal(() -> service.enforce("get_activity", objectRead(), "{\"activity_id\":8}", SCOPED));
    String failing = refusal(() -> service.enforce("get_activity", objectRead(), "{\"activity_id\":9}", SCOPED));

    assertThat(elsewhere).isEqualTo(missing.replace("activity_id 8", "activity_id 7"));
    assertThat(failing).isEqualTo(missing.replace("activity_id 8", "activity_id 9"));
    assertThat(missing).contains("space with id 12").doesNotContain("database down");
  }

  /**
   * An absent object argument, or one that isn't an id, is refused without
   * asking the resolver, even one that would answer the call's space for
   * anything. Mutant: the id check removed, which asks the resolver.
   */
  @Test
  void objectArgumentThatIsNoIdIsRefusedWithoutAskingTheResolver() {
    SpaceScopeResolver resolver = resolver("activity");
    lenient().when(resolver.spaceOf(anyString())).thenReturn(SPACE_ID);

    for (String input : List.of("{}", "{\"activity_id\":null}", "{\"activity_id\":\"07\"}", "{\"activity_id\":7.5}", "{\"activity_id\":\"7 \"}")) {
      assertRefused(() -> service.enforce("get_activity", objectRead(), input, SCOPED), "can only open an item");
    }
    verify(resolver, never()).spaceOf(anyString());
  }

  /**
   * A by-id tool whose resolver no add-on provides, or two provide under one
   * name, is refused without asking any of them. Mutant: the first resolver
   * of the name taken.
   */
  @Test
  void absentOrAmbiguousResolverIsRefused() {
    assertRefused(() -> service.enforce("get_activity", objectRead(), "{\"activity_id\":7}", SCOPED), "isn't available");

    SpaceScopeResolver first = resolver("activity");
    SpaceScopeResolver second = resolver("activity");
    lenient().when(first.spaceOf(anyString())).thenReturn(SPACE_ID);
    lenient().when(second.spaceOf(anyString())).thenReturn(SPACE_ID);
    assertRefused(() -> service.enforce("get_activity", objectRead(), "{\"activity_id\":7}", SCOPED), "isn't available");
    verify(first, never()).spaceOf(anyString());
    verify(second, never()).spaceOf(anyString());
  }

  /**
   * @param toolName the tool name
   * @param readOnly the read-only hint
   * @param scope    the space scope, may be null
   * @return a tool definition
   */
  private static SimpleToolDefinition definition(String toolName, boolean readOnly, SpaceScopeDeclaration scope) {
    SimpleToolDefinition definition = new SimpleToolDefinition(toolName,
                                                               "title",
                                                               "description",
                                                               "{}",
                                                               !readOnly,
                                                               false,
                                                               new ToolAnnotations("title", readOnly, false, true, false, null));
    definition.setSpaceScope(scope);
    return definition;
  }

  /**
   * @param whenAbsent the {@code when_absent} value
   * @return a space read declaration on {@code space_id} with offset and limit
   */
  private static SpaceScopeDeclaration spaceDeclaration(String whenAbsent) {
    return new SpaceScopeDeclaration("space_id", whenAbsent, null, null, false, List.of("offset", "limit"), null);
  }

  /**
   * @param whenAbsent the {@code when_absent} value
   * @return a read-only space tool
   */
  private static SimpleToolDefinition spaceRead(String whenAbsent) {
    return definition("get_activities", true, spaceDeclaration(whenAbsent));
  }

  /**
   * @return a read-only by-id tool resolved by {@code activity}
   */
  private static SimpleToolDefinition objectRead() {
    return definition("get_activity",
                      true,
                      new SpaceScopeDeclaration(null, null, "activity_id", "activity", false, List.of("offset", "limit"), null));
  }

  /**
   * @param name the resolver name
   * @return a mocked resolver, added to the contributed ones
   */
  private SpaceScopeResolver resolver(String name) {
    SpaceScopeResolver resolver = mock(SpaceScopeResolver.class);
    lenient().when(resolver.getName()).thenReturn(name);
    resolvers.add(resolver);
    return resolver;
  }

  /**
   * @param call     the call expected to be refused
   * @param fragment a fragment of the refusal message
   */
  private static void assertRefused(Runnable call, String fragment) {
    assertThatThrownBy(call::run).isInstanceOf(SpaceScopeRefusedException.class)
                                 .hasMessageContaining(fragment)
                                 .hasMessageContaining(String.valueOf(SPACE_ID));
  }

  /**
   * @param call the call expected to be refused
   * @return the refusal message
   */
  private static String refusal(Runnable call) {
    try {
      call.run();
    } catch (SpaceScopeRefusedException e) {
      return e.getMessage();
    }
    throw new AssertionError("The call wasn't refused");
  }

}

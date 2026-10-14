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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.meeds.mcp.server.constant.Origin;
import io.meeds.mcp.server.model.ActingIdentity;
import io.meeds.mcp.server.model.SpaceScopeRefusedException;
import io.meeds.mcp.server.plugin.SpaceScopeResolver;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.SpaceScopeDeclaration;
import io.meeds.mcp.server.service.McpToolSpaceScopeService;
import io.meeds.mcp.server.tool.plugin.ActivitySpaceScopeResolver;
import io.meeds.mcp.server.tool.plugin.CommentSpaceScopeResolver;
import io.meeds.mcp.server.util.McpToolUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the {@code space_scope} declarations this module ships: which tools a
 * call limited to one space may use, and on which arguments.
 */
class ToolDefinitionSpaceScopeTest {

  private static final Set<String> SPACE_READS          = Set.of("get_activities",
                                                                 "get_activities_since_days",
                                                                 "search_activities",
                                                                 "get_space_by_id",
                                                                 "get_space_members",
                                                                 "list_users_of_space_by_role",
                                                                 "get_scheduled_activities",
                                                                 "list_space_join_requests");

  /**
   * The sub-spaces list, which reads its space through
   * {@code parent_space_id}.
   */
  private static final Set<String> SUB_SPACE_READS      = Set.of("list_sub_spaces");

  private static final Set<String> OBJECT_READS         = Set.of("get_activity",
                                                                 "get_activity_comments",
                                                                 "get_activity_likers",
                                                                 "get_activity_attachments",
                                                                 "get_activity_comment",
                                                                 "get_comment_attachments");

  private static final Set<String> DIRECTORY            = Set.of("get_user_by_username");

  private static final Set<String> SPACE_WRITES         = Set.of("create_activity",
                                                                 "update_space_settings",
                                                                 "invite_users_to_space",
                                                                 "remove_users_from_space",
                                                                 "set_user_role_in_space",
                                                                 "remove_user_role_in_space",
                                                                 "accept_space_join_request",
                                                                 "decline_space_join_request",
                                                                 "delete_space",
                                                                 "create_activity_with_image",
                                                                 "set_space_avatar",
                                                                 "set_space_banner");

  private static final Set<String> OBJECT_WRITES        = Set.of("create_activity_comment",
                                                                 "update_activity",
                                                                 "delete_activity",
                                                                 "like_the_activity",
                                                                 "unlike_the_activity",
                                                                 "pin_activity",
                                                                 "unpin_activity",
                                                                 "publish_scheduled_activity_now",
                                                                 "create_activity_comment_with_image",
                                                                 "attach_image_to_activity",
                                                                 "remove_image_from_activity",
                                                                 "attach_image_to_comment",
                                                                 "remove_image_from_comment");

  /**
   * The object tools that take a comment, by the argument holding it: the
   * {@value CommentSpaceScopeResolver#NAME} resolver tells its space. Every
   * other object tool takes an activity through {@code activity_id} and the
   * {@value ActivitySpaceScopeResolver#NAME} resolver, which never places a
   * comment.
   */
  private static final Map<String, String> COMMENT_TOOLS = Map.of("get_activity_comment",
                                                                  "activity_comment_id",
                                                                  "get_comment_attachments",
                                                                  "comment_id",
                                                                  "attach_image_to_comment",
                                                                  "comment_id",
                                                                  "remove_image_from_comment",
                                                                  "comment_id");

  /**
   * Writes held back until their tool checks who may change the object: today
   * {@code update_comment} lets any member's comment be edited (EXO-91313).
   */
  private static final Set<String> HELD_BACK            = Set.of("update_comment");

  /**
   * The image writes: under a space scope their bytes come from the call or
   * from a public URL, never from another object's attachment.
   */
  private static final Set<String> IMAGE_WRITES         = Set.of("create_activity_with_image",
                                                                 "create_activity_comment_with_image",
                                                                 "attach_image_to_activity",
                                                                 "attach_image_to_comment",
                                                                 "set_space_avatar",
                                                                 "set_space_banner");

  /**
   * Arguments left out of a write only because the reference they go with is:
   * the type of the object whose attachment {@code attachment_object_id}
   * names. Alone, the tool ignores it.
   */
  private static final Set<String> COMPANION_ARGUMENTS  = Set.of("attachment_object_type");

  /**
   * The writes that administer the space itself: settings, members, roles,
   * join requests, deletion, its avatar and banner, and pinning a post to
   * its stream. Adding content (an activity, a comment) isn't, and neither is
   * deleting a post: the social rules on the worker decide that one.
   */
  private static final Set<String> SPACE_ADMIN_WRITES   = Set.of("update_space_settings",
                                                                 "set_space_avatar",
                                                                 "set_space_banner",
                                                                 "pin_activity",
                                                                 "unpin_activity",
                                                                 "invite_users_to_space",
                                                                 "remove_users_from_space",
                                                                 "set_user_role_in_space",
                                                                 "remove_user_role_in_space",
                                                                 "accept_space_join_request",
                                                                 "decline_space_join_request",
                                                                 "delete_space");

  /**
   * Writes whose effect leaves the space they are called in: they must stay
   * undeclared, so a call limited to a space never runs them. Those of other
   * add-ons are listed too, so a copy of this test there starts from it.
   */
  private static final Set<String> LEAVING_THE_SPACE    = Set.of("share_activity_to_space",
                                                                 "share_document",
                                                                 "create_public_link",
                                                                 "move_document",
                                                                 "copy_document",
                                                                 "move_note",
                                                                 "invite_space_to_agenda_event",
                                                                 "add_content_to_category");

  /**
   * Arguments that name people, who the space administration and the
   * meetings act on in the thread's space: not a place.
   */
  private static final Set<String> PEOPLE_ARGUMENTS     = Set.of("usernames_to_invite", "attendee_usernames", "username");

  /**
   * An argument shaped like a reference to a place or an object: an id or
   * ids, in snake or camel case, a folder, a space.
   */
  private static final Pattern     REFERENCE_SHAPED     = Pattern.compile("(^|_)ids?$|[a-z]Ids?$|(?i:folder|space)");

  private static final JsonMapper  MAPPER               = JsonMapper.builder().build();

  /**
   * Exactly the expected tools declare a space scope, each of the expected
   * kind: the reads with {@code when_absent} filling in the space, the writes
   * marked as such and without it. A tool added to the list, or a read
   * turned write, fails here first.
   */
  @Test
  void declaredToolsAreTheExpectedOnes() {
    Map<String, SimpleToolDefinition> declared = declaredTools();

    assertThat(declared.keySet()).containsExactlyInAnyOrderElementsOf(union(SPACE_READS,
                                                                            SUB_SPACE_READS,
                                                                            OBJECT_READS,
                                                                            DIRECTORY,
                                                                            SPACE_WRITES,
                                                                            OBJECT_WRITES));
    declared.forEach((name, tool) -> {
      boolean write = SPACE_WRITES.contains(name) || OBJECT_WRITES.contains(name);
      assertThat(tool.getAnnotations()).as(name).isNotNull();
      assertThat(McpToolSpaceScopeService.isReadTool(tool)).as(name).isEqualTo(!write);
      assertThat(tool.getSpaceScope().isWellFormedFor(write)).as(name).isTrue();
    });
    SPACE_READS.forEach(name -> {
      SpaceScopeDeclaration scope = declared.get(name).getSpaceScope();
      assertThat(scope.isSpaceKind()).as(name).isTrue();
      assertThat(scope.getSpaceArg()).as(name).isEqualTo("space_id");
      assertThat(scope.isUseScopeWhenAbsent()).as(name).isTrue();
    });
    SUB_SPACE_READS.forEach(name -> {
      SpaceScopeDeclaration scope = declared.get(name).getSpaceScope();
      assertThat(scope.isSpaceKind()).as(name).isTrue();
      assertThat(scope.getSpaceArg()).as(name).isEqualTo("parent_space_id");
      assertThat(scope.isUseScopeWhenAbsent()).as(name).isTrue();
    });
    SPACE_WRITES.forEach(name -> {
      SpaceScopeDeclaration scope = declared.get(name).getSpaceScope();
      assertThat(scope.isSpaceKind()).as(name).isTrue();
      assertThat(scope.getSpaceArg()).as(name).isEqualTo("space_id");
      assertThat(scope.getWhenAbsent()).as(name).isNull();
    });
    union(OBJECT_READS, OBJECT_WRITES).forEach(name -> {
      SpaceScopeDeclaration scope = declared.get(name).getSpaceScope();
      assertThat(scope.isObjectKind()).as(name).isTrue();
      if (COMMENT_TOOLS.containsKey(name)) {
        assertThat(scope.getObjectArg()).as(name).isEqualTo(COMMENT_TOOLS.get(name));
        assertThat(scope.getResolver()).as(name).isEqualTo(CommentSpaceScopeResolver.NAME);
      } else {
        assertThat(scope.getObjectArg()).as(name).isEqualTo("activity_id");
        assertThat(scope.getResolver()).as(name).isEqualTo(ActivitySpaceScopeResolver.NAME);
      }
    });
    assertThat(union(OBJECT_READS, OBJECT_WRITES)).containsAll(COMMENT_TOOLS.keySet());
    DIRECTORY.forEach(name -> assertThat(declared.get(name).getSpaceScope().isUnscopedKind()).as(name).isTrue());
    assertThat(declared.get("search_activities").getSpaceScope().getFalseOnlyArgs()).containsExactly("is_favorite");
  }

  /**
   * Exactly the space administration writes carry {@code space_admin}: no
   * read, no content write, and none of them missing it. Mutants: the flag
   * dropped from {@code delete_space} or {@code pin_activity}; set on
   * {@code create_activity} or {@code delete_activity}.
   */
  @Test
  void spaceAdminFlagIsOnTheSpaceAdministrationWritesOnly() {
    Map<String, SimpleToolDefinition> declared = declaredTools();

    assertThat(declared.values()
                       .stream()
                       .filter(tool -> tool.getSpaceScope().isSpaceAdmin())
                       .map(SimpleToolDefinition::getName)).containsExactlyInAnyOrderElementsOf(SPACE_ADMIN_WRITES);
    assertThat(union(SPACE_WRITES, OBJECT_WRITES)).containsAll(SPACE_ADMIN_WRITES);
    SPACE_ADMIN_WRITES.forEach(name -> assertThat(McpToolSpaceScopeService.isReadTool(declared.get(name))).as(name).isFalse());
  }

  /**
   * A read accepts exactly the tool's schema properties: none the tool
   * doesn't take, none it takes left unchecked. A write accepts only schema
   * properties, and leaves out only reference-shaped ones and their
   * companions.
   */
  @Test
  void acceptedArgumentsAreTheSchemaProperties() {
    declaredTools().forEach((name, tool) -> {
      Set<String> schema = new LinkedHashSet<>(properties(tool).propertyNames());
      Set<String> accepted = acceptedArguments(tool.getSpaceScope());
      if (McpToolSpaceScopeService.isReadTool(tool)) {
        assertThat(accepted).as(name).containsExactlyInAnyOrderElementsOf(schema);
      } else {
        assertThat(schema).as(name).containsAll(accepted);
        schema.stream()
              .filter(argument -> !accepted.contains(argument))
              .filter(argument -> !COMPANION_ARGUMENTS.contains(argument))
              .forEach(argument -> assertThat(isReferenceShaped(tool, argument)).as(name + " leaves out " + argument).isTrue());
      }
    });
  }

  /**
   * No write lets a call limited to a space pass a reference to another place
   * or object: its accepted arguments, besides the declared space or object
   * argument, hold nothing shaped like an id, a folder or a space, people
   * excepted, and an argument the schema restricts to fixed values (a role,
   * a visibility) is no reference. Mutant: {@code parent_comment_id} accepted
   * on {@code create_activity_comment}.
   */
  @Test
  void writesAcceptNoReferenceBesidesTheirPlace() {
    declaredTools().forEach((name, tool) -> {
      if (McpToolSpaceScopeService.isReadTool(tool)) {
        return;
      }
      SpaceScopeDeclaration scope = tool.getSpaceScope();
      Set<String> others = union(Set.copyOf(listOf(scope.getAllowedArgs())), Set.copyOf(listOf(scope.getFalseOnlyArgs())));
      others.forEach(argument -> assertThat(isReferenceShaped(tool, argument)).as(name + " accepts " + argument).isFalse());
    });
    assertThat(isReferenceShaped(declaredTools().get("create_activity_comment"), "parent_comment_id")).isTrue();
    assertThat(isReferenceShaped(declaredTools().get("set_user_role_in_space"), "space_role")).isFalse();
    for (String reference : List.of("id", "ids", "target_ids", "spaceId", "folderId", "targetIds", "parentFolder", "Space")) {
      assertThat(isReferenceShaped(declaredTools().get("create_activity"), reference)).as(reference).isTrue();
    }
    for (String plain : List.of("username", "html_content", "comment", "valid", "kids_count")) {
      assertThat(isReferenceShaped(declaredTools().get("create_activity"), plain)).as(plain).isFalse();
    }
  }

  /**
   * The shipped definitions, run through the server's check under a space
   * scope: {@code search_users} is refused, {@code get_user_by_username}
   * passes, and a tool the file doesn't declare is refused. Mutant: the
   * {@code space_scope} declaration put back on {@code search_users}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void searchUsersIsRefusedUnderASpaceScopeAndGetUserByUsernamePasses() {
    ObjectProvider<SpaceScopeResolver> provider = mock(ObjectProvider.class);
    when(provider.orderedStream()).thenAnswer(invocation -> Stream.<SpaceScopeResolver> empty());
    McpToolSpaceScopeService service = new McpToolSpaceScopeService(provider);
    ActingIdentity scoped = ActingIdentity.agent("agent-mail", "EMAIL_ASSISTANT", Origin.MENTION, 12L);
    Map<String, SimpleToolDefinition> shipped = McpToolUtils.parseToolDefinitions(getClass().getClassLoader()
                                                                                            .getResource("ai-tool-definitions.json"))
                                                            .stream()
                                                            .collect(Collectors.toMap(SimpleToolDefinition::getName, tool -> tool));
    String lookup = "{\"username\":\"mary\"}";
    String search = "{\"query\":\"mary\"}";

    assertThat(shipped).containsKeys("search_users", "get_user_by_username");
    assertThat(service.enforce("get_user_by_username", shipped.get("get_user_by_username"), lookup, scoped)).isSameAs(lookup);
    assertThatThrownBy(() -> service.enforce("search_users", shipped.get("search_users"), search, scoped))
        .isInstanceOf(SpaceScopeRefusedException.class)
        .hasMessageContaining("isn't available");
    assertThat(service.enforce("search_users", shipped.get("search_users"), search, ActingIdentity.person("john", Origin.CHAT)))
        .isSameAs(search);
  }

  /**
   * The image writes take their bytes from the call ({@code image_base64}) or
   * a public URL ({@code image_url}) under a space scope, never from another
   * object's attachment, which the worker could read outside the space.
   * Mutant: {@code attachment_object_id} accepted on an image write.
   */
  @Test
  void imageWritesTakeTheCallBytesOrAUrlOnly() {
    Map<String, SimpleToolDefinition> declared = declaredTools();

    IMAGE_WRITES.forEach(name -> {
      Set<String> accepted = acceptedArguments(declared.get(name).getSpaceScope());
      assertThat(accepted).as(name).contains("image_url", "image_base64");
      assertThat(accepted).as(name).doesNotContain("attachment_object_id", "attachment_object_type");
      assertThat(properties(declared.get(name)).has("attachment_object_id")).as(name).isTrue();
    });
    assertThat(acceptedArguments(declared.get("remove_image_from_activity").getSpaceScope())).doesNotContain("file_id");
    assertThat(acceptedArguments(declared.get("remove_image_from_comment").getSpaceScope())).doesNotContain("file_id");
  }

  /**
   * A write held back stays undeclared until its tool checks who may change
   * the object. Mutant: {@code update_comment} declared.
   */
  @Test
  void heldBackWritesStayUndeclared() {
    URL url = getClass().getClassLoader().getResource("ai-tool-definitions.json");
    List<SimpleToolDefinition> shipped = McpToolUtils.parseToolDefinitions(url);

    assertThat(shipped).extracting(SimpleToolDefinition::getName).containsAll(HELD_BACK);
    shipped.stream()
           .filter(tool -> HELD_BACK.contains(tool.getName()))
           .forEach(tool -> assertThat(tool.getSpaceScope()).as(tool.getName()).isNull());
  }

  /**
   * The shipped comment and sub-space declarations, run through the server's
   * check under a space scope with the comment resolver answering: a comment
   * of the space passes and one of another space is refused; an approved
   * image write naming an attachment is refused by name; the sub-spaces list
   * reads the scope's space when it names none, and another parent is
   * refused. Mutants: the comment tools declared with the activity resolver;
   * {@code attachment_object_id} accepted; {@code when_absent} dropped from
   * {@code list_sub_spaces}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void shippedCommentAndSubSpaceDeclarationsHoldUnderTheServerCheck() {
    SpaceScopeResolver comments = mock(SpaceScopeResolver.class);
    when(comments.getName()).thenReturn(CommentSpaceScopeResolver.NAME);
    when(comments.spaceOf("21")).thenReturn(12L);
    when(comments.spaceOf("22")).thenReturn(13L);
    ObjectProvider<SpaceScopeResolver> provider = mock(ObjectProvider.class);
    when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(comments));
    McpToolSpaceScopeService service = new McpToolSpaceScopeService(provider);
    ActingIdentity mention = ActingIdentity.agent("agent-mail", "EMAIL_ASSISTANT", Origin.MENTION, 12L);
    ActingIdentity proposal = ActingIdentity.agent("agent-mail", "EMAIL_ASSISTANT", Origin.PROPOSAL, 12L);
    Map<String, SimpleToolDefinition> shipped = McpToolUtils.parseToolDefinitions(getClass().getClassLoader()
                                                                                            .getResource("ai-tool-definitions.json"))
                                                            .stream()
                                                            .collect(Collectors.toMap(SimpleToolDefinition::getName, tool -> tool));
    String inSpace = "{\"comment_id\":21}";
    String byUrl = "{\"comment_id\":21,\"image_url\":\"https://example.org/a.png\"}";

    assertThat(service.enforce("get_comment_attachments", shipped.get("get_comment_attachments"), inSpace, mention))
        .isSameAs(inSpace);
    assertThatThrownBy(() -> service.enforce("get_comment_attachments",
                                             shipped.get("get_comment_attachments"),
                                             "{\"comment_id\":22}",
                                             mention)).isInstanceOf(SpaceScopeRefusedException.class)
                                                     .hasMessageContaining("isn't one of them");
    assertThat(service.enforce("attach_image_to_comment", shipped.get("attach_image_to_comment"), byUrl, proposal))
        .isSameAs(byUrl);
    assertThatThrownBy(() -> service.enforce("attach_image_to_comment",
                                             shipped.get("attach_image_to_comment"),
                                             "{\"comment_id\":21,\"attachment_object_type\":\"activity\",\"attachment_object_id\":\"9\"}",
                                             proposal)).isInstanceOf(SpaceScopeRefusedException.class)
                                                       .hasMessageContaining("accepts only the arguments");
    assertThat(service.enforce("list_sub_spaces", shipped.get("list_sub_spaces"), "{}", mention))
        .isEqualTo("{\"parent_space_id\":12}");
    assertThatThrownBy(() -> service.enforce("list_sub_spaces",
                                             shipped.get("list_sub_spaces"),
                                             "{\"parent_space_id\":13}",
                                             mention)).isInstanceOf(SpaceScopeRefusedException.class)
                                                      .hasMessageContaining("can only reach that space");
  }

  /**
   * The writes whose effect leaves the space stay undeclared. Mutant: a
   * space scope declared on {@code share_activity_to_space}.
   */
  @Test
  void writesLeavingTheSpaceStayUndeclared() {
    URL url = getClass().getClassLoader().getResource("ai-tool-definitions.json");
    List<SimpleToolDefinition> shipped = McpToolUtils.parseToolDefinitions(url);

    assertThat(shipped).extracting(SimpleToolDefinition::getName).contains("share_activity_to_space", "add_content_to_category");
    shipped.stream()
           .filter(tool -> LEAVING_THE_SPACE.contains(tool.getName()))
           .forEach(tool -> assertThat(tool.getSpaceScope()).as(tool.getName()).isNull());
  }

  /**
   * @param tool     the tool
   * @param argument one of its arguments
   * @return true when the argument is shaped like a reference to a place or
   *         an object, and is neither a people argument nor restricted by the
   *         schema to fixed values
   */
  private static boolean isReferenceShaped(SimpleToolDefinition tool, String argument) {
    return REFERENCE_SHAPED.matcher(argument).find()
           && !PEOPLE_ARGUMENTS.contains(argument)
           && !properties(tool).path(argument).has("enum");
  }

  /**
   * @param tool the tool
   * @return the properties of its input schema
   */
  private static JsonNode properties(SimpleToolDefinition tool) {
    return MAPPER.readTree(tool.getInputSchema()).path("properties");
  }

  /**
   * @param scope a space scope declaration
   * @return the arguments a limited call may carry
   */
  private static Set<String> acceptedArguments(SpaceScopeDeclaration scope) {
    Set<String> accepted = new LinkedHashSet<>();
    if (scope.getSpaceArg() != null) {
      accepted.add(scope.getSpaceArg());
    }
    if (scope.getObjectArg() != null) {
      accepted.add(scope.getObjectArg());
    }
    accepted.addAll(listOf(scope.getAllowedArgs()));
    accepted.addAll(listOf(scope.getFalseOnlyArgs()));
    return accepted;
  }

  /**
   * @param values a list, may be null
   * @return the list, empty when null
   */
  private static List<String> listOf(List<String> values) {
    return values == null ? List.of() : values;
  }

  /**
   * @return the shipped tools that declare a space scope, by name
   */
  private Map<String, SimpleToolDefinition> declaredTools() {
    URL url = getClass().getClassLoader().getResource("ai-tool-definitions.json");
    return McpToolUtils.parseToolDefinitions(url)
                       .stream()
                       .filter(tool -> tool.getSpaceScope() != null)
                       .collect(Collectors.toMap(SimpleToolDefinition::getName, tool -> tool));
  }

  /**
   * @param sets the sets to join
   * @return their union
   */
  @SafeVarargs
  private static Set<String> union(Set<String>... sets) {
    Set<String> union = new LinkedHashSet<>();
    for (Set<String> set : sets) {
      union.addAll(set);
    }
    return union;
  }

}

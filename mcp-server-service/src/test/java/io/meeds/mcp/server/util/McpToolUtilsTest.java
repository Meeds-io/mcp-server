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
package io.meeds.mcp.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.meeds.mcp.server.util.McpToolUtils.ARGUMENTS_TOO_LARGE_NOTE;
import static io.meeds.mcp.server.util.McpToolUtils.MAX_PUBLISHED_INPUT_LENGTH;
import static io.meeds.mcp.server.util.McpToolUtils.redactBase64ForPublication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.model.ToolDefinitionMethods;

class McpToolUtilsTest {

  private static final String TOOL_NAME       = "alpha_tool";

  private static final String CONVERSATION_ID = "conv-42";

  private static final String USERNAME        = "john";

  private static final String EXTERNAL_CLIENT_ID = "claude-desktop";

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
    ConversationState.setCurrent(null);
  }

  @Test
  void getCurrentConversationId_internalClientWithContextId_returnsHeader() {// NOSONAR
    authenticateAsInternalClient();
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, USERNAME);

    assertEquals(CONVERSATION_ID, McpToolUtils.getCurrentConversationId());
  }

  @Test
  void getCurrentConversationId_wrongContextId_returnsNull() {// NOSONAR
    authenticateAsInternalClient();
    bindRequest("forged-context-id", CONVERSATION_ID, USERNAME);

    assertNull(McpToolUtils.getCurrentConversationId());
  }

  @Test
  void getCurrentConversationId_externalOauthUser_returnsNullEvenWithHeaders() {// NOSONAR
    // An external MCP client authenticates as the end user, not as the
    // internal client-credentials registration: its headers are never trusted
    authenticateAs(USERNAME);
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, USERNAME);

    assertNull(McpToolUtils.getCurrentConversationId());
  }

  @Test
  void getCurrentConversationId_blankHeaderOrNoRequest_returnsNull() {// NOSONAR
    authenticateAsInternalClient();
    assertNull(McpToolUtils.getCurrentConversationId());

    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, "   ", USERNAME);
    assertNull(McpToolUtils.getCurrentConversationId());
  }

  @Test
  void getCurrentUserName_internalClientWithContextId_returnsHeader() {// NOSONAR
    authenticateAsInternalClient();
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, USERNAME);

    assertEquals(USERNAME, McpToolUtils.getCurrentUserName());
  }

  @Test
  void getCurrentUserName_wrongContextId_returnsNull() {// NOSONAR
    authenticateAsInternalClient();
    bindRequest("forged-context-id", CONVERSATION_ID, USERNAME);

    assertNull(McpToolUtils.getCurrentUserName());
  }

  @Test
  void getCurrentUserName_externalOauthUser_returnsPrincipal() {// NOSONAR
    authenticateAs(USERNAME);
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, "someone-else");

    assertEquals(USERNAME, McpToolUtils.getCurrentUserName());
  }

  @Test
  void getCurrentConversationId_userTokenWhoseLoginIsTheInternalClientId_returnsNull() {// NOSONAR
    // The token subject is the user login, and nothing reserves the login
    // 'mcp-internal': only the OAuth client that owns the token identifies the
    // internal call. Even with the real contextId, this is not that client.
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, EXTERNAL_CLIENT_ID);
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, "someone-else");

    assertNull(McpToolUtils.getCurrentConversationId());
  }

  @Test
  void getCurrentUserName_userTokenWhoseLoginIsTheInternalClientId_isThatUserNotTheHeader() {// NOSONAR
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, EXTERNAL_CLIENT_ID);
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, "someone-else");

    assertEquals(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, McpToolUtils.getCurrentUserName());
  }

  @Test
  void getCurrentConversationId_nonBearerAuthenticationNamedLikeInternalClient_returnsNull() {// NOSONAR
    // No token attributes at all: there is no client to bind the call to, so
    // this is an ordinary principal that happens to bear that name
    authenticateAs(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);
    bindRequest(McpToolUtils.TOOL_CONTEXT_ID, CONVERSATION_ID, USERNAME);

    assertNull(McpToolUtils.getCurrentConversationId());
    assertEquals(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID, McpToolUtils.getCurrentUserName());
  }

  @Test
  void getCurrentConversationId_missingContextIdHeader_returnsNull() {// NOSONAR
    authenticateAsInternalClient();
    bindRequest(null, CONVERSATION_ID, USERNAME);

    assertNull(McpToolUtils.getCurrentConversationId());
    assertNull(McpToolUtils.getCurrentUserName());
  }

  private static void authenticateAs(String principal) {
    SecurityContextHolder.getContext()
                         .setAuthentication(new UsernamePasswordAuthenticationToken(principal, "N/A", List.of()));
  }

  /**
   * Authenticates as the internal client-credentials call: a bearer token
   * whose subject and owning client are both the internal registration id, as
   * the authorization server issues it for that grant.
   */
  private static void authenticateAsInternalClient() {
    authenticateAsBearer(McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID,
                         McpToolUtils.MCP_OAUTH2_CLIENT_CREDENTIALS_REGISTRATION_ID);
  }

  /**
   * Authenticates with an introspected bearer token, the shape the resource
   * server builds from the introspection response: the subject is the
   * principal name, the owning OAuth client is the 'client_id' attribute.
   */
  private static void authenticateAsBearer(String principalName, String clientId) {
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_" + McpToolUtils.TOOL_WRITE_APPROVE_SCOPE));
    DefaultOAuth2AuthenticatedPrincipal principal =
                                                  new DefaultOAuth2AuthenticatedPrincipal(principalName,
                                                                                          Map.of(OAuth2TokenIntrospectionClaimNames.SUB,
                                                                                                 principalName,
                                                                                                 OAuth2TokenIntrospectionClaimNames.CLIENT_ID,
                                                                                                 clientId),
                                                                                          authorities);
    OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                                          "token-" + principalName,
                                                          Instant.now(),
                                                          Instant.now().plusSeconds(60));
    SecurityContextHolder.getContext().setAuthentication(new BearerTokenAuthentication(principal, accessToken, authorities));
  }

  @Test
  void getCurrentTurnId_internalClient_returnsTrimmedHeader() {// NOSONAR
    authenticateAsInternalClient();
    MockHttpServletRequest request = bindRequestWithTurnId("  turn-7  ");

    assertEquals("turn-7", McpToolUtils.getCurrentTurnId());
    assertNotNull(request);
  }

  @Test
  void getCurrentTurnId_missingBlankOrTooLong_returnsNull() {// NOSONAR
    authenticateAsInternalClient();
    bindRequestWithTurnId(null);
    assertNull(McpToolUtils.getCurrentTurnId());

    bindRequestWithTurnId("   ");
    assertNull(McpToolUtils.getCurrentTurnId());

    bindRequestWithTurnId("t".repeat(McpToolUtils.MAX_TURN_ID_LENGTH + 1));
    assertNull(McpToolUtils.getCurrentTurnId());

    String longest = "t".repeat(McpToolUtils.MAX_TURN_ID_LENGTH);
    bindRequestWithTurnId(longest);
    assertEquals(longest, McpToolUtils.getCurrentTurnId());
  }

  @Test
  void getCurrentTurnId_externalOauthUser_returnsNullEvenWithHeader() {// NOSONAR
    authenticateAs(USERNAME);
    bindRequestWithTurnId("turn-7");

    assertNull(McpToolUtils.getCurrentTurnId());
  }

  private static MockHttpServletRequest bindRequestWithTurnId(String turnId) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(McpToolUtils.TOOL_CONTEXT_ID_PARAM, McpToolUtils.TOOL_CONTEXT_ID);
    request.addHeader(McpToolUtils.TOOL_CONTEXT_CONVERSATION_ID_PARAM, CONVERSATION_ID);
    request.addHeader(McpToolUtils.TOOL_CONTEXT_USER_NAME_PARAM, USERNAME);
    if (turnId != null) {
      request.addHeader(McpToolUtils.TOOL_CONTEXT_TURN_ID_PARAM, turnId);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

  private static void bindRequest(String contextId, String conversationId, String userName) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (contextId != null) {
      request.addHeader(McpToolUtils.TOOL_CONTEXT_ID_PARAM, contextId);
    }
    request.addHeader(McpToolUtils.TOOL_CONTEXT_CONVERSATION_ID_PARAM, conversationId);
    request.addHeader(McpToolUtils.TOOL_CONTEXT_USER_NAME_PARAM, userName);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @Test
  void fromJsonString_blank_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.fromJsonString(null));
    assertNull(McpToolUtils.fromJsonString(""));
    assertNull(McpToolUtils.fromJsonString("   "));
  }

  @Test
  void toJsonStringBase64_encodesInputSchema_andFromJsonStringBase64_decodesBack() {// NOSONAR
    SimpleToolDefinition tool = new SimpleToolDefinition();
    tool.setName(TOOL_NAME);
    tool.setDescription("Alpha");
    tool.setInputSchema("{\"type\":\"object\",\"x\":1}");
    tool.setRequireApproval(true);
    tool.setDisabled(false);

    ToolDefinitionMethods original = new ToolDefinitionMethods(List.of(tool));

    String encodedJson = McpToolUtils.toJsonStringBase64(original);
    assertNotNull(encodedJson);
    assertTrue(encodedJson.contains(TOOL_NAME));

    assertFalse(encodedJson.contains("\"type\":\"object\""));

    ToolDefinitionMethods decoded = McpToolUtils.fromJsonStringBase64(encodedJson);
    assertNotNull(decoded);
    assertEquals(1, decoded.tools().size());

    SimpleToolDefinition decodedTool = decoded.tools().get(0);
    assertEquals(TOOL_NAME, decodedTool.getName());
    assertEquals("Alpha", decodedTool.getDescription());
    assertEquals("{\"type\":\"object\",\"x\":1}", decodedTool.getInputSchema());
    assertTrue(decodedTool.isRequireApproval());
    assertFalse(decodedTool.isDisabled());
  }

  /**
   * A tool with no icon takes the file's default; a tool's own icon wins.
   *
   * @param tmp a temporary folder
   */
  @Test
  void parseToolDefinitions_fileDefaultIcon_appliesToToolsWithoutTheirOwn(@TempDir Path tmp) throws Exception {// NOSONAR
    Path file = tmp.resolve("ai-tool-definitions.json");
    Files.writeString(file, """
        { "icon": "fa-tasks",
          "tools": [ { "name": "plain_tool", "input_schema": {} },
                     { "name": "own_icon_tool", "icon": "fa-clipboard", "input_schema": {} } ] }
        """, StandardCharsets.UTF_8);

    List<SimpleToolDefinition> tools = McpToolUtils.parseToolDefinitions(file.toUri().toURL());

    assertEquals(2, tools.size());
    assertEquals("fa-tasks", tools.get(0).getIcon());
    assertEquals("fa-clipboard", tools.get(1).getIcon());
  }

  /**
   * A property this server does not know, at the file or the tool level,
   * no longer drops every tool of the file.
   *
   * @param tmp a temporary folder
   */
  @Test
  void parseToolDefinitions_unknownProperties_keepTheTools(@TempDir Path tmp) throws Exception {// NOSONAR
    Path file = tmp.resolve("ai-tool-definitions.json");
    Files.writeString(file, """
        { "future_file_field": 1,
          "tools": [ { "name": "plain_tool", "future_tool_field": { "x": true }, "input_schema": {} } ] }
        """, StandardCharsets.UTF_8);

    List<SimpleToolDefinition> tools = McpToolUtils.parseToolDefinitions(file.toUri().toURL());

    assertEquals(1, tools.size());
    assertEquals("plain_tool", tools.get(0).getName());
    assertNull(tools.get(0).getIcon());
  }

  /**
   * The persisted copy keeps the icon through the base64 round trip.
   */
  @Test
  void toJsonStringBase64_keepsTheIcon() {// NOSONAR
    SimpleToolDefinition tool = new SimpleToolDefinition();
    tool.setName(TOOL_NAME);
    tool.setInputSchema("{}");
    tool.setIcon("fa-clipboard");

    ToolDefinitionMethods decoded = McpToolUtils.fromJsonStringBase64(McpToolUtils.toJsonStringBase64(new ToolDefinitionMethods(List.of(tool))));

    assertEquals("fa-clipboard", decoded.tools().get(0).getIcon());
  }

  @Test
  void fromJsonStringBase64_blank_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.fromJsonStringBase64(null));
    assertNull(McpToolUtils.fromJsonStringBase64(""));
    assertNull(McpToolUtils.fromJsonStringBase64("   "));
  }

  @Test
  void toSnakeCase_nullOrEmpty_passthrough() {// NOSONAR
    assertNull(McpToolUtils.toSnakeCase(null));
    assertEquals("", McpToolUtils.toSnakeCase(""));
  }

  @Test
  void toSnakeCase_convertsCamelToSnake() {// NOSONAR
    assertEquals("my_tool", McpToolUtils.toSnakeCase("myTool"));
    assertEquals("my_tool_name", McpToolUtils.toSnakeCase("myToolName"));
    assertEquals("a", McpToolUtils.toSnakeCase("A"));
  }

  @Test
  void toCamelCase_nullOrEmpty_passthrough() {// NOSONAR
    assertNull(McpToolUtils.toCamelCase(null));
    assertEquals("", McpToolUtils.toCamelCase(""));
  }

  @Test
  void toCamelCase_convertsSnakeToCamel() {// NOSONAR
    assertEquals("myTool", McpToolUtils.toCamelCase("my_tool"));
    assertEquals("myToolName", McpToolUtils.toCamelCase("my_tool_name"));
    assertEquals("alreadyCamel", McpToolUtils.toCamelCase("alreadyCamel"));
  }

  @Test
  void markdownToHtml_rendersBasicMarkdown() {// NOSONAR
    String html = McpToolUtils.markdownToHtml("**bold**");
    assertNotNull(html);
    assertTrue(html.contains("<strong>bold</strong>"), "Expected strong tag in HTML output");
  }

  @Test
  void markdownToHtml_nullInput_returnsOriginalViaCatch() {// NOSONAR
    assertNull(McpToolUtils.markdownToHtml(null));
  }

  @Test
  void toDate_blank_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.toDate(null));
    assertNull(McpToolUtils.toDate(""));
    assertNull(McpToolUtils.toDate("   "));
  }

  @Test
  void formatDate_long_nullOrNonPositive_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.formatDate((Long) null));
    assertNull(McpToolUtils.formatDate(0L));
    assertNull(McpToolUtils.formatDate(-1L));
  }

  @Test
  void formatDate_date_null_returnsNull() {// NOSONAR
    assertNull(McpToolUtils.formatDate((Date) null));
  }

  @Test
  void formatDate_and_toDate_roundTrip() throws Exception { // NOSONAR
    String username = "root";
    Identity userIdentity = new Identity(username);
    ConversationState state = new ConversationState(userIdentity);
    ConversationState.setCurrent(state);

    CommonsUtils.getService(OrganizationService.class)
                .getUserProfileHandler()
                .findUserProfileByName(username)
                .setAttribute("exo.timezone", "UTC");

    Date now = new Date();
    String formatted = McpToolUtils.formatDate(now);

    assertNotNull(formatted);

    Date parsed = McpToolUtils.toDate(formatted);
    assertNotNull(parsed);

    long deltaMs = Math.abs(parsed.getTime() - now.getTime());
    assertTrue(deltaMs < 2000, "Expected roundtrip date to be within 2s, got delta=" + deltaMs + "ms");
  }

  /**
   * A top-level {@code _base64} string becomes its decoded size, a data URI
   * prefix and whitespace not counted, and the other arguments stay.
   */
  @Test
  void redactBase64ReplacesTheImageWithItsDecodedSize() {
    String twoMb = "data:image/png;base64," + "AAAA\n".repeat(699_051);
    String redacted = redactBase64ForPublication("{\"name\":\"x\",\"image_base64\":\"" + twoMb.replace("\n", "\\n") + "\"}");

    assertEquals("{\"name\":\"x\",\"image_base64\":\"[image, 2.0 MB]\"}", redacted);
    assertEquals("{\"image_base64\":\"[image, 1 KB]\"}", redactBase64ForPublication("{\"image_base64\":\"" + "A".repeat(1_000) + "\"}"));
    assertEquals("{\"image_base64\":\"[image, 3 KB]\"}", redactBase64ForPublication("{\"image_base64\":\"" + "A".repeat(4_000) + "\"}"));
  }

  /**
   * Every top-level argument whose name ends with {@code _base64} is
   * redacted, and none that is nested or that isn't a string.
   */
  @Test
  void redactBase64OnlyTouchesTopLevelStringsWithTheSuffix() {
    String redacted = redactBase64ForPublication("{\"a_base64\":\"AAAA\",\"b_base64\":\"BBBBBBBB\",\"n_base64\":5,"
                                                 + "\"nested\":{\"c_base64\":\"CCCC\"},\"base64x\":\"DDDD\"}");

    assertEquals("{\"a_base64\":\"[image, 1 KB]\",\"b_base64\":\"[image, 1 KB]\",\"n_base64\":5,"
                 + "\"nested\":{\"c_base64\":\"CCCC\"},\"base64x\":\"DDDD\"}",
                 redacted);
  }

  /**
   * An input without a {@code _base64} argument is published byte for byte,
   * whatever its length, its spacing or its escapes.
   */
  @Test
  void redactBase64LeavesAnInputWithoutTheSuffixUntouched() {
    String spaced = "{ \"message\" : \"h\\u00e9llo\",  \"n\":1 }";
    String large = "{\"html\":\"" + "x".repeat(MAX_PUBLISHED_INPUT_LENGTH * 2) + "\"}";
    String suffixInsideKey = "{ \"image_base64_x\" : \"AAAA\" }";

    assertEquals(spaced, redactBase64ForPublication(spaced));
    assertEquals(large, redactBase64ForPublication(large));
    assertEquals(suffixInsideKey, redactBase64ForPublication(suffixInsideKey));
    assertNull(redactBase64ForPublication(null));
    assertEquals("", redactBase64ForPublication(""));
  }

  /**
   * An argument whose name is written with a unicode escape is still found
   * on a long input, and redacted.
   */
  @Test
  void redactBase64FindsAnEscapedKeyOnALongInput() {
    String input = "{\"image\\u005fbase64\":\"" + "A".repeat(MAX_PUBLISHED_INPUT_LENGTH * 2) + "\"}";

    String published = redactBase64ForPublication(input);

    assertTrue(published.length() < 100, published);
    assertTrue(published.contains("[image, "), published);
    assertFalse(published.contains("AAAA"), published);
  }

  /**
   * A short input whose argument name is written with a unicode escape, so
   * that its text holds no {@code base64}, is redacted too. Mutant: a text
   * pre-check that skips parsing a short input without {@code base64}.
   */
  @Test
  void redactBase64FindsAnEscapedKeyOnAShortInput() {
    String input = "{\"image_b\\u0061se64\":\"" + "A".repeat(4_000) + "\"}";

    assertEquals("{\"image_base64\":\"[image, 3 KB]\"}", redactBase64ForPublication(input));
  }

  /**
   * An image argument spelled as the tool binds it, {@code imageBase64}, or
   * as {@code image_Base64}, is redacted on a short input too: both bind to
   * the tool's {@code imageBase64}. Mutants: a case-sensitive text pre-check
   * that skips parsing a short input; the raw name matched instead of the
   * bound one.
   */
  @Test
  void redactBase64FindsACamelCaseImageArgument() {
    String camelCase = "{\"imageBase64\":\"" + "A".repeat(4_000) + "\"}";
    String mixed = "{\"image_Base64\":\"" + "A".repeat(4_000) + "\"}";

    assertEquals("{\"imageBase64\":\"[image, 3 KB]\"}", redactBase64ForPublication(camelCase));
    assertEquals("{\"image_Base64\":\"[image, 3 KB]\"}", redactBase64ForPublication(mixed));
  }

  /**
   * A short input whose argument name binds to {@code imageBase64} or
   * {@code base64} only once the binding drops an underscore before a digit
   * ({@code image_base_64}, {@code base_64}) is redacted too, since every input
   * is parsed. Mutant: a text pre-check that skips parsing a short input whose
   * lower-cased text holds no {@code base64}.
   */
  @Test
  void redactBase64FindsANameThatBindsThroughAnUnderscoreBeforeADigit() {
    String image = "{\"image_base_64\":\"" + "A".repeat(4_000) + "\"}";
    String file = "{\"base_64\":\"" + "A".repeat(4_000) + "\"}";

    assertEquals("{\"image_base_64\":\"[image, 3 KB]\"}", redactBase64ForPublication(image));
    assertEquals("{\"base_64\":\"[file, 3 KB]\"}", redactBase64ForPublication(file));
  }

  /**
   * A short input with no base64 argument is returned as the caller wrote it,
   * never re-serialised, and a short input that is not JSON is returned as
   * is.
   */
  @Test
  void redactBase64LeavesAShortInputWithoutBase64ArgumentUnchanged() {
    String input = "{ \"title\" : \"Weekly\",  \"ratio\": 1.10 }";
    String notJson = "not a json { input";

    assertSame(input, redactBase64ForPublication(input));
    assertSame(notJson, redactBase64ForPublication(notJson));
  }

  /**
   * A top-level string argument named exactly {@code base64}, as the document
   * tools name their file, becomes a file placeholder, short or long, while a
   * nested one or a longer name containing it stays. Mutants: the exact name
   * not matched; a text pre-check keyed on the image suffix only.
   */
  @Test
  void redactBase64ReplacesTheFileArgumentWithItsDecodedSize() {
    String shortFile = "{\"title\":\"report.pdf\",\"base64\":\"" + "A".repeat(4_000) + "\"}";
    String longFile = "{\"base64\":\"" + "A".repeat(MAX_PUBLISHED_INPUT_LENGTH * 2) + "\"}";
    String others = "{\"nested\":{\"base64\":\"CCCC\"},\"base64x\":\"DDDD\"}";

    assertEquals("{\"title\":\"report.pdf\",\"base64\":\"[file, 3 KB]\"}", redactBase64ForPublication(shortFile));
    assertEquals("{\"base64\":\"[file, 96 KB]\"}", redactBase64ForPublication(longFile));
    assertEquals(others, redactBase64ForPublication(others));
  }

  /**
   * A short input that isn't a JSON object is kept, a long one is replaced by
   * the note so that no multi-MB value is published.
   */
  @Test
  void redactBase64BoundsAnInputThatIsNotAJsonObject() {
    String shortBroken = "{\"image_base64\":\"AAAA";
    String longBroken = "{\"image_base64\":\"" + "A".repeat(MAX_PUBLISHED_INPUT_LENGTH + 1);
    String longArray = "[\"image_base64\"," + "\"A\",".repeat(MAX_PUBLISHED_INPUT_LENGTH) + "1]";

    assertEquals(shortBroken, redactBase64ForPublication(shortBroken));
    assertEquals(ARGUMENTS_TOO_LARGE_NOTE, redactBase64ForPublication(longBroken));
    assertEquals(ARGUMENTS_TOO_LARGE_NOTE, redactBase64ForPublication(longArray));
    assertEquals("{\"_note\":\"[arguments too large to show]\"}", ARGUMENTS_TOO_LARGE_NOTE);
  }

}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;

import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.mcp.server.tool.model.UploadLinkModel;
import io.meeds.mcp.server.tool.model.UploadStatusModel;
import io.meeds.mcp.server.tool.util.UploadToolUtils;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins {@link UploadMcpTool} with a mocked upload registry: the size cap and
 * its configuration, the file-name check, the token issued for the current
 * user, the shape of the link, and the owner-checked status read.
 */
class UploadMcpToolTest {

  private static final String USERNAME       = "john";

  private static final String DOMAIN         = "https://tribe.example.org";

  private static final String DOMAIN_PROPERTY = "gatein.email.domain.url";

  private static final String TOKEN          = "t0k3n-AbC_xyz";

  private static final long   MB             = 1024L * 1024L;

  private final UploadService uploadService  = mock(UploadService.class);

  private String              previousDomain;

  /**
   * Sets the current user and the platform's public base URL.
   */
  @BeforeEach
  void setUp() {
    ConversationState.setCurrent(new ConversationState(new Identity(USERNAME)));
    previousDomain = System.getProperty(DOMAIN_PROPERTY);
    System.setProperty(DOMAIN_PROPERTY, DOMAIN);
  }

  /**
   * Clears the current user and restores the base URL.
   */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
    if (previousDomain == null) {
      System.clearProperty(DOMAIN_PROPERTY);
    } else {
      System.setProperty(DOMAIN_PROPERTY, previousDomain);
    }
  }

  /**
   * A size over the configured cap is refused before any token is issued, and
   * a size of exactly the cap is accepted. Mutants: the comparison made
   * {@code >=}, or the check removed.
   */
  @Test
  void startUploadRefusesASizeOverTheCap() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "1");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> tool.startUpload("deck.pptx", MB + 1));
    assertTrue(refusal.getMessage().contains("1 MB"), refusal.getMessage());
    verify(uploadService, never()).createUploadToken(anyString(), anyString(), anyLong());

    when(uploadService.createUploadToken(eq(USERNAME), anyString(), eq(MB))).thenReturn(TOKEN);
    assertEquals(MB, tool.startUpload("deck.pptx", MB).size());
  }

  /**
   * A missing, zero or negative size is refused before any token is issued.
   */
  @Test
  void startUploadRefusesANonPositiveSize() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");

    assertThrows(IllegalArgumentException.class, () -> tool.startUpload("a.png", null));
    assertThrows(IllegalArgumentException.class, () -> tool.startUpload("a.png", 0L));
    assertThrows(IllegalArgumentException.class, () -> tool.startUpload("a.png", -5L));
    verify(uploadService, never()).createUploadToken(anyString(), anyString(), anyLong());
  }

  /**
   * The cap is the configured value in MB, and falls back to 200 MB when the
   * value is blank, not a number or not positive. Mutant: the fallback
   * dropped, so a "0" property refuses every upload.
   */
  @Test
  void theCapFallsBackTo200MbOnAnUnusableValue() {
    when(uploadService.createUploadToken(eq(USERNAME), anyString(), anyLong())).thenReturn(TOKEN);
    for (String value : new String[] { null, "", "abc", "0", "-3" }) {
      UploadMcpTool tool = new UploadMcpTool(uploadService, value);
      assertEquals(200 * MB, tool.startUpload("a.png", 200 * MB).size(), String.valueOf(value));
      assertThrows(IllegalArgumentException.class, () -> tool.startUpload("a.png", 200 * MB + 1), String.valueOf(value));
    }
    UploadMcpTool five = new UploadMcpTool(uploadService, " 5 ");
    assertThrows(IllegalArgumentException.class, () -> five.startUpload("a.png", 5 * MB + 1));
  }

  /**
   * The cap is read from {@code meeds.mcp.upload.maxSizeInMB}, falling back to
   * the platform's upload limit {@code exo.uploadLimit}, the limit the token's
   * size replaces, then to 200. Mutant: the fallback pointed at a property the
   * platform does not set.
   *
   * @throws Exception never
   */
  @Test
  void theCapFallsBackToThePlatformUploadLimit() throws Exception {
    Value value = UploadMcpTool.class.getConstructor(UploadService.class, String.class)
                                     .getParameters()[1].getAnnotation(Value.class);

    assertEquals("${meeds.mcp.upload.maxSizeInMB:${exo.uploadLimit:200}}", value.value());
  }

  /**
   * A name holding a path separator, a quote, a control character, or that is
   * blank, a dot entry or too long is refused before any token is issued: it
   * goes into the shell command the agent runs. Mutant: the forbidden
   * characters check removed.
   */
  @Test
  void startUploadRefusesAnUnsafeName() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");

    for (String name : new String[] { "dir/a.png", "dir\\a.png", "it's.png", "a\"b.png", "a\nb.png", "a\u0000.png", "",
        "  ", null, ".", "..", "x".repeat(256) }) {
      assertThrows(IllegalArgumentException.class, () -> tool.startUpload(name, 10L), String.valueOf(name));
    }
    verify(uploadService, never()).createUploadToken(anyString(), anyString(), anyLong());
  }

  /**
   * The token is issued for the current user, a fresh UUID upload id and the
   * declared size, and the link carries that id, the platform's upload
   * endpoint and a curl command holding the token in its header, the
   * placeholder path and the file name. Mutants: another user passed; the
   * declared size replaced by the cap.
   */
  @Test
  void startUploadIssuesTheTokenForTheCurrentUserAndReturnsTheLink() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");
    ArgumentCaptor<String> uploadId = ArgumentCaptor.forClass(String.class);
    when(uploadService.createUploadToken(eq(USERNAME), uploadId.capture(), eq(1234L))).thenReturn(TOKEN);

    UploadLinkModel link = tool.startUpload(" chart (v2).png ", 1234L);

    assertEquals(uploadId.getValue(), link.uploadId());
    assertEquals(uploadId.getValue(), UUID.fromString(link.uploadId()).toString());
    assertEquals(DOMAIN + "/portal/upload?action=upload&uploadId=" + link.uploadId(), link.uploadUrl());
    assertEquals("curl -sS -H 'Authorization: Bearer " + TOKEN + "' -F 'file=@<path-to-file>;filename=\"chart (v2).png\"' '"
        + link.uploadUrl() + "'", link.curlCommand());
    assertEquals(1234L, link.size());
    assertEquals(UploadService.DEFAULT_UPLOAD_TOKEN_VALIDITY_SECONDS, link.expiresInSeconds());
    assertTrue(link.nextStep().contains("upload_id"), link.nextStep());
    assertTrue(link.fallback().contains("image_base64"), link.fallback());
  }

  /**
   * The command, run by a real shell and a real curl against a local server
   * standing for the platform, sends what the upload endpoint expects: a POST
   * to the link's path and query, the token as a bearer header, a
   * {@code Content-Length}, and one multipart file part carrying the file's
   * bytes under the given name, quoting included. Skipped where no
   * {@code sh} or {@code curl} is installed. The platform's own handling of
   * that request is portal's to pin.
   *
   * @param folder a temporary folder
   * @throws Exception when the server, the file or the process fails
   */
  @Test
  void theCurlCommandSendsTheFileAsTheUploadEndpointExpects(@TempDir Path folder) throws Exception {
    Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "no /bin/sh");
    Assumptions.assumeTrue(hasCurl(), "no curl");
    Map<String, String> received = new ConcurrentHashMap<>();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      received.put("method", exchange.getRequestMethod());
      received.put("uri", exchange.getRequestURI().toString());
      received.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      received.put("contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
      received.put("contentLength", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Length")));
      body.write(exchange.getRequestBody().readAllBytes());
      byte[] answer = "{\"status\":\"uploaded\",\"size\":5}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, answer.length);
      exchange.getResponseBody().write(answer);
      exchange.close();
    });
    server.start();
    try {
      System.setProperty(DOMAIN_PROPERTY, "http://127.0.0.1:" + server.getAddress().getPort());
      UploadMcpTool tool = new UploadMcpTool(uploadService, "200");
      when(uploadService.createUploadToken(eq(USERNAME), anyString(), eq(5L))).thenReturn(TOKEN);
      Path file = folder.resolve("deck.bin");
      Files.write(file, "HELLO".getBytes(StandardCharsets.UTF_8));
      UploadLinkModel link = tool.startUpload("chart (v2) & co.png", 5L);

      ProcessBuilder shell = new ProcessBuilder("/bin/sh", "-c", link.curlCommand().replace("<path-to-file>", file.toString()));
      for (String proxy : new String[] { "http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY" }) {
        shell.environment().remove(proxy);
      }
      shell.environment().put("NO_PROXY", "*");
      shell.environment().put("no_proxy", "*");
      Process curl = shell.redirectErrorStream(true).start();
      String output = new String(curl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(curl.waitFor(30, TimeUnit.SECONDS), "curl did not finish");
      assertEquals(0, curl.exitValue(), output);

      assertEquals("POST", received.get("method"));
      assertEquals("/portal/upload?action=upload&uploadId=" + link.uploadId(), received.get("uri"));
      assertEquals("Bearer " + TOKEN, received.get("authorization"));
      assertTrue(received.get("contentType").startsWith("multipart/form-data"), received.get("contentType"));
      assertEquals(String.valueOf(body.size()), received.get("contentLength"));
      String sent = body.toString(StandardCharsets.UTF_8);
      assertTrue(sent.contains("name=\"file\"; filename=\"chart (v2) & co.png\""), sent);
      assertTrue(sent.contains("HELLO"), sent);
      assertTrue(output.contains("\"status\":\"uploaded\""), output);
    } finally {
      server.stop(0);
    }
  }

  /**
   * @return true when a {@code curl} runs on this machine
   */
  private static boolean hasCurl() {
    try {
      Process probe = new ProcessBuilder("curl", "--version").redirectErrorStream(true).start();
      probe.getInputStream().readAllBytes();
      return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Two links never share an upload id.
   */
  @Test
  void eachLinkHasItsOwnUploadId() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");
    when(uploadService.createUploadToken(eq(USERNAME), anyString(), anyLong())).thenReturn(TOKEN);

    String first = tool.startUpload("a.png", 10L).uploadId();
    String second = tool.startUpload("a.png", 10L).uploadId();

    assertTrue(!first.equals(second), first);
  }

  /**
   * The link serializes with the snake_case names the tool description uses.
   */
  @Test
  void theLinkSerializesInSnakeCase() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");
    when(uploadService.createUploadToken(eq(USERNAME), anyString(), anyLong())).thenReturn(TOKEN);

    JsonNode json = JsonMapper.builder().build().valueToTree(tool.startUpload("a.png", 10L));

    for (String field : new String[] { "upload_id", "upload_url", "curl_command", "size", "expires_in_seconds", "next_step",
        "fallback" }) {
      assertTrue(json.has(field), field + " in " + json);
    }
  }

  /**
   * The status is read through the owner-checked accessor with the current
   * user, never through the unchecked one: an id this node holds for no
   * upload of the user's, whether unknown or another user's, is reported
   * unknown. Mutant: the one-argument {@code getUploadResource(id)} used.
   */
  @Test
  void getUploadIsOwnerCheckedAndReportsAnUnknownId() {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");
    UploadResource someoneElses = uploaded("other-id", null);
    when(uploadService.getUploadResource("other-id")).thenReturn(someoneElses);

    UploadStatusModel status = tool.getUpload(" other-id ");

    assertEquals("unknown", status.status());
    assertEquals("other-id", status.uploadId());
    assertEquals(UploadToolUtils.UNKNOWN_UPLOAD_MESSAGE, status.message());
    assertNull(status.size());
    verify(uploadService).getUploadResource("other-id", USERNAME);
    verify(uploadService, never()).getUploadResource("other-id");
    assertThrows(IllegalArgumentException.class, () -> tool.getUpload(" "));
  }

  /**
   * An uploaded file reports the stored file's size, name and type; a
   * failed or in-progress one its status and the bytes counted so far.
   *
   * @param folder a temporary folder
   * @throws Exception when the file can't be written
   */
  @Test
  void getUploadReportsEachStatus(@TempDir Path folder) throws Exception {
    UploadMcpTool tool = new UploadMcpTool(uploadService, "200");
    Path file = folder.resolve("stored.bin");
    Files.write(file, new byte[42]);
    when(uploadService.getUploadResource("done", USERNAME)).thenReturn(uploaded("done", file.toString()));
    UploadResource failed = new UploadResource("failed", "x.png");
    failed.setStatus(UploadResource.FAILED_STATUS);
    when(uploadService.getUploadResource("failed", USERNAME)).thenReturn(failed);
    UploadResource receiving = new UploadResource("receiving", "x.png");
    receiving.setStatus(UploadResource.UPLOADING_STATUS);
    receiving.addUploadedBytes(7);
    when(uploadService.getUploadResource("receiving", USERNAME)).thenReturn(receiving);

    UploadStatusModel done = tool.getUpload("done");
    assertEquals("uploaded", done.status());
    assertEquals(42L, done.size());
    assertEquals("chart.png", done.fileName());
    assertEquals("image/png", done.mimeType());
    assertNull(done.message());

    assertEquals("failed", tool.getUpload("failed").status());
    UploadStatusModel inProgress = tool.getUpload("receiving");
    assertEquals("uploading", inProgress.status());
    assertEquals(7L, inProgress.size());
  }

  /**
   * @param uploadId the upload id
   * @param storeLocation the stored file, or null
   * @return an uploaded resource named chart.png of type image/png
   */
  private static UploadResource uploaded(String uploadId, String storeLocation) {
    UploadResource resource = new UploadResource(uploadId, "chart.png");
    resource.setMimeType("image/png");
    resource.setStoreLocation(storeLocation);
    resource.setStatus(UploadResource.UPLOADED_STATUS);
    return resource;
  }

}

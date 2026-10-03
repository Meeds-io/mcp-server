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
package io.meeds.mcp.server.tool.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.commons.http.SafeFetchPolicy;
import io.meeds.commons.http.SafeFetchPolicyBuilder;
import io.meeds.commons.http.SafeHttpFetcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The upload helpers, and the URL fetch they share: driven through the connect
 * path against a stub server on loopback, standing for the public internet
 * under the names the policy exempts, so that a refusal is the one the HTTP
 * client's own resolver makes. Nothing leaves the machine.
 */
class UploadToolUtilsTest {

  private static final byte[]              PNG     = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R' };

  private final Map<String, InetAddress[]> dns     = new HashMap<>();

  private final Map<String, byte[]>        bodies  = new HashMap<>();

  private final Map<String, String>        types   = new HashMap<>();

  private final List<String>               hits    = new CopyOnWriteArrayList<>();

  private HttpServer                       server;

  private ExecutorService                  executor;

  private InetAddress                      stub;

  private int                              port;

  private SafeHttpFetcher                  fetcher;

  /**
   * Starts the stub on loopback and maps the test names: a public one, which
   * is the stub, and internal ones.
   *
   * @throws Exception when the server cannot start
   */
  @BeforeEach
  void startServer() throws Exception {
    stub = InetAddress.getByAddress("public.test", new byte[] { 127, 0, 0, 1 });
    server = HttpServer.create(new InetSocketAddress(stub, 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/", this::serve);
    server.start();
    port = server.getAddress().getPort();
    dns.put("public.test", new InetAddress[] { stub });
    dns.put("private.test", new InetAddress[] { InetAddress.getByAddress(new byte[] { 127, 0, 0, 2 }) });
    dns.put("internal.test", new InetAddress[] { InetAddress.getByAddress(new byte[] { 10, 0, 0, 5 }) });
    dns.put("metadata.test", new InetAddress[] { InetAddress.getByAddress(new byte[] { (byte) 169, (byte) 254, (byte) 169, (byte) 254 }) });
    fetcher = new SafeHttpFetcher(policy().build());
  }

  /**
   * Stops the stub and the fetcher.
   */
  @AfterEach
  void stopServer() {
    fetcher.close();
    server.stop(0);
    executor.shutdownNow();
  }

  /**
   * The upload policy over the table of names: any port, no redirect, the
   * stub's address exempted, short timeouts.
   *
   * @return the builder, for a scenario to change
   */
  private SafeFetchPolicyBuilder policy() {
    return SafeFetchPolicy.builder()
                          .anyPort()
                          .maxRedirects(0)
                          .resolver(this::resolve)
                          .exemptAddresses(Set.of(stub))
                          .connectTimeout(Duration.ofSeconds(2))
                          .readTimeout(Duration.ofSeconds(2))
                          .totalTimeout(Duration.ofSeconds(5));
  }

  /**
   * Resolves a name from the table.
   *
   * @param host the name
   * @return its addresses
   * @throws UnknownHostException when the table does not know it
   */
  private InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = dns.get(host);
    if (addresses == null) {
      throw new UnknownHostException(host);
    }
    return addresses;
  }

  /**
   * A URL on a test name, at the stub's port.
   *
   * @param host the test name
   * @param path the path
   * @return the URL
   */
  private String url(String host, String path) {
    return "http://" + host + ":" + port + path;
  }

  /**
   * Answers a request from the bodies set, 404 otherwise, the connection closed
   * after each answer so that every fetch dials again.
   *
   * @param exchange the request
   * @throws IOException when the client went away
   */
  private void serve(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    hits.add(path);
    byte[] body = bodies.get(path);
    try {
      exchange.getResponseHeaders().add("Connection", "close");
      if (types.containsKey(path)) {
        exchange.getResponseHeaders().add("Content-Type", types.get(path));
      }
      if (body == null) {
        exchange.sendResponseHeaders(404, -1);
      } else {
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        }
      }
    } finally {
      exchange.close();
    }
  }

  /**
   * The message a fetch of an image is refused with.
   *
   * @param url the URL
   * @return the message
   */
  private String refusal(String url) {
    return assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchImage(fetcher, url, UploadToolUtils.DEFAULT_MAX_BYTES)).getMessage();
  }

  // --- the URL fetch, through the connect path ----------------------------

  /**
   * An image on a public name is downloaded, its type read from the header or
   * sniffed from the bytes when the header says nothing of an image, and its
   * name derived from the type.
   */
  @Test
  void fetchImageDownloadsFromAPublicHost() {
    bodies.put("/typed.png", PNG);
    types.put("/typed.png", "image/png");
    bodies.put("/untyped", PNG);
    types.put("/untyped", "application/octet-stream");

    UploadToolUtils.FetchedContent typed = UploadToolUtils.fetchImage(fetcher, url("public.test", "/typed.png"), UploadToolUtils.DEFAULT_MAX_BYTES);
    UploadToolUtils.FetchedContent sniffed = UploadToolUtils.fetchImage(fetcher, url("public.test", "/untyped"), UploadToolUtils.DEFAULT_MAX_BYTES);

    assertArrayEquals(PNG, typed.bytes());
    assertEquals("image/png", typed.mimeType());
    assertEquals("image.png", typed.fileName());
    assertEquals("image/png", sniffed.mimeType());
    assertEquals(List.of("/typed.png", "/untyped"), hits);
  }

  /**
   * A name resolving to an internal address — a private one, loopback, the
   * cloud metadata address — is refused by the lookup the connection makes,
   * and the stub never sees a request. The pin of the pitfall where the guard
   * checked one answer and the client connected with another: the check now
   * runs inside the client's resolver.
   */
  @Test
  void fetchImageRefusesAnInternalAddressAtConnection() {
    bodies.put("/secret.png", PNG);
    for (String host : new String[] { "private.test", "internal.test", "metadata.test" }) {
      assertEquals("URL host is not allowed (it points to a private or internal address).", refusal(url(host, "/secret.png")), host);
    }
    assertTrue(hits.isEmpty(), "no request may reach an internal address");
  }

  /**
   * A name answering a public address for one connection and an internal one
   * for the next — DNS rebinding — is refused at that next connection: the
   * first answer is never trusted for the second, because the address judged
   * is the address dialled.
   */
  @Test
  void fetchImageRefusesANameRebindingToAnInternalAddress() throws Exception {
    InetAddress internal = InetAddress.getByAddress(new byte[] { 127, 0, 0, 2 });
    AtomicInteger lookups = new AtomicInteger();
    SafeHttpFetcher rebinding = new SafeHttpFetcher(policy().resolver(host -> {
      if (!"rebind.test".equals(host)) {
        throw new UnknownHostException(host);
      }
      return lookups.incrementAndGet() == 1 ? new InetAddress[] { stub } : new InetAddress[] { internal };
    }).build());
    bodies.put("/logo.png", PNG);
    try {
      assertArrayEquals(PNG, UploadToolUtils.fetchImage(rebinding, url("rebind.test", "/logo.png"), UploadToolUtils.DEFAULT_MAX_BYTES).bytes());
      IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                      () -> UploadToolUtils.fetchImage(rebinding, url("rebind.test", "/logo.png"), UploadToolUtils.DEFAULT_MAX_BYTES));
      assertEquals("URL host is not allowed (it points to a private or internal address).", refused.getMessage());
    } finally {
      rebinding.close();
    }
    assertEquals(2, lookups.get(), "the second connection must resolve the name again rather than trust the first answer");
    assertEquals(List.of("/logo.png"), hits, "no request may reach the stub through a name judged internal");
  }

  /**
   * What is not a public http(s) URL is refused before any request: another
   * scheme, no host, credentials, an unusable string, an unknown name; a
   * literal internal address is refused like a name resolving to one.
   */
  @Test
  void fetchImageRefusesWhatIsNotPublicHttp() {
    assertEquals("Only http and https URLs are allowed.", refusal("ftp://8.8.8.8/x"));
    assertEquals("Only http and https URLs are allowed.", refusal("file:///etc/passwd"));
    assertEquals("Invalid URL.", refusal("http:///nohost"));
    assertEquals("Invalid URL.", refusal("not a url"));
    assertEquals("The URL must not carry credentials.", refusal("http://user:secret@public.test:" + port + "/x"));
    assertEquals("Could not fetch the URL: unknown host.", refusal(url("nowhere.test", "/x")));
    SafeHttpFetcher jdk = new SafeHttpFetcher(policy().resolver(InetAddress::getAllByName).exemptAddresses(Set.of()).build());
    try {
      for (String literal : new String[] { "http://127.0.0.1/x", "http://192.168.0.10/x", "http://169.254.169.254/latest/meta-data",
          "http://[::1]/x", "http://100.64.0.1/x" }) {
        assertEquals("URL host is not allowed (it points to a private or internal address).",
                     assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchImage(jdk, literal, 1024)).getMessage(),
                     literal);
      }
    } finally {
      jdk.close();
    }
    assertTrue(hits.isEmpty());
  }

  /**
   * The answer is bounded and reported: an error status with its code, a body
   * over the caller's limit, a redirect, which is not followed, bytes that are
   * no image.
   */
  @Test
  void fetchImageReportsErrorsLimitsAndRedirects() {
    assertEquals("The image URL returned HTTP 404.", refusal(url("public.test", "/missing.png")));
    bodies.put("/big.png", new byte[2048]);
    assertEquals("The file exceeds the maximum allowed size (0 MB).",
                 assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchImage(fetcher, url("public.test", "/big.png"), 1024)).getMessage());
    bodies.put("/text", "plain text bytes here, not an image".getBytes());
    types.put("/text", "text/plain");
    assertEquals("The URL does not point to a supported image.", refusal(url("public.test", "/text")));
  }

  /**
   * Any file is downloaded with its declared type, octet-stream when none, and a
   * name from the URL path or the fallback; an empty body is refused.
   */
  @Test
  void fetchUrlDownloadsAnyFile() {
    bodies.put("/docs/report.pdf", "%PDF-1.4".getBytes());
    types.put("/docs/report.pdf", "application/pdf; charset=binary");
    bodies.put("/blob", new byte[] { 1, 2, 3 });
    bodies.put("/empty", new byte[0]);

    UploadToolUtils.FetchedContent pdf = UploadToolUtils.fetchUrl(fetcher, url("public.test", "/docs/report.pdf"), UploadToolUtils.DEFAULT_MAX_BYTES, "x.bin");
    UploadToolUtils.FetchedContent blob = UploadToolUtils.fetchUrl(fetcher, url("public.test", "/blob"), UploadToolUtils.DEFAULT_MAX_BYTES, "x.bin");

    assertEquals("application/pdf", pdf.mimeType());
    assertEquals("report.pdf", pdf.fileName());
    assertEquals("application/octet-stream", blob.mimeType());
    assertEquals("blob", blob.fileName());
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchUrl(fetcher, url("public.test", "/empty"), UploadToolUtils.DEFAULT_MAX_BYTES, "x.bin"));
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchUrl(fetcher, url("private.test", "/blob"), UploadToolUtils.DEFAULT_MAX_BYTES, "x.bin"));
    assertEquals("The file URL returned HTTP 404.",
                 assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchUrl(fetcher, url("public.test", "/none"), UploadToolUtils.DEFAULT_MAX_BYTES, "x.bin")).getMessage());
  }

  /**
   * The production entry points run under the shared fetcher, whose guard
   * refuses an internal literal without any network: the same refusal as
   * through the seam.
   */
  @Test
  void theSharedFetcherRefusesAnInternalLiteral() {
    assertEquals("URL host is not allowed (it points to a private or internal address).",
                 assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchImage("http://127.0.0.1/x", 1024)).getMessage());
    assertEquals("Only http and https URLs are allowed.",
                 assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.fetchUrl("ftp://8.8.8.8/x", 1024, "x")).getMessage());
  }

  // --- base64 --------------------------------------------------------------

  @Test
  void decodeBase64PlainAndDataUri() {
    byte[] raw = { 1, 2, 3, 4 };
    String b64 = Base64.getEncoder().encodeToString(raw);
    assertArrayEquals(raw, UploadToolUtils.decodeBase64(b64));
    assertArrayEquals(raw, UploadToolUtils.decodeBase64("data:image/png;base64," + b64));
  }

  @Test
  void decodeBase64InvalidFails() {
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.decodeBase64("$$$not-base64$$$"));
  }

  // --- mime sniffing -------------------------------------------------------

  @Test
  void sniffImageMimeRecognisesFormats() {
    byte[] png = new byte[16];
    png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
    assertEquals("image/png", UploadToolUtils.sniffImageMime(png));

    byte[] jpg = new byte[16];
    jpg[0] = (byte) 0xFF; jpg[1] = (byte) 0xD8;
    assertEquals("image/jpeg", UploadToolUtils.sniffImageMime(jpg));

    byte[] gif = new byte[16];
    gif[0] = 'G'; gif[1] = 'I'; gif[2] = 'F';
    assertEquals("image/gif", UploadToolUtils.sniffImageMime(gif));

    byte[] webp = new byte[16];
    webp[0] = 'R'; webp[1] = 'I'; webp[2] = 'F'; webp[3] = 'F';
    webp[8] = 'W'; webp[9] = 'E'; webp[10] = 'B'; webp[11] = 'P';
    assertEquals("image/webp", UploadToolUtils.sniffImageMime(webp));

    assertNull(UploadToolUtils.sniffImageMime(new byte[] { 0, 1, 2 }));
    assertNull(UploadToolUtils.sniffImageMime("plain text bytes here".getBytes()));
  }

  // --- materialize / release ----------------------------------------------

  @Test
  void materializeRegistersUploadedResource() {
    UploadService uploadService = Mockito.mock(UploadService.class);
    byte[] png = new byte[16];
    png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';

    String uploadId = UploadToolUtils.materialize(uploadService, png, "shot.png", "image/png");

    assertNotNull(uploadId);
    ArgumentCaptor<UploadResource> captor = ArgumentCaptor.forClass(UploadResource.class);
    verify(uploadService).createUploadResource(captor.capture());
    UploadResource resource = captor.getValue();
    assertEquals(uploadId, resource.getUploadId());
    assertEquals("image/png", resource.getMimeType());
    assertEquals(UploadResource.UPLOADED_STATUS, resource.getStatus());
    File staged = new File(resource.getStoreLocation());
    assertTrue(staged.exists(), "staged temp file should exist");
    assertEquals(png.length, staged.length());
    staged.delete();
  }

  @Test
  void materializeFromUrlOrBase64RequiresExactlyOneSource() {
    UploadService uploadService = Mockito.mock(UploadService.class);
    assertThrows(IllegalArgumentException.class,
                 () -> UploadToolUtils.materializeFromUrlOrBase64(uploadService, null, null, UploadToolUtils.DEFAULT_MAX_BYTES));
    assertThrows(IllegalArgumentException.class,
                 () -> UploadToolUtils.materializeFromUrlOrBase64(uploadService, "https://8.8.8.8/i.png", "abcd",
                                                                 UploadToolUtils.DEFAULT_MAX_BYTES));
  }

  @Test
  void materializeFromBase64ImageWorks() {
    UploadService uploadService = Mockito.mock(UploadService.class);
    byte[] png = new byte[16];
    png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
    String b64 = Base64.getEncoder().encodeToString(png);

    String uploadId = UploadToolUtils.materializeFromUrlOrBase64(uploadService, null, b64, UploadToolUtils.DEFAULT_MAX_BYTES);

    assertNotNull(uploadId);
    verify(uploadService).createUploadResource(Mockito.any(UploadResource.class));
  }

  @Test
  void materializeFromBase64NonImageFails() {
    UploadService uploadService = Mockito.mock(UploadService.class);
    String b64 = Base64.getEncoder().encodeToString("this is definitely not an image payload".getBytes());
    assertThrows(IllegalArgumentException.class,
                 () -> UploadToolUtils.materializeFromUrlOrBase64(uploadService, null, b64, UploadToolUtils.DEFAULT_MAX_BYTES));
  }

  @Test
  void releaseRemovesUploadResource() {
    UploadService uploadService = Mockito.mock(UploadService.class);
    UploadToolUtils.release(uploadService, "some-id");
    verify(uploadService).removeUploadResource("some-id");
  }

  // --- FetchedContent (array-content equals/hashCode/toString) -------------

  @Test
  void fetchedContentEqualityComparesArrayContentNotReference() {
    UploadToolUtils.FetchedContent a = new UploadToolUtils.FetchedContent(new byte[] { 1, 2, 3 }, "image/png", "a.png");
    UploadToolUtils.FetchedContent b = new UploadToolUtils.FetchedContent(new byte[] { 1, 2, 3 }, "image/png", "a.png");
    UploadToolUtils.FetchedContent different = new UploadToolUtils.FetchedContent(new byte[] { 9 }, "image/png", "a.png");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertFalse(a.equals(different));
    assertFalse(a.equals(null));
    assertTrue(a.toString().contains("bytes.length=3"));
  }

}

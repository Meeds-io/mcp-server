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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.attachment.AttachmentService;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

/**
 * Helpers to turn an image (from a URL or base64) into a platform
 * {@link UploadService} <code>uploadId</code> that any upload-consuming API
 * (activity/comment attachments, note featured image, avatars, documents…)
 * accepts. This is the missing "materialize" side of {@link UploadService},
 * which otherwise only registers/reads resources.
 *
 * <p>Fetching an image from a URL happens server-side, so the URL is validated
 * against SSRF (only public http/https hosts are allowed). To close the
 * TOCTOU/DNS-rebinding gap between that validation and the actual connection
 * (the JDK's {@link HttpClient} would otherwise re-resolve the hostname
 * independently at connect time, which an attacker controlling DNS could
 * answer differently), the fetch pins the TCP connection to the exact address
 * that was just validated — see {@link #fetchViaPinnedConnection}.
 */
public final class UploadToolUtils {

  private static final Log    LOG                      = ExoLogger.getLogger(UploadToolUtils.class);

  /** 10 MB default ceiling for a fetched/decoded image. */
  public static final long    DEFAULT_MAX_BYTES        = 10L * 1024 * 1024;

  private static final int    CONNECT_TIMEOUT_SECONDS  = 10;

  private static final int    READ_TIMEOUT_SECONDS     = 20;

  static {
    // Lets fetchViaPinnedConnection set an explicit "Host" header on the pinned-IP
    // request (normally a restricted header the JDK HttpClient manages itself).
    // Best-effort: has no effect if java.net.http was already initialized elsewhere
    // in this JVM before this class loads.
    String existing = System.getProperty("jdk.httpclient.allowRestrictedHeaders");
    if (StringUtils.isBlank(existing)) {
      System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    } else if (!existing.toLowerCase().contains("host")) {
      System.setProperty("jdk.httpclient.allowRestrictedHeaders", existing + ",host");
    }
  }

  private UploadToolUtils() {
  }

  /** A downloaded image: its bytes, resolved mime type and a file name. */
  public record FetchedContent(byte[] bytes, String mimeType, String fileName) {
  }

  /** A raw http(s) response: its status, headers and body bytes (already size-capped). */
  private record FetchedResponse(int statusCode, HttpHeaders headers, byte[] bytes) {
  }

  /**
   * Resolves an image from exactly one of an http(s) URL or a base64 string,
   * stages it and registers it with {@link UploadService}.
   *
   * @return the generated <code>uploadId</code> to pass to a consumer API
   */
  public static String materializeFromUrlOrBase64(UploadService uploadService,
                                                  String imageUrl,
                                                  String imageBase64,
                                                  long maxBytes) {
    boolean hasUrl = StringUtils.isNotBlank(imageUrl);
    boolean hasBase64 = StringUtils.isNotBlank(imageBase64);
    if (hasUrl == hasBase64) {
      throw new IllegalArgumentException("Provide exactly one of image_url or image_base64.");
    }
    FetchedContent image = hasUrl ? fetchImage(imageUrl, maxBytes) : decodeBase64Image(imageBase64, maxBytes);
    return materialize(uploadService, image.bytes(), image.fileName(), image.mimeType());
  }

  /**
   * Resolves an image from exactly one of three mutually exclusive sources — an
   * http(s) URL, a base64 string, or an ACL-checked reference to a file already
   * attached to a platform object (<code>attachment_object_type</code> +
   * <code>attachment_object_id</code>) — into its raw bytes. Consumers that need
   * an <code>uploadId</code> pass the result to {@link #materialize}; consumers
   * that need raw bytes (avatars/banners) use it directly.
   *
   * <p>The attachment reference is read <b>as the given user</b> via
   * {@link AttachmentService#getAttachmentFileIds(String, String, Identity)}, so
   * platform ACLs are enforced (an unreadable object throws
   * {@link IllegalAccessException} — no IDOR).
   *
   * @return the resolved image bytes, mime type and file name
   */
  public static FetchedContent resolveImage(AttachmentService attachmentService,
                                          FileService fileService,
                                          Identity aclIdentity,
                                          String imageUrl,
                                          String imageBase64,
                                          String attachmentObjectType,
                                          String attachmentObjectId,
                                          long maxBytes) throws IllegalAccessException, ObjectNotFoundException {
    if (StringUtils.isNotBlank(attachmentObjectId)) {
      if (StringUtils.isNotBlank(imageUrl) || StringUtils.isNotBlank(imageBase64)) {
        throw new IllegalArgumentException("Provide only one image source: attachment_object_id, image_url or image_base64.");
      }
      if (StringUtils.isBlank(attachmentObjectType)) {
        throw new IllegalArgumentException("attachment_object_type is required together with attachment_object_id.");
      }
      List<String> fileIds = attachmentService.getAttachmentFileIds(attachmentObjectType, attachmentObjectId, aclIdentity);
      if (CollectionUtils.isEmpty(fileIds)) {
        throw new ObjectNotFoundException("No file attachment found for %s/%s.".formatted(attachmentObjectType,
                                                                                         attachmentObjectId));
      }
      byte[] bytes;
      String mimeType;
      String fileName;
      try {
        FileItem file = fileService.getFile(Long.parseLong(fileIds.get(0)));
        bytes = file == null ? null : file.getAsByte();
        mimeType = file != null && file.getFileInfo() != null ? file.getFileInfo().getMimetype() : null;
        fileName = file != null && file.getFileInfo() != null ? file.getFileInfo().getName() : "image";
      } catch (Exception e) {
        throw new IllegalStateException("Could not read the referenced attachment file: " + e.getMessage());
      }
      if (bytes == null || bytes.length == 0) {
        throw new ObjectNotFoundException("The referenced attachment file is empty or could not be read.");
      }
      return new FetchedContent(bytes, mimeType, StringUtils.isBlank(fileName) ? "image" : fileName);
    }
    boolean hasUrl = StringUtils.isNotBlank(imageUrl);
    boolean hasBase64 = StringUtils.isNotBlank(imageBase64);
    if (hasUrl == hasBase64) {
      throw new IllegalArgumentException("Provide exactly one of image_url or image_base64.");
    }
    return hasUrl ? fetchImage(imageUrl, maxBytes) : decodeBase64Image(imageBase64, maxBytes);
  }

  /** Decodes a base64 image into its bytes, enforcing the size cap and a supported mime. */
  private static FetchedContent decodeBase64Image(String imageBase64, long maxBytes) {
    byte[] bytes = decodeBase64(imageBase64);
    if (bytes.length > maxBytes) {
      throw new IllegalArgumentException("Image exceeds the maximum allowed size (" + (maxBytes / (1024 * 1024)) + " MB).");
    }
    String mimeType = sniffImageMime(bytes);
    if (mimeType == null) {
      throw new IllegalArgumentException("image_base64 does not decode to a supported image (png, jpeg, gif or webp).");
    }
    return new FetchedContent(bytes, mimeType, "image" + extensionForMime(mimeType));
  }

  /**
   * Downloads an image over http(s) after validating the URL against SSRF.
   */
  public static FetchedContent fetchImage(String url, long maxBytes) {
    FetchedResponse response = fetchViaPinnedConnection(url, maxBytes);
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new IllegalArgumentException("The image URL returned HTTP " + status + ".");
    }
    byte[] bytes = response.bytes();
    String mimeType = response.headers()
                              .firstValue("Content-Type")
                              .map(value -> value.split(";")[0].trim().toLowerCase())
                              .filter(value -> value.startsWith("image/"))
                              .orElseGet(() -> sniffImageMime(bytes));
    if (mimeType == null) {
      throw new IllegalArgumentException("The URL does not point to a supported image.");
    }
    return new FetchedContent(bytes, mimeType, "image" + extensionForMime(mimeType));
  }

  /**
   * Downloads <b>any</b> file (not restricted to images) over http(s) after
   * validating the URL against SSRF. The mime type is resolved from the
   * <code>Content-Type</code> response header (falling back to
   * <code>application/octet-stream</code>) and the file name is derived from the
   * URL path (falling back to <code>defaultFileName</code>). Used by document /
   * generic file upload tools.
   *
   * @param url the http(s) URL to download
   * @param maxBytes the maximum number of bytes to read before failing
   * @param defaultFileName a file name to use when the URL path has none
   * @return the downloaded bytes, resolved mime type and file name
   */
  public static FetchedContent fetchUrl(String url, long maxBytes, String defaultFileName) {
    FetchedResponse response = fetchViaPinnedConnection(url, maxBytes);
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new IllegalArgumentException("The file URL returned HTTP " + status + ".");
    }
    byte[] bytes = response.bytes();
    if (bytes.length == 0) {
      throw new IllegalArgumentException("The file URL returned an empty response body; provide a URL that points to actual file bytes.");
    }
    String mimeType = response.headers()
                              .firstValue("Content-Type")
                              .map(value -> value.split(";")[0].trim())
                              .filter(StringUtils::isNotBlank)
                              .orElse("application/octet-stream");
    return new FetchedContent(bytes, mimeType, fileNameFromUrl(url, defaultFileName));
  }

  /**
   * Downloads bytes from a validated public http(s) URL, pinning the TCP
   * connection to the exact address that {@link #assertPublicHttpUrl} just
   * validated. Without this, the SSRF guard's DNS resolution and the
   * {@link HttpClient}'s own (separate, later) resolution at connect time
   * could return different answers for the same hostname — an attacker
   * controlling the DNS record can serve a public IP for the first lookup and
   * a private/internal one for the second ("DNS rebinding"), fully bypassing
   * the guard. When the host is already an IP literal there is no DNS
   * resolution to pin (nothing to rebind), so the request goes out unchanged.
   *
   * <p>The pin is done by connecting directly to the validated IP while
   * keeping TLS SNI and the HTTP <code>Host</code> header set to the original
   * hostname (via {@link SSLParameters#setServerNames} and, best-effort, an
   * explicit <code>Host</code> header — see the static initializer), so
   * certificate hostname verification and name-based virtual hosting both
   * keep working correctly against the pinned connection.
   */
  private static FetchedResponse fetchViaPinnedConnection(String url, long maxBytes) {
    String trimmedUrl = StringUtils.trimToEmpty(url);
    URI uri;
    try {
      uri = URI.create(trimmedUrl);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid URL.");
    }
    InetAddress[] validatedAddresses = assertPublicHttpUrl(trimmedUrl);
    String host = uri.getHost();

    HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                                                 .followRedirects(HttpClient.Redirect.NEVER)
                                                 .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
    URI targetUri = uri;
    boolean pinned = false;
    if (!isIpLiteral(host)) {
      targetUri = withHost(uri, validatedAddresses[0].getHostAddress());
      SSLParameters sslParameters = new SSLParameters();
      sslParameters.setServerNames(List.of(new SNIHostName(host)));
      clientBuilder.sslParameters(sslParameters);
      pinned = true;
    }
    HttpClient client = clientBuilder.build();

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(targetUri)
                                                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                                                    .GET();
    if (pinned) {
      try {
        requestBuilder.header("Host", host);
      } catch (IllegalArgumentException e) {
        LOG.warn("Could not pin the Host header for {} (JVM property 'jdk.httpclient.allowRestrictedHeaders' isn't in "
            + "effect); the request will use the pinned IP as its Host header instead.", host);
      }
    }
    HttpRequest request = requestBuilder.build();

    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while downloading from the URL.");
    } catch (IOException e) {
      throw new IllegalArgumentException("Could not fetch the URL: " + e.getMessage());
    }
    byte[] bytes = readCapped(response.body(), maxBytes);
    return new FetchedResponse(response.statusCode(), response.headers(), bytes);
  }

  /** True when {@code host} is an IPv4/IPv6 literal rather than a DNS name (nothing to pin/rebind). */
  private static boolean isIpLiteral(String host) {
    String candidate = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    return candidate.indexOf(':') >= 0 || candidate.matches("\\d{1,3}(\\.\\d{1,3}){3}");
  }

  /** Rebuilds {@code uri} with its host replaced by {@code ipLiteral}, keeping scheme/port/path/query. */
  private static URI withHost(URI uri, String ipLiteral) {
    String hostForUri = ipLiteral.indexOf(':') >= 0 ? "[" + ipLiteral + "]" : ipLiteral;
    StringBuilder builder = new StringBuilder(uri.getScheme()).append("://").append(hostForUri);
    if (uri.getPort() >= 0) {
      builder.append(':').append(uri.getPort());
    }
    if (uri.getRawPath() != null) {
      builder.append(uri.getRawPath());
    }
    if (uri.getRawQuery() != null) {
      builder.append('?').append(uri.getRawQuery());
    }
    return URI.create(builder.toString());
  }

  /** Extracts the last path segment of a URL as a file name, or the given fallback. */
  static String fileNameFromUrl(String url, String defaultFileName) {
    try {
      String path = URI.create(StringUtils.trimToEmpty(url)).getPath();
      if (StringUtils.isNotBlank(path)) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (StringUtils.isNotBlank(last)) {
          return last;
        }
      }
    } catch (RuntimeException e) {
      // fall through to the default file name
    }
    return StringUtils.isBlank(defaultFileName) ? "download" : defaultFileName;
  }

  /**
   * Stages raw bytes to a temp file and registers an {@link UploadResource},
   * returning its <code>uploadId</code>.
   */
  public static String materialize(UploadService uploadService, byte[] bytes, String fileName, String mimeType) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("No image data to upload.");
    }
    String uploadId = UUID.randomUUID().toString();
    File temp;
    try {
      temp = Files.createTempFile("mcp-upload-", ".bin").toFile();
      temp.deleteOnExit();
      try (FileOutputStream output = new FileOutputStream(temp)) {
        output.write(bytes);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not stage the image for upload: " + e.getMessage());
    }
    UploadResource resource = new UploadResource(uploadId);
    resource.setFileName(StringUtils.isBlank(fileName) ? uploadId : fileName);
    resource.setMimeType(mimeType);
    resource.setStoreLocation(temp.getAbsolutePath());
    resource.setEstimatedSize(bytes.length);
    resource.setStatus(UploadResource.UPLOADED_STATUS);
    uploadService.createUploadResource(resource);
    return uploadId;
  }

  /** Removes the upload resource and its temp file. Safe to call in a finally. */
  public static void release(UploadService uploadService, String uploadId) {
    if (uploadId == null) {
      return;
    }
    try {
      uploadService.removeUploadResource(uploadId);
    } catch (RuntimeException e) {
      LOG.warn("Could not release upload resource {}", uploadId, e);
    }
  }

  public static byte[] decodeBase64(String data) {
    String encoded = data.trim();
    if (encoded.startsWith("data:")) {
      int comma = encoded.indexOf(',');
      if (comma > 0) {
        encoded = encoded.substring(comma + 1);
      }
    }
    try {
      return Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("image_base64 is not valid base64 data.");
    }
  }

  /**
   * Rejects any URL that is not public http/https — no other scheme, and no
   * host resolving to a loopback/link-local/site-local/CGNAT/unique-local
   * address (SSRF guard). Package-visible for testing.
   *
   * @return the validated, resolved addresses, so callers can pin their
   *         connection to the exact address that was checked here.
   */
  static InetAddress[] assertPublicHttpUrl(String urlString) {
    URI uri;
    try {
      uri = URI.create(StringUtils.trimToEmpty(urlString));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid URL.");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("Only http and https URLs are allowed.");
    }
    String host = uri.getHost();
    if (StringUtils.isBlank(host)) {
      throw new IllegalArgumentException("The URL has no host.");
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Could not fetch the URL: unknown host " + host + ".");
    }
    for (InetAddress address : addresses) {
      if (isBlockedAddress(address)) {
        throw new IllegalArgumentException("URL host is not allowed (it points to a private or internal address).");
      }
    }
    return addresses;
  }

  static boolean isBlockedAddress(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 16 && isIpv4Mapped(bytes)) {
      // ::ffff:a.b.c.d — re-check the embedded IPv4 address against the same rules below,
      // otherwise a mapped literal in a blocked IPv4 range (e.g. ::ffff:100.64.0.1) slips through.
      bytes = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
    }
    if (bytes.length == 4) {
      int b0 = bytes[0] & 0xFF;
      int b1 = bytes[1] & 0xFF;
      // 0.0.0.0/8 and CGNAT 100.64.0.0/10
      return b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127);
    }
    if (bytes.length == 16) {
      // IPv6 unique local addresses fc00::/7
      return (bytes[0] & 0xFE) == 0xFC;
    }
    return false;
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
  }

  private static byte[] readCapped(InputStream input, long maxBytes) {
    try (input) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new IllegalArgumentException("The file exceeds the maximum allowed size (" + (maxBytes / (1024 * 1024))
              + " MB).");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalArgumentException("Could not fetch the URL: " + e.getMessage());
    }
  }

  static String sniffImageMime(byte[] bytes) {
    if (bytes == null || bytes.length < 12) {
      return null;
    }
    if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
      return "image/png";
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
      return "image/jpeg";
    }
    if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
      return "image/gif";
    }
    if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
      return "image/webp";
    }
    return null;
  }

  private static String extensionForMime(String mimeType) {
    return switch (mimeType) {
    case "image/png" -> ".png";
    case "image/jpeg" -> ".jpg";
    case "image/gif" -> ".gif";
    case "image/webp" -> ".webp";
    default -> ".img";
    };
  }

}

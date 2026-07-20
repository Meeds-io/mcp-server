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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
 * against SSRF (only public http/https hosts are allowed).
 */
public final class UploadToolUtils {

  private static final Log    LOG                      = ExoLogger.getLogger(UploadToolUtils.class);

  /** 10 MB default ceiling for a fetched/decoded image. */
  public static final long    DEFAULT_MAX_BYTES        = 10L * 1024 * 1024;

  private static final int    CONNECT_TIMEOUT_SECONDS  = 10;

  private static final int    READ_TIMEOUT_SECONDS     = 20;

  private UploadToolUtils() {
  }

  /** A downloaded image: its bytes, resolved mime type and a file name. */
  public record FetchedContent(byte[] bytes, String mimeType, String fileName) {

    @Override
    public boolean equals(Object o) {
      return this == o
          || (o instanceof FetchedContent other
              && Arrays.equals(bytes, other.bytes)
              && Objects.equals(mimeType, other.mimeType)
              && Objects.equals(fileName, other.fileName));
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(bytes), mimeType, fileName);
    }

    @Override
    public String toString() {
      return "FetchedContent[bytes.length=%d, mimeType=%s, fileName=%s]".formatted(bytes.length, mimeType, fileName);
    }
  }

  /** A raw http(s) response: its status, headers and body bytes (already size-capped). */
  private record FetchedResponse(int statusCode, HttpHeaders headers, byte[] bytes) {

    @Override
    public boolean equals(Object o) {
      return this == o
          || (o instanceof FetchedResponse other
              && statusCode == other.statusCode
              && Objects.equals(headers, other.headers)
              && Arrays.equals(bytes, other.bytes));
    }

    @Override
    public int hashCode() {
      return Objects.hash(statusCode, headers, Arrays.hashCode(bytes));
    }

    @Override
    public String toString() {
      return "FetchedResponse[statusCode=%d, bytes.length=%d]".formatted(statusCode, bytes.length);
    }
  }

  /**
   * The three mutually-exclusive ways to provide an image to {@link #resolveImage}: a public
   * http(s) URL, base64-encoded bytes, or a reference to an existing platform attachment
   * (<code>attachmentObjectType</code> + <code>attachmentObjectId</code>).
   */
  public record ImageSource(String imageUrl, String imageBase64, String attachmentObjectType, String attachmentObjectId) {
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
                                          ImageSource source,
                                          long maxBytes) throws IllegalAccessException, ObjectNotFoundException {
    String imageUrl = source.imageUrl();
    String imageBase64 = source.imageBase64();
    String attachmentObjectType = source.attachmentObjectType();
    String attachmentObjectId = source.attachmentObjectId();
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
    FetchedResponse response = fetchInternal(url, maxBytes);
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
    FetchedResponse response = fetchInternal(url, maxBytes);
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
   * Downloads bytes from a validated public http(s) URL. Shared by
   * {@link #fetchImage} and {@link #fetchUrl}, which differ only in how they
   * interpret the status/headers/bytes afterwards.
   */
  private static FetchedResponse fetchInternal(String url, long maxBytes) {
    assertPublicHttpUrl(url);
    HttpClient client = HttpClient.newBuilder()
                                  .followRedirects(HttpClient.Redirect.NEVER)
                                  .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                                  .build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                                     .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                                     .GET()
                                     .build();
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
      temp = createOwnerOnlyTempFile();
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

  /**
   * Creates a temp file restricted to the owner (rw-------) rather than relying on the
   * platform/umask default, since the default temp directory is shared and world-writable
   * on most systems and the staged bytes are user-supplied image content.
   */
  private static File createOwnerOnlyTempFile() throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
                                                        PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ,
                                                                                                         PosixFilePermission.OWNER_WRITE));
      return Files.createTempFile("mcp-upload-", ".bin", ownerOnly).toFile();
    }
    return Files.createTempFile("mcp-upload-", ".bin").toFile();
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
   */
  static void assertPublicHttpUrl(String urlString) {
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
  }

  static boolean isBlockedAddress(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
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

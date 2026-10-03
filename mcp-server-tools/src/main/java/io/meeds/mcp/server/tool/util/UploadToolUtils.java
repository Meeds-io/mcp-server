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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
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

import io.meeds.commons.http.SafeFetchException;
import io.meeds.commons.http.SafeFetchPolicy;
import io.meeds.commons.http.SafeFetchRequest;
import io.meeds.commons.http.SafeFetchResponse;
import io.meeds.commons.http.SafeHttpFetcher;

/**
 * Helpers to turn an image (from a URL or base64) into a platform
 * {@link UploadService} <code>uploadId</code> that any upload-consuming API
 * (activity/comment attachments, note featured image, avatars, documents…)
 * accepts. This is the missing "materialize" side of {@link UploadService},
 * which otherwise only registers/reads resources.
 *
 * <p>Fetching from a URL happens server-side, through the platform's
 * {@link SafeHttpFetcher}: only public http/https hosts are reached, and the
 * address is judged by the HTTP client's own resolver when the connection
 * opens, so the address checked is the address dialled — a name answering a
 * public address once and an internal one at the next lookup is refused at
 * the connection. No redirect is followed; the body is bounded by the caller's
 * limit and the whole read by a deadline.
 */
public final class UploadToolUtils {

  private static final Log    LOG                      = ExoLogger.getLogger(UploadToolUtils.class);

  /** 10 MB default ceiling for a fetched/decoded image. */
  public static final long    DEFAULT_MAX_BYTES        = 10L * 1024 * 1024;

  private static final int    CONNECT_TIMEOUT_SECONDS  = 10;

  private static final int    READ_TIMEOUT_SECONDS     = 20;

  /** Longest fetch of one URL in all: a server trickling bytes cannot hold a tool call longer. */
  private static final int    TOTAL_TIMEOUT_SECONDS    = 60;

  private static final String USER_AGENT               = "Meeds-MCP-Server-Upload/1.0";

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_PERMS =
                                                                                 PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ,
                                                                                                                                  PosixFilePermission.OWNER_WRITE));

  /**
   * The one fetcher of every URL a tool is given: public http/https on any
   * port, no redirect followed, the timeouts above. Shared for the JVM's life,
   * so it is never closed; its only thread is a daemon.
   */
  private static final SafeHttpFetcher FETCHER = new SafeHttpFetcher(SafeFetchPolicy.builder()
                                                                                      .name("mcp-server-upload")
                                                                                      .userAgent(USER_AGENT)
                                                                                      .anyPort()
                                                                                      .maxRedirects(0)
                                                                                      .maxBytes(DEFAULT_MAX_BYTES)
                                                                                      .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                                                                                      .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                                                                                      .totalTimeout(Duration.ofSeconds(TOTAL_TIMEOUT_SECONDS))
                                                                                      .build());

  /**
   * Not instantiated.
   */
  private UploadToolUtils() {
  }

  /** A downloaded image: its bytes, resolved mime type and a file name. */
  public record FetchedContent(byte[] bytes, String mimeType, String fileName) {

    /**
     * Compares by content: the bytes are an array.
     *
     * @param o the other object
     * @return true when the bytes, type and name are equal
     */
    @Override
    public boolean equals(Object o) {
      return this == o
          || (o instanceof FetchedContent other
              && Arrays.equals(bytes, other.bytes)
              && Objects.equals(mimeType, other.mimeType)
              && Objects.equals(fileName, other.fileName));
    }

    /**
     * Hashes by content, the bytes included.
     *
     * @return the hash
     */
    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(bytes), mimeType, fileName);
    }

    /**
     * Describes the content by its size, type and name, never its bytes.
     *
     * @return the description
     */
    @Override
    public String toString() {
      return "FetchedContent[bytes.length=%d, mimeType=%s, fileName=%s]".formatted(bytes.length, mimeType, fileName);
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
   * @param uploadService the upload registry
   * @param imageUrl the public http(s) URL, or null
   * @param imageBase64 the base64 bytes, or null
   * @param maxBytes the most bytes accepted
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
   * @param attachmentService the attachments, read as the user
   * @param fileService the files
   * @param aclIdentity the user the attachment is read as
   * @param source the one source of the image
   * @param maxBytes the most bytes accepted
   * @return the resolved image bytes, mime type and file name
   * @throws IllegalAccessException when the user may not read the attachment
   * @throws ObjectNotFoundException when the attachment has no file
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

  /**
   * Decodes a base64 image into its bytes, enforcing the size cap and a supported mime.
   *
   * @param imageBase64 the base64 bytes, a data URI accepted
   * @param maxBytes the most bytes accepted
   * @return the image
   */
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
   * Downloads an image over http(s) through the platform's guarded fetcher.
   *
   * @param url the http(s) URL
   * @param maxBytes the most bytes read before failing
   * @return the image
   */
  public static FetchedContent fetchImage(String url, long maxBytes) {
    return fetchImage(FETCHER, url, maxBytes);
  }

  /**
   * Downloads an image through a given fetcher: the seam of the tests, which
   * hand in one over a table of names.
   *
   * @param fetcher the fetcher
   * @param url the http(s) URL
   * @param maxBytes the most bytes read before failing
   * @return the image
   */
  static FetchedContent fetchImage(SafeHttpFetcher fetcher, String url, long maxBytes) {
    SafeFetchResponse response = fetchInternal(fetcher, url, maxBytes, "image");
    byte[] bytes = response.body();
    String mimeType = StringUtils.startsWith(response.mediaType(), "image/") ? response.mediaType() : sniffImageMime(bytes);
    if (mimeType == null) {
      throw new IllegalArgumentException("The URL does not point to a supported image.");
    }
    return new FetchedContent(bytes, mimeType, "image" + extensionForMime(mimeType));
  }

  /**
   * Downloads <b>any</b> file (not restricted to images) over http(s) through
   * the platform's guarded fetcher. The mime type is resolved from the
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
    return fetchUrl(FETCHER, url, maxBytes, defaultFileName);
  }

  /**
   * Downloads any file through a given fetcher: the seam of the tests.
   *
   * @param fetcher the fetcher
   * @param url the http(s) URL to download
   * @param maxBytes the maximum number of bytes to read before failing
   * @param defaultFileName a file name to use when the URL path has none
   * @return the downloaded bytes, resolved mime type and file name
   */
  static FetchedContent fetchUrl(SafeHttpFetcher fetcher, String url, long maxBytes, String defaultFileName) {
    SafeFetchResponse response = fetchInternal(fetcher, url, maxBytes, "file");
    byte[] bytes = response.body();
    if (bytes.length == 0) {
      throw new IllegalArgumentException("The file URL returned an empty response body; provide a URL that points to actual file bytes.");
    }
    String mimeType = StringUtils.defaultIfBlank(StringUtils.trim(StringUtils.substringBefore(response.contentType(), ";")),
                                                 "application/octet-stream");
    return new FetchedContent(bytes, mimeType, fileNameFromUrl(url, defaultFileName));
  }

  /**
   * Reads a URL through the fetcher, whose guard refuses what is not public
   * http(s) — the URL's shape before the request, the address at the
   * connection — and whose bounds cap the body and the time. Shared by
   * {@link #fetchImage} and {@link #fetchUrl}, which differ only in how they
   * interpret the answer. Every refusal is an {@link IllegalArgumentException}
   * the tool reports; none names the URL's host or address.
   *
   * @param fetcher the fetcher
   * @param url the URL as given
   * @param maxBytes the most bytes read
   * @param what "image" or "file", for the messages
   * @return the 2xx answer
   */
  private static SafeFetchResponse fetchInternal(SafeHttpFetcher fetcher, String url, long maxBytes, String what) {
    try {
      URI uri = fetcher.getGuard().normalize(url);
      return fetcher.fetch(SafeFetchRequest.get(uri).withMaxBytes(maxBytes));
    } catch (SafeFetchException e) {
      throw new IllegalArgumentException(switch (e.getFailure()) {
      case INVALID_URL -> "Invalid URL.";
      case SCHEME_NOT_ALLOWED, PORT_NOT_ALLOWED -> "Only http and https URLs are allowed.";
      case CREDENTIALS_IN_URL -> "The URL must not carry credentials.";
      case REFUSED_ADDRESS -> "URL host is not allowed (it points to a private or internal address).";
      case UNRESOLVABLE -> "Could not fetch the URL: unknown host.";
      case HTTP_ERROR -> "The " + what + " URL returned HTTP " + e.getStatus() + ".";
      case TOO_MANY_REDIRECTS -> "The " + what + " URL redirects, which is not followed; provide the final URL.";
      case TOO_LARGE -> "The file exceeds the maximum allowed size (" + (maxBytes / (1024 * 1024)) + " MB).";
      case TIMEOUT -> "Could not fetch the URL: it did not answer in time.";
      case CONTENT_TYPE_NOT_ALLOWED, UNREACHABLE -> "Could not fetch the URL.";
      }, e);
    }
  }

  /**
   * Extracts the last path segment of a URL as a file name, or the given fallback.
   *
   * @param url the URL
   * @param defaultFileName the fallback, "download" when blank
   * @return the file name
   */
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
   *
   * @param uploadService the upload registry
   * @param bytes the content
   * @param fileName the file name, the upload id when blank
   * @param mimeType the mime type
   * @return the upload id
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
   * on most systems and the staged bytes are user-supplied image content. The eXo/Meeds
   * runtime is POSIX-only (Linux), so no non-POSIX fallback is needed.
   *
   * @return the file
   * @throws IOException when it cannot be created
   */
  private static File createOwnerOnlyTempFile() throws IOException {
    return Files.createTempFile("mcp-upload-", ".bin", OWNER_ONLY_PERMS).toFile();
  }

  /**
   * Removes the upload resource and its temp file. Safe to call in a finally.
   *
   * @param uploadService the upload registry
   * @param uploadId the upload id, null for nothing to release
   */
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

  /**
   * Decodes base64 data, a data URI prefix dropped and white space ignored.
   *
   * @param data the base64 text
   * @return the bytes
   */
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
   * The image type the bytes are, from their magic number: png, jpeg, gif or
   * webp.
   *
   * @param bytes the bytes
   * @return the mime type, or null for none of those
   */
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

  /**
   * The file extension of an image mime type.
   *
   * @param mimeType the mime type
   * @return the extension, dot included
   */
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

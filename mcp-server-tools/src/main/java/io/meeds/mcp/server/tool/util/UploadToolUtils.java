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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
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
  public record FetchedImage(byte[] bytes, String mimeType, String fileName) {
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
    if (hasUrl) {
      FetchedImage image = fetchImage(imageUrl, maxBytes);
      return materialize(uploadService, image.bytes(), image.fileName(), image.mimeType());
    }
    byte[] bytes = decodeBase64(imageBase64);
    if (bytes.length > maxBytes) {
      throw new IllegalArgumentException("Image exceeds the maximum allowed size (" + (maxBytes / (1024 * 1024)) + " MB).");
    }
    String mimeType = sniffImageMime(bytes);
    if (mimeType == null) {
      throw new IllegalArgumentException("image_base64 does not decode to a supported image (png, jpeg, gif or webp).");
    }
    return materialize(uploadService, bytes, "image" + extensionForMime(mimeType), mimeType);
  }

  /**
   * Downloads an image over http(s) after validating the URL against SSRF.
   */
  public static FetchedImage fetchImage(String url, long maxBytes) {
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
      throw new IllegalStateException("Interrupted while downloading the image from the URL.");
    } catch (IOException e) {
      throw new IllegalStateException("Could not download the image from the given URL: " + e.getMessage());
    }
    int status = response.statusCode();
    if (status >= 300 && status < 400) {
      throw new IllegalArgumentException("The image URL redirects; provide a direct image link.");
    }
    if (status != 200) {
      throw new IllegalStateException("The image URL returned HTTP " + status + ".");
    }
    byte[] bytes = readCapped(response.body(), maxBytes);
    String mimeType = response.headers()
                              .firstValue("Content-Type")
                              .map(value -> value.split(";")[0].trim().toLowerCase())
                              .filter(value -> value.startsWith("image/"))
                              .orElseGet(() -> sniffImageMime(bytes));
    if (mimeType == null) {
      throw new IllegalArgumentException("The URL does not point to a supported image.");
    }
    return new FetchedImage(bytes, mimeType, "image" + extensionForMime(mimeType));
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
   */
  static void assertPublicHttpUrl(String urlString) {
    URI uri;
    try {
      uri = URI.create(StringUtils.trimToEmpty(urlString));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid image URL.");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("Only http and https image URLs are allowed.");
    }
    String host = uri.getHost();
    if (StringUtils.isBlank(host)) {
      throw new IllegalArgumentException("Image URL has no host.");
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Image URL host cannot be resolved: " + host);
    }
    for (InetAddress address : addresses) {
      if (isBlockedAddress(address)) {
        throw new IllegalArgumentException("Image URL host is not allowed (it points to a private or internal address).");
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
          throw new IllegalArgumentException("Image exceeds the maximum allowed size (" + (maxBytes / (1024 * 1024))
              + " MB).");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Error reading image data: " + e.getMessage());
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

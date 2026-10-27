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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.cluster.ClusterNodeName;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.mcp.server.plugin.McpToolPlugin;
import io.meeds.mcp.server.tool.model.UploadLinkModel;
import io.meeds.mcp.server.tool.model.UploadStatusModel;
import io.meeds.mcp.server.tool.util.UploadToolUtils;

/**
 * Lets an agent send a file from its own machine to the platform without
 * passing the bytes through the conversation: {@code start_upload} opens a
 * one-time upload link on the platform's upload endpoint, the agent sends the
 * file with the returned curl command, and passes the returned
 * {@code upload_id} to the tool that consumes the file, which reads it through
 * the owner-checked accessor of {@link UploadService} and removes it.
 * <p>
 * Pending uploads: a link unused within its validity is dropped by the
 * platform; an uploaded file no tool consumes is removed when the HTTP session
 * of the upload request expires (the container's session timeout). Both live
 * in this server node's memory and disk only. The number a user may hold
 * pending is not bounded here: each link is one call, approved by the user when
 * the caller's token carries the approval scope, and each file is at most
 * the upload limit; the platform's upload registry exposes no per-user count
 * to bound it with.
 */
@Service
public class UploadMcpTool implements McpToolPlugin {

  /** The upload endpoint the token is presented to, from the platform's base URL. */
  public static final String  UPLOAD_PATH              = "/portal/upload?action=upload&uploadId=";

  /** The upload limit, in MB, when no property sets a positive one. */
  public static final long    DEFAULT_MAX_SIZE_IN_MB   = 200;

  /** The placeholder of the curl command the caller replaces with the file's path. */
  public static final String  PATH_PLACEHOLDER         = "<path-to-file>";

  /** What the caller does once the link is open. */
  public static final String  NEXT_STEP                = """
      Run curl_command in a shell on the machine that holds the file, with <path-to-file> replaced by the file's path. \
      It answers {"status":"uploaded","size":N} once the file is received. Then pass upload_id to the tool that should \
      receive the file (for an image: set_my_avatar, set_my_banner, set_space_avatar, set_space_banner, \
      create_activity_with_image, attach_image_to_activity, create_activity_comment_with_image or \
      attach_image_to_comment), which consumes it. The link works once, within a few minutes (expires_in_seconds \
      is the platform default validity), and only on the server that issued it: send it with curl_command as \
      given, whose cookie, on a cluster, routes it to that server. HTTP 401: the \
      link expired or was already used; 409: the upload_id is taken; 413: the file is larger than the declared size; \
      in each case call start_upload again. 400: the request is not one multipart file or the platform refused the \
      file; 411: the request had no Content-Length (send the file with -F as given). get_upload tells where an \
      upload stands.""";

  /** What remains when the link cannot be used. */
  public static final String  FALLBACK                 = """
      If curl cannot reach the platform (for example a proxy answering 403 to the connection) or no shell is \
      available, the file cannot be sent by link from this environment: a small image can still be passed to the \
      image tools as image_base64, at the cost of every byte going through the conversation.""";

  /** Characters a file name may not hold: path separators, quotes and control characters. */
  private static final Pattern FORBIDDEN_NAME_CHARS   = Pattern.compile("[/\\\\'\"\\p{Cntrl}]");

  private static final int     MAX_NAME_LENGTH        = 255;

  private static final long    BYTES_PER_MB           = 1024L * 1024L;

  /**
   * A cluster node name the routing cookie may carry: what a Tomcat
   * {@code jvmRoute} holds, and nothing that could break the command's
   * quoting.
   */
  private static final Pattern NODE_NAME              = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final UploadService  uploadService;

  private final long           maxSizeInMB;

  @Autowired(required = false)
  private ClusterNodeName      clusterNodeName;

  /**
   * Builds the tool over the platform upload registry.
   *
   * @param uploadService the platform upload registry the link registers into
   * @param maxSizeInMB the largest file a link accepts, in MB: the
   *          {@code meeds.mcp.upload.maxSizeInMB} property, else the
   *          platform's upload limit {@code exo.uploadLimit}, which the token's
   *          size replaces for its upload, else
   *          {@value #DEFAULT_MAX_SIZE_IN_MB} when blank, not a number or not
   *          positive (an {@code exo.uploadLimit} of 0, unlimited for the
   *          platform, gives a link that default)
   */
  public UploadMcpTool(UploadService uploadService,
                       @Value("${meeds.mcp.upload.maxSizeInMB:${exo.uploadLimit:200}}")
                       String maxSizeInMB) {
    this.uploadService = uploadService;
    long value = NumberUtils.toLong(StringUtils.trim(maxSizeInMB), 0);
    this.maxSizeInMB = value > 0 ? value : DEFAULT_MAX_SIZE_IN_MB;
  }

  /**
   * Opens a one-time upload link for a file of the current user: generates an
   * unguessable upload id, has the platform issue a token bound to the user,
   * that id and the declared size, and returns the ready curl command that
   * sends the file.
   *
   * @param name the file name, extension included, without path or quotes
   * @param size the file's size in bytes, positive and at most the upload limit
   * @return the upload id, the upload URL, the curl command, the link's
   *         validity and the next step
   * @throws IllegalArgumentException when the name or the size is refused
   */
  public UploadLinkModel startUpload(String name, Long size) {
    String fileName = checkFileName(name);
    String routingCookie = routingCookie();
    long maxBytes = maxSizeInMB * BYTES_PER_MB;
    if (size == null || size <= 0) {
      throw new IllegalArgumentException("size must be the file's exact size in bytes, greater than 0.");
    }
    if (size > maxBytes) {
      throw new IllegalArgumentException("The file is %d bytes, over the %d MB an upload may hold on this platform.".formatted(size,
                                                                                                                         maxSizeInMB));
    }
    String uploadId = UUID.randomUUID().toString();
    String token = uploadService.createUploadToken(getCurrentUserName(), uploadId, size);
    String uploadUrl = Strings.CS.removeEnd(CommonsUtils.getCurrentDomain(), "/") + UPLOAD_PATH + uploadId;
    return new UploadLinkModel(uploadId,
                               uploadUrl,
                               curlCommand(token, fileName, uploadUrl, routingCookie),
                               size,
                               UploadService.DEFAULT_UPLOAD_TOKEN_VALIDITY_SECONDS,
                               NEXT_STEP,
                               FALLBACK);
  }

  /**
   * Builds the shell command that sends the file: every argument in single
   * quotes, the token in the {@code Authorization} header, never in the URL.
   * An option the request needs, such as a cookie routing it to this node, is
   * one more element of the argument list.
   *
   * @param token the one-time upload token
   * @param fileName the checked file name, sent as the multipart file name
   * @param uploadUrl the upload URL
   * @param routingCookie the cookie routing the request to this node, or null
   *          outside a cluster
   * @return the curl command, with {@value #PATH_PLACEHOLDER} for the file's
   *         path
   */
  private String curlCommand(String token, String fileName, String uploadUrl, String routingCookie) {
    List<String> arguments = new ArrayList<>();
    arguments.add("curl -sS");
    arguments.add("-H 'Authorization: Bearer " + token + "'");
    if (routingCookie != null) {
      arguments.add("-b '" + routingCookie + "'");
    }
    arguments.add("-F 'file=@" + PATH_PLACEHOLDER + ";filename=\"" + fileName + "\"'");
    arguments.add("'" + uploadUrl + "'");
    return String.join(" ", arguments);
  }

  /**
   * The cookie that routes the upload request to this node in a cluster: the
   * token and the uploaded file live in this node's memory and disk, and
   * Tomcat's {@code jvmRoute} is the cluster node name, so a load balancer
   * that honours it sends a session id suffixed {@code .<node>} here. Tomcat
   * ignores the unknown session id itself and opens a fresh session.
   *
   * @return {@code JSESSIONID=upload.<node>}, or null when this server has no
   *         cluster node name
   * @throws IllegalStateException when the node name holds characters a
   *           {@code jvmRoute} does not, which could break the command
   */
  private String routingCookie() {
    String nodeName = clusterNodeName == null ? null : StringUtils.trim(clusterNodeName.getNodeName());
    if (StringUtils.isBlank(nodeName)) {
      return null;
    }
    if (!NODE_NAME.matcher(nodeName).matches()) {
      throw new IllegalStateException("This server's cluster node name (exo.cluster.node.name) cannot route an upload "
          + "link: it may hold only letters, digits, '.', '_' and '-'. An administrator must fix it before files can be "
          + "sent by link.");
    }
    return "JSESSIONID=upload." + nodeName;
  }

  /**
   * Tells where an upload of the current user stands on this server node.
   *
   * @param uploadId the upload id start_upload returned
   * @return its status, size, file name and mime type, or the {@code unknown}
   *         status when this node holds no such upload for the user
   * @throws IllegalArgumentException when the upload id is blank
   */
  public UploadStatusModel getUpload(String uploadId) {
    if (StringUtils.isBlank(uploadId)) {
      throw new IllegalArgumentException("upload_id is mandatory: pass the id start_upload returned.");
    }
    String id = uploadId.trim();
    UploadResource resource = uploadService.getUploadResource(id, getCurrentUserName());
    if (resource == null) {
      return new UploadStatusModel(id, "unknown", null, null, null, UploadToolUtils.UNKNOWN_UPLOAD_MESSAGE);
    }
    return switch (resource.getStatus()) {
    case UploadResource.UPLOADED_STATUS -> new UploadStatusModel(id,
                                                                 "uploaded",
                                                                 storedSize(resource),
                                                                 resource.getFileName(),
                                                                 resource.getMimeType(),
                                                                 null);
    case UploadResource.FAILED_STATUS -> new UploadStatusModel(id,
                                                               "failed",
                                                               (long) resource.getUploadedSize(),
                                                               resource.getFileName(),
                                                               resource.getMimeType(),
                                                               "The upload failed. Call start_upload again, run its curl command, then pass the new upload_id.");
    default -> new UploadStatusModel(id,
                                     "uploading",
                                     (long) resource.getUploadedSize(),
                                     resource.getFileName(),
                                     resource.getMimeType(),
                                     "The file is still being received: wait for the curl command to finish.");
    };
  }

  /**
   * The size of an uploaded resource's stored file, or the bytes the upload
   * counted when the file cannot be read.
   *
   * @param resource an uploaded resource
   * @return its size in bytes
   */
  private long storedSize(UploadResource resource) {
    if (StringUtils.isNotBlank(resource.getStoreLocation())) {
      File file = new File(resource.getStoreLocation());
      if (file.isFile()) {
        return file.length();
      }
    }
    return (long) resource.getUploadedSize();
  }

  /**
   * Checks a file name before it goes into the curl command: no path, no
   * quote, no control character, at most {@value #MAX_NAME_LENGTH}
   * characters, so it is neither a path nor able to break out of the
   * command's quoting.
   *
   * @param name the name the caller gave
   * @return the name, trimmed
   * @throws IllegalArgumentException when it is refused
   */
  private String checkFileName(String name) {
    String fileName = StringUtils.trim(name);
    if (StringUtils.isBlank(fileName)) {
      throw new IllegalArgumentException("name is mandatory: the file name, extension included (e.g. 'chart.png').");
    }
    if (fileName.length() > MAX_NAME_LENGTH
        || ".".equals(fileName)
        || "..".equals(fileName)
        || FORBIDDEN_NAME_CHARS.matcher(fileName).find()) {
      throw new IllegalArgumentException(("name must be a plain file name of at most %d characters, without path separators, "
          + "quotes or control characters (e.g. 'chart.png').").formatted(MAX_NAME_LENGTH));
    }
    return fileName;
  }

}

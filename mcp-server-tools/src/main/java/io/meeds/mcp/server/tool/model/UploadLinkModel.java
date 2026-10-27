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
package io.meeds.mcp.server.tool.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The upload link {@code start_upload} opens: the id of the upload to come,
 * where and how to send the file, and what to do next.
 *
 * @param uploadId the id the file is registered under once sent, to pass to
 *          the tool that consumes it
 * @param uploadUrl the platform URL the file is posted to
 * @param curlCommand the ready command that sends the file, with a
 *          {@code <path-to-file>} placeholder and the one-time token in its
 *          {@code Authorization} header
 * @param size the size declared for the file, in bytes: a larger file is
 *          refused
 * @param expiresInSeconds how long the link stays usable
 * @param nextStep what the caller does with the command and the upload id
 * @param fallback what remains when the command cannot run or cannot reach
 *          the platform
 */
@JsonInclude(value = Include.NON_EMPTY)
public record UploadLinkModel(
                              @JsonProperty("upload_id")
                              String uploadId,
                              @JsonProperty("upload_url")
                              String uploadUrl,
                              @JsonProperty("curl_command")
                              String curlCommand,
                              long size,
                              @JsonProperty("expires_in_seconds")
                              long expiresInSeconds,
                              @JsonProperty("next_step")
                              String nextStep,
                              String fallback) {
}

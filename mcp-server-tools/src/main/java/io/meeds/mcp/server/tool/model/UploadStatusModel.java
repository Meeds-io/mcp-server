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
 * The state of an upload opened with {@code start_upload}, as this server
 * node holds it for the current user.
 *
 * @param uploadId the upload id asked about
 * @param status {@code uploading}, {@code uploaded}, {@code failed}, or
 *          {@code unknown} when this server holds no such upload for the user
 * @param size the bytes received so far, or the stored file's size once
 *          uploaded
 * @param fileName the file name the upload request gave
 * @param mimeType the mime type the upload request declared
 * @param message what the status means for the caller's next step, when it
 *          is not {@code uploaded}
 */
@JsonInclude(value = Include.NON_EMPTY)
public record UploadStatusModel(
                                @JsonProperty("upload_id")
                                String uploadId,
                                String status,
                                Long size,
                                @JsonProperty("file_name")
                                String fileName,
                                @JsonProperty("mime_type")
                                String mimeType,
                                String message) {
}

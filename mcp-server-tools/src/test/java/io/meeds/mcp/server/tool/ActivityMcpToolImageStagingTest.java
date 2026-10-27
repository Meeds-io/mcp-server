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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

/**
 * Pins that an image post or image comment whose post fails releases the
 * image it staged, instead of leaving an ownerless upload resource behind.
 * Plain mocks, no Spring/Kernel context.
 */
class ActivityMcpToolImageStagingTest {

  private static final String   PNG_1PX       =
                                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  private static final long     MISSING_ID    = 99L;

  private final UploadService   uploadService = mock(UploadService.class);

  private final SpaceService    spaceService  = mock(SpaceService.class);

  private final ActivityManager activityManager = mock(ActivityManager.class);

  private final ActivityMcpTool tool          = new ActivityMcpTool() {
                                                @Override
                                                protected Long getScopeSpaceId() {
                                                  return null;
                                                }
                                              };

  /**
   * Wires the mocks and the current user.
   */
  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(tool, "uploadService", uploadService);
    ReflectionTestUtils.setField(tool, "spaceService", spaceService);
    ReflectionTestUtils.setField(tool, "activityManager", activityManager);
    ReflectionTestUtils.setField(tool, "identityManager", mock(IdentityManager.class));
    ConversationState.setCurrent(new ConversationState(new Identity("john")));
  }

  /**
   * Clears the current user.
   */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * A post into a missing space fails after the image is staged: the staged
   * upload is released. Mutant: the release on a failed post removed.
   */
  @Test
  void aFailedImagePostReleasesTheStagedImage() {
    assertThrows(ObjectNotFoundException.class,
                 () -> tool.createActivityWithImage(MISSING_ID, "hello", null, PNG_1PX, null, null, null, null));

    String stagedUploadId = stagedUploadId();
    verify(uploadService).removeUploadResource(stagedUploadId);
  }

  /**
   * A comment on a missing activity fails after the image is staged: the
   * staged upload is released. Mutant: the release on a failed comment
   * removed.
   */
  @Test
  void aFailedImageCommentReleasesTheStagedImage() {
    when(activityManager.getActivity(String.valueOf(MISSING_ID))).thenReturn(null);

    assertThrows(ObjectNotFoundException.class,
                 () -> tool.createActivityCommentWithImage(MISSING_ID, "hello", null, PNG_1PX, null, null, null, null));

    String stagedUploadId = stagedUploadId();
    verify(uploadService).removeUploadResource(stagedUploadId);
  }

  /**
   * A post whose image came from the user's upload, refused after the image
   * was read, says that the upload_id is spent and how to send the file
   * again; a post from base64 does not. Mutant: the hint dropped.
   *
   * @param folder a temporary folder
   * @throws Exception when the upload can't be staged
   */
  @Test
  void aFailedPostFromAnUploadSaysTheUploadIsConsumed(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("dot.png");
    Files.write(file, Base64.getDecoder().decode(PNG_1PX));
    UploadResource upload = new UploadResource("u-1", "dot.png");
    upload.setStoreLocation(file.toString());
    upload.setStatus(UploadResource.UPLOADED_STATUS);
    when(uploadService.getUploadResource("u-1", "john")).thenReturn(upload);

    ObjectNotFoundException refusal = assertThrows(ObjectNotFoundException.class,
                                                   () -> tool.createActivityWithImage(MISSING_ID,
                                                                                      "hello",
                                                                                      null,
                                                                                      null,
                                                                                      null,
                                                                                      null,
                                                                                      "u-1",
                                                                                      null));
    assertTrue(refusal.getMessage().contains("upload_id was consumed"), refusal.getMessage());
    verify(uploadService).removeUploadResource("u-1", "john");

    ObjectNotFoundException fromBase64 = assertThrows(ObjectNotFoundException.class,
                                                      () -> tool.createActivityWithImage(MISSING_ID,
                                                                                         "hello",
                                                                                         null,
                                                                                         PNG_1PX,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null));
    assertTrue(!fromBase64.getMessage().contains("upload_id"), fromBase64.getMessage());
  }

  /**
   * The hint is on every refusal met after the user's upload was read: a
   * comment on a missing activity, and a post the user may not make. Mutants:
   * the hint dropped from the comment path, or from the post's access
   * refusal.
   *
   * @param folder a temporary folder
   * @throws Exception when the upload can't be staged
   */
  @Test
  void everyRefusalAfterTheUploadWasReadSaysItIsConsumed(@TempDir Path folder) throws Exception {
    stageUpload(folder, "u-2");
    when(activityManager.getActivity(String.valueOf(MISSING_ID))).thenReturn(null);
    ObjectNotFoundException comment = assertThrows(ObjectNotFoundException.class,
                                                   () -> tool.createActivityCommentWithImage(MISSING_ID,
                                                                                             "hello",
                                                                                             null,
                                                                                             null,
                                                                                             null,
                                                                                             null,
                                                                                             "u-2",
                                                                                             null));
    assertTrue(comment.getMessage().contains("upload_id was consumed"), comment.getMessage());
    verify(uploadService).removeUploadResource("u-2", "john");

    stageUpload(folder, "u-3");
    IllegalAccessException post = assertThrows(IllegalAccessException.class,
                                               () -> tool.createActivityWithImage(null,
                                                                                  "hello",
                                                                                  null,
                                                                                  null,
                                                                                  null,
                                                                                  null,
                                                                                  "u-3",
                                                                                  null));
    assertTrue(post.getMessage().contains("upload_id was consumed"), post.getMessage());
    verify(uploadService).removeUploadResource("u-3", "john");
  }

  /**
   * Registers, for john, an uploaded 1x1 png under the given upload id.
   *
   * @param folder a temporary folder
   * @param uploadId the upload id
   * @throws Exception when the file can't be written
   */
  private void stageUpload(Path folder, String uploadId) throws Exception {
    Path file = folder.resolve(uploadId + ".png");
    Files.write(file, Base64.getDecoder().decode(PNG_1PX));
    UploadResource upload = new UploadResource(uploadId, "dot.png");
    upload.setStoreLocation(file.toString());
    upload.setStatus(UploadResource.UPLOADED_STATUS);
    when(uploadService.getUploadResource(uploadId, "john")).thenReturn(upload);
  }

  /**
   * @return the upload id the tool staged its image under
   */
  private String stagedUploadId() {
    ArgumentCaptor<UploadResource> staged = ArgumentCaptor.forClass(UploadResource.class);
    verify(uploadService).createUploadResource(staged.capture());
    return staged.getValue().getUploadId();
  }

}

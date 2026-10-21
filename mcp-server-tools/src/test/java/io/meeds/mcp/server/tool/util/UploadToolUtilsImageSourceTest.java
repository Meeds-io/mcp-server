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
package io.meeds.mcp.server.tool.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.attachment.AttachmentService;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.mcp.server.tool.util.UploadToolUtils.FetchedContent;
import io.meeds.mcp.server.tool.util.UploadToolUtils.ImageSource;

/**
 * Pins the image sources of
 * {@link UploadToolUtils#resolveImage(AttachmentService, FileService, UploadService, Identity, ImageSource, long)}
 * that read platform state: the upload id a user sent with
 * {@code start_upload} (owner check, size cap, type told from the bytes,
 * removal once read) and the attachment reference (size cap and type), with
 * the exactly-one-source rule; and that the deprecated overload keeps reading
 * any attachment unbounded, as the add-ons calling it rely on.
 */
class UploadToolUtilsImageSourceTest {

  private static final String      USERNAME   = "john";

  private static final String      UPLOAD_ID  = "4b2d6a7e-0d4f-4d38-9a51-2a1c3f0e9b11";

  private static final byte[]      PNG        = Base64.getDecoder()
                                                       .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

  private static final byte[]      TEXT       = "this is plain text, not an image".getBytes(StandardCharsets.UTF_8);

  private final AttachmentService  attachmentService = mock(AttachmentService.class);

  private final FileService        fileService       = mock(FileService.class);

  private final UploadService      uploadService     = mock(UploadService.class);

  private final Identity           identity          = new Identity(USERNAME);

  /**
   * The user's own uploaded image is read through the owner-checked accessor,
   * typed from its bytes (not from the type the upload declared), named as
   * uploaded, and removed through the owner-checked removal. Mutants: the
   * removal dropped; the one-argument accessor used.
   *
   * @param folder a temporary folder
   * @throws Exception when the file can't be written
   */
  @Test
  void anUploadIdIsReadForItsOwnerAndRemoved(@TempDir Path folder) throws Exception {
    stubUpload(folder, PNG, UploadResource.UPLOADED_STATUS, "application/octet-stream");

    FetchedContent image = resolve(new ImageSource(null, null, null, null, " " + UPLOAD_ID + " "), 1024);

    assertArrayEquals(PNG, image.bytes());
    assertEquals("image/png", image.mimeType());
    assertEquals("dot.png", image.fileName());
    verify(uploadService).getUploadResource(UPLOAD_ID, USERNAME);
    verify(uploadService, never()).getUploadResource(anyString());
    verify(uploadService).removeUploadResource(UPLOAD_ID, USERNAME);
    verify(uploadService, never()).removeUploadResource(anyString());
  }

  /**
   * An upload id this server holds for no upload of the user's — unknown, or
   * another user's, which the owner-checked accessor answers alike — is
   * refused with a message naming start_upload, and nothing is removed.
   */
  @Test
  void anUnknownOrAnotherUsersUploadIdIsRefused() {
    ObjectNotFoundException refusal = assertThrows(ObjectNotFoundException.class,
                                                   () -> resolve(new ImageSource(null, null, null, null, UPLOAD_ID), 1024));

    assertEquals(UploadToolUtils.UNKNOWN_UPLOAD_MESSAGE, refusal.getMessage());
    assertTrue(refusal.getMessage().contains("start_upload"));
    verify(uploadService).getUploadResource(UPLOAD_ID, USERNAME);
    verify(uploadService, never()).removeUploadResource(anyString(), anyString());
  }

  /**
   * An upload over the size limit is refused before its bytes are read, and
   * removed all the same. Mutants: the size check removed; the removal moved
   * out of {@code finally}.
   *
   * @param folder a temporary folder
   * @throws Exception when the file can't be written
   */
  @Test
  void anUploadOverTheLimitIsRefusedAndRemoved(@TempDir Path folder) throws Exception {
    stubUpload(folder, PNG, UploadResource.UPLOADED_STATUS, "image/png");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> resolve(new ImageSource(null, null, null, null, UPLOAD_ID),
                                                                  PNG.length - 1L));

    assertTrue(refusal.getMessage().contains("maximum allowed size"), refusal.getMessage());
    verify(uploadService).removeUploadResource(UPLOAD_ID, USERNAME);
  }

  /**
   * An upload of exactly the limit passes: the limit is inclusive, as on the
   * other sources. Mutant: the comparison made {@code >=}.
   *
   * @param folder a temporary folder
   * @throws Exception when the file can't be written
   */
  @Test
  void anUploadOfExactlyTheLimitPasses(@TempDir Path folder) throws Exception {
    stubUpload(folder, PNG, UploadResource.UPLOADED_STATUS, "image/png");

    assertArrayEquals(PNG, resolve(new ImageSource(null, null, null, null, UPLOAD_ID), PNG.length).bytes());
  }

  /**
   * An upload that is not a png, jpeg, gif or webp image is refused whatever
   * type it declared, and removed. Mutant: the declared type trusted.
   *
   * @param folder a temporary folder
   * @throws Exception when the file can't be written
   */
  @Test
  void anUploadThatIsNotAnImageIsRefusedAndRemoved(@TempDir Path folder) throws Exception {
    stubUpload(folder, TEXT, UploadResource.UPLOADED_STATUS, "image/png");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> resolve(new ImageSource(null, null, null, null, UPLOAD_ID), 1024));

    assertTrue(refusal.getMessage().contains("not a supported image"), refusal.getMessage());
    verify(uploadService).removeUploadResource(UPLOAD_ID, USERNAME);
  }

  /**
   * A failed upload is refused and removed; an upload still being received is
   * refused and left in place, so the transfer in progress is not cut.
   *
   * @param folder a temporary folder
   * @throws Exception when the file can't be written
   */
  @Test
  void aFailedUploadIsRemovedAndAnUploadInProgressIsLeftInPlace(@TempDir Path folder) throws Exception {
    stubUpload(folder, PNG, UploadResource.FAILED_STATUS, "image/png");
    assertThrows(IllegalArgumentException.class, () -> resolve(new ImageSource(null, null, null, null, UPLOAD_ID), 1024));
    verify(uploadService).removeUploadResource(UPLOAD_ID, USERNAME);

    UploadService receiving = mock(UploadService.class);
    UploadResource resource = new UploadResource(UPLOAD_ID, "dot.png");
    resource.setStatus(UploadResource.UPLOADING_STATUS);
    when(receiving.getUploadResource(UPLOAD_ID, USERNAME)).thenReturn(resource);
    assertThrows(IllegalStateException.class,
                 () -> UploadToolUtils.resolveImage(attachmentService,
                                                    fileService,
                                                    receiving,
                                                    identity,
                                                    new ImageSource(null, null, null, null, UPLOAD_ID),
                                                    1024));
    verify(receiving, never()).removeUploadResource(anyString(), anyString());
  }

  /**
   * Exactly one source per call: none, or an upload id with any other source,
   * is refused before the upload is looked up. Mutant: the count check
   * removed, so the upload id wins silently.
   */
  @Test
  void exactlyOneSourceIsAccepted() {
    for (ImageSource source : List.of(new ImageSource(null, null, null, null, null),
                                      new ImageSource(null, " ", null, null, " "),
                                      new ImageSource(null, "aGVsbG8=", null, null, UPLOAD_ID),
                                      new ImageSource("https://example.org/a.png", null, null, null, UPLOAD_ID),
                                      new ImageSource(null, null, "activity", "12", UPLOAD_ID),
                                      new ImageSource("https://example.org/a.png", "aGVsbG8=", null, null, null))) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> resolve(source, 1024));
      assertEquals(UploadToolUtils.ONE_IMAGE_SOURCE_MESSAGE, refusal.getMessage());
    }
    verifyNoInteractions(uploadService, attachmentService);
  }

  /**
   * The deprecated overload, which the add-ons call without an upload
   * registry, refuses an upload id rather than ignoring it.
   */
  @Test
  @SuppressWarnings("deprecation")
  void theDeprecatedOverloadRefusesAnUploadId() {
    assertThrows(IllegalArgumentException.class,
                 () -> UploadToolUtils.resolveImage(attachmentService,
                                                    fileService,
                                                    identity,
                                                    new ImageSource(null, null, null, null, UPLOAD_ID),
                                                    1024));
    verifyNoInteractions(uploadService, attachmentService);
  }

  /**
   * An attachment whose stored size is over the limit is refused before its
   * bytes are loaded. Mutant: the stored-size check removed.
   *
   * @throws Exception never
   */
  @Test
  void anAttachmentOverTheLimitIsRefusedBeforeItIsLoaded() throws Exception {
    FileItem file = stubAttachment(PNG, PNG.length, "image/png");

    assertThrows(IllegalArgumentException.class,
                 () -> resolve(new ImageSource(null, null, "activity", "12"), PNG.length - 1L));

    verify(file, never()).getAsByte();
  }

  /**
   * An attachment whose stored size understates its bytes is still bounded by
   * the bytes read. Mutant: the check on the bytes read removed.
   *
   * @throws Exception never
   */
  @Test
  void anAttachmentIsAlsoBoundedByTheBytesRead() throws Exception {
    stubAttachment(PNG, 1, "image/png");

    assertThrows(IllegalArgumentException.class,
                 () -> resolve(new ImageSource(null, null, "activity", "12"), PNG.length - 1L));
  }

  /**
   * An attachment that is not an image is refused, and an image is typed from
   * its bytes rather than its stored type. Mutants: the sniff removed; the
   * stored type returned.
   *
   * @throws Exception never
   */
  @Test
  void anAttachmentMustBeAnImageTypedFromItsBytes() throws Exception {
    stubAttachment(TEXT, TEXT.length, "image/png");
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> resolve(new ImageSource(null, null, "activity", "12"), 1024));
    assertTrue(refusal.getMessage().contains("not a supported image"), refusal.getMessage());

    stubAttachment(PNG, PNG.length, "application/octet-stream");
    FetchedContent image = resolve(new ImageSource(null, null, "activity", "12"), 1024);
    assertEquals("image/png", image.mimeType());
    assertEquals("stored.png", image.fileName());
    verify(attachmentService, org.mockito.Mockito.atLeastOnce()).getAttachmentFileIds("activity", "12", identity);
  }

  /**
   * The deprecated overload keeps reading any attachment, unbounded and
   * whatever its type, as documents' upload_document and onlyoffice's image
   * operations do through it until they move to the new overload.
   *
   * @throws Exception never
   */
  @Test
  @SuppressWarnings("deprecation")
  void theDeprecatedOverloadStillReadsAnyAttachment() throws Exception {
    stubAttachment(TEXT, TEXT.length, "text/plain");

    FetchedContent file = UploadToolUtils.resolveImage(attachmentService,
                                                       fileService,
                                                       identity,
                                                       new ImageSource(null, null, "activity", "12"),
                                                       1);

    assertArrayEquals(TEXT, file.bytes());
    assertEquals("text/plain", file.mimeType());
  }

  /**
   * Registers, for the user, an upload of the given bytes and status.
   *
   * @param folder a temporary folder
   * @param bytes the uploaded bytes
   * @param status the upload status
   * @param declaredType the type the upload declared
   * @throws Exception when the file can't be written
   */
  private void stubUpload(Path folder, byte[] bytes, int status, String declaredType) throws Exception {
    Path file = folder.resolve("upload.bin");
    Files.write(file, bytes);
    UploadResource resource = new UploadResource(UPLOAD_ID, "dot.png");
    resource.setMimeType(declaredType);
    resource.setStoreLocation(file.toString());
    resource.setStatus(status);
    resource.setOwner(USERNAME);
    when(uploadService.getUploadResource(UPLOAD_ID, USERNAME)).thenReturn(resource);
    when(uploadService.removeUploadResource(UPLOAD_ID, USERNAME)).thenReturn(true);
  }

  /**
   * Attaches to activity 12 a file of the given bytes, stored size and type.
   *
   * @param bytes the file's bytes
   * @param storedSize the size its metadata records
   * @param mimeType the type its metadata records
   * @return the file
   * @throws Exception never
   */
  private FileItem stubAttachment(byte[] bytes, long storedSize, String mimeType) throws Exception {
    FileItem file = mock(FileItem.class);
    FileInfo info = mock(FileInfo.class);
    when(info.getSize()).thenReturn(storedSize);
    when(info.getMimetype()).thenReturn(mimeType);
    when(info.getName()).thenReturn("stored.png");
    when(file.getFileInfo()).thenReturn(info);
    when(file.getAsByte()).thenReturn(bytes);
    when(attachmentService.getAttachmentFileIds("activity", "12", identity)).thenReturn(List.of("5"));
    when(fileService.getFile(5L)).thenReturn(file);
    return file;
  }

  /**
   * @param source the image source
   * @param maxBytes the size limit
   * @return the image the new overload resolves
   * @throws Exception when it refuses
   */
  private FetchedContent resolve(ImageSource source, long maxBytes) throws Exception {
    return UploadToolUtils.resolveImage(attachmentService, fileService, uploadService, identity, source, maxBytes);
  }

}

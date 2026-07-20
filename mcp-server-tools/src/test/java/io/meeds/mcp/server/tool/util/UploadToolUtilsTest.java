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
import java.net.InetAddress;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

class UploadToolUtilsTest {

  // --- SSRF guard ----------------------------------------------------------

  @Test
  void blocksPrivateAndSpecialAddresses() throws Exception {
    for (String ip : new String[] { "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.5.6", "192.168.1.1",
        "169.254.169.254", "100.64.0.1", "::1" }) {
      assertTrue(UploadToolUtils.isBlockedAddress(InetAddress.getByName(ip)), ip + " should be blocked");
    }
  }

  @Test
  void allowsPublicAddresses() throws Exception {
    for (String ip : new String[] { "8.8.8.8", "1.1.1.1", "93.184.216.34" }) {
      assertFalse(UploadToolUtils.isBlockedAddress(InetAddress.getByName(ip)), ip + " should be allowed");
    }
  }

  @Test
  void blocksIpv4MappedIpv6Addresses() throws Exception {
    // ::ffff:a.b.c.d embeds an IPv4 address; the embedded address must be checked too
    for (String ip : new String[] { "::ffff:127.0.0.1", "::ffff:100.64.0.1", "::ffff:0.0.0.1" }) {
      assertTrue(UploadToolUtils.isBlockedAddress(InetAddress.getByName(ip)), ip + " should be blocked");
    }
  }

  @Test
  void allowsIpv4MappedIpv6PublicAddresses() throws Exception {
    assertFalse(UploadToolUtils.isBlockedAddress(InetAddress.getByName("::ffff:8.8.8.8")), "::ffff:8.8.8.8 should be allowed");
  }

  @Test
  void assertPublicHttpUrlRejectsBadSchemesAndPrivateHosts() {
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.assertPublicHttpUrl("ftp://8.8.8.8/x"));
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.assertPublicHttpUrl("file:///etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.assertPublicHttpUrl("http:///nohost"));
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.assertPublicHttpUrl("http://127.0.0.1/x"));
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.assertPublicHttpUrl("http://192.168.0.10/x"));
    assertThrows(IllegalArgumentException.class, () -> UploadToolUtils.assertPublicHttpUrl("http://169.254.169.254/latest/meta-data"));
  }

  @Test
  void assertPublicHttpUrlAcceptsPublicLiteralIp() {
    // literal public IP => no DNS lookup, safe in CI
    UploadToolUtils.assertPublicHttpUrl("https://8.8.8.8/image.png");
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

}

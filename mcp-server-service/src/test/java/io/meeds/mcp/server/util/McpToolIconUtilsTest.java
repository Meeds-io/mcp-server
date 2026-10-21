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
package io.meeds.mcp.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.modelcontextprotocol.spec.McpSchema.Icon;

class McpToolIconUtilsTest {

  private static final String DATA_URI_PREFIX = "data:image/svg+xml;base64,";

  /**
   * A bundled name becomes one standard icon: an inlined SVG data URI with the
   * mid-grey fill, usable by a client that has no portal session.
   */
  @Test
  void toMcpIcons_bundledName_returnsDataUriSvgWithFill() {
    List<Icon> icons = McpToolIconUtils.toMcpIcons("fa-clipboard");

    assertEquals(1, icons.size());
    Icon icon = icons.get(0);
    assertEquals("image/svg+xml", icon.mimeType());
    assertEquals(List.of("any"), icon.sizes());
    assertNull(icon.theme());
    assertTrue(icon.src().startsWith(DATA_URI_PREFIX));
    String svg = new String(Base64.getDecoder().decode(icon.src().substring(DATA_URI_PREFIX.length())),
                            StandardCharsets.UTF_8);
    assertTrue(svg.startsWith("<svg fill=\"" + McpToolIconUtils.ICON_FILL + "\" "), svg);
    assertTrue(svg.contains("<path"));
    assertTrue(svg.contains("Font Awesome Free"), "the CC BY attribution stays in the SVG");
  }

  /**
   * A blank, malformed or unbundled name gives no icon, never an exception,
   * and never a file outside the bundled folder.
   *
   * @param iconName the declared name
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { " ", "fa-not-a-bundled-icon", "clipboard", "FA-CLIPBOARD", "fa-", "fa-../NOTICE",
      "fa-clipboard.svg", "fa-clipboard/../tools", "fa--clipboard" })
  void toMcpIcons_unusableName_returnsNoIcon(String iconName) {
    assertTrue(McpToolIconUtils.toMcpIcons(iconName).isEmpty());
  }

  /**
   * An SVG over the inlining limit gives no icon rather than a huge
   * {@code tools/list}.
   */
  @Test
  void toMcpIcons_oversizedSvg_returnsNoIcon() {
    assertTrue(McpToolIconUtils.toMcpIcons("fa-test-oversize").isEmpty());
  }

  /**
   * Every bundled SVG is under the inlining limit, so none is silently
   * dropped at runtime.
   */
  @Test
  void bundledIcons_areAllUnderTheSizeLimit_andResolve() throws Exception {
    // the main folder only, where McpToolIconUtils is compiled: the test
    // classpath adds an oversized fixture
    File mainClasses = new File(McpToolIconUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    File[] files = new File(mainClasses, McpToolIconUtils.ICONS_PATH).listFiles((dir, name) -> name.endsWith(".svg"));
    assertNotNull(files);
    assertTrue(files.length > 0);
    for (File file : files) {
      assertTrue(file.length() <= McpToolIconUtils.MAX_ICON_BYTES, file.getName());
      String iconName = "fa-" + file.getName().replace(".svg", "");
      assertEquals(1, McpToolIconUtils.toMcpIcons(iconName).size(), iconName);
    }
  }

}

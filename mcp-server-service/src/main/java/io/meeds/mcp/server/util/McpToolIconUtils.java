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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import io.modelcontextprotocol.spec.McpSchema.Icon;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the Font Awesome icon name an add-on declares for a tool
 * ({@code fa-clipboard}) into the standard MCP tool icon external clients
 * display: the matching Font Awesome Free solid SVG, bundled in this module
 * under {@value #ICONS_PATH}, inlined as a {@code data:image/svg+xml} URI with
 * a mid-grey fill that reads on light and dark backgrounds. Only the bundled
 * SVGs are served: a name with no bundled file gives no icon.
 */
@Slf4j
public final class McpToolIconUtils {

  /** The classpath folder of the bundled Font Awesome Free solid SVGs. */
  public static final String                     ICONS_PATH     = "mcp-tool-icons/fa-solid/";

  /** The largest SVG file inlined, in bytes. */
  public static final int                        MAX_ICON_BYTES = 8 * 1024;

  /** The fill given to the icon, readable on light and dark backgrounds. */
  public static final String                     ICON_FILL      = "#707070";

  /** The mime type of the published icons. */
  private static final String                    SVG_MIME_TYPE  = "image/svg+xml";

  /** The prefix of every Font Awesome icon name, absent from the file name. */
  private static final String                    ICON_NAME_PREFIX = "fa-";

  /** A well-formed Font Awesome icon name: no dot, no slash, no traversal. */
  private static final Pattern                   ICON_NAME      = Pattern.compile("^fa-[a-z0-9]++(?:-[a-z0-9]++)*+$");

  /** The icons already read, empty for a name with no usable SVG. */
  private static final Map<String, Optional<Icon>> ICONS        = new ConcurrentHashMap<>();

  /**
   * Utility class.
   */
  private McpToolIconUtils() {
  }

  /**
   * @param iconName a Font Awesome icon name, such as {@code fa-clipboard}
   * @return the MCP icons of that name: one data URI icon, or an empty list
   *         when the name is blank, malformed, or has no bundled SVG
   */
  public static List<Icon> toMcpIcons(String iconName) {
    if (StringUtils.isBlank(iconName) || !ICON_NAME.matcher(iconName).matches()) {
      return Collections.emptyList();
    }
    return ICONS.computeIfAbsent(iconName, McpToolIconUtils::loadIcon)
                .map(List::of)
                .orElse(Collections.emptyList());
  }

  /**
   * Reads the bundled SVG of an icon name and inlines it.
   *
   * @param iconName a well-formed Font Awesome icon name
   * @return the icon, or empty when no SVG is bundled for it or the file is
   *         too large
   */
  private static Optional<Icon> loadIcon(String iconName) {
    String path = ICONS_PATH + iconName.substring(ICON_NAME_PREFIX.length()) + ".svg";
    try (InputStream inputStream = McpToolIconUtils.class.getClassLoader().getResourceAsStream(path)) {
      if (inputStream == null) {
        log.warn("No bundled SVG for the tool icon '{}', the tool is published without an icon", iconName);
        return Optional.empty();
      }
      byte[] content = inputStream.readNBytes(MAX_ICON_BYTES + 1);
      if (content.length > MAX_ICON_BYTES) {
        log.warn("The SVG of the tool icon '{}' exceeds {} bytes, the tool is published without an icon",
                 iconName,
                 MAX_ICON_BYTES);
        return Optional.empty();
      }
      String svg = withFill(new String(content, StandardCharsets.UTF_8));
      String src = "data:" + SVG_MIME_TYPE + ";base64,"
          + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
      return Optional.of(new Icon(src, SVG_MIME_TYPE, List.of("any"), null));
    } catch (IOException e) {
      log.warn("Error reading the SVG of the tool icon '{}', the tool is published without an icon", iconName, e);
      return Optional.empty();
    }
  }

  /**
   * @param svg the SVG markup
   * @return the markup with {@value #ICON_FILL} as the root fill, which the
   *         paths inherit
   */
  private static String withFill(String svg) {
    return svg.replaceFirst("<svg ", "<svg fill=\"" + ICON_FILL + "\" ");
  }

}

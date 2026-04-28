/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2025 Meeds Association contact@meeds.io
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

import java.util.Locale;

import org.exoplatform.services.security.Identity;

import io.meeds.social.html.model.HtmlTransformerContext;
import io.meeds.social.html.utils.HtmlUtils;

public class McpToolPluginUtils {

  private McpToolPluginUtils() {
    // Utils class
  }

  public static int getInteger(Integer i, int defaultValue) {
    return i == null || i == 0 ? defaultValue : i;
  }

  public static String transformHtmlContent(String htmlText, Identity aclIdentity, Locale locale) {
    HtmlTransformerContext htmlContext = new HtmlTransformerContext(aclIdentity, locale);
    return HtmlUtils.transform(htmlText, htmlContext);
  }

}

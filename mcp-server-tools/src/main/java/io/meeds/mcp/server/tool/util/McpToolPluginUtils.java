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

  /**
   * The largest page a list tool returns, whatever limit its caller asks for.
   */
  public static final int MAX_LIMIT = 100;

  /**
   * Utility class, not instantiated.
   */
  private McpToolPluginUtils() {
    // Utils class
  }

  /**
   * @param i            the value a tool received, may be {@code null}
   * @param defaultValue the value to use when none, or zero, was received
   * @return the received value, or the default one when it is {@code null} or
   *         zero
   */
  public static int getInteger(Integer i, int defaultValue) {
    return i == null || i == 0 ? defaultValue : i;
  }

  /**
   * Computes the page size a list tool applies: the default one when the
   * caller asked for none, or for zero or less, and never more than
   * {@link #MAX_LIMIT}.
   *
   * @param limit        the limit the caller asked for, may be {@code null}
   * @param defaultValue the page size to use when the caller asked for none
   * @return the page size to apply, at most {@link #MAX_LIMIT}
   */
  public static int clampLimit(Integer limit, int defaultValue) {
    return clampLimit(limit, defaultValue, MAX_LIMIT);
  }

  /**
   * Computes the page size a list tool applies: the default one when the
   * caller asked for none, or for zero or less, and never more than
   * {@code max}.
   *
   * @param limit        the limit the caller asked for, may be {@code null}
   * @param defaultValue the page size to use when the caller asked for none
   * @param max          the largest page size the tool returns
   * @return the page size to apply
   */
  public static int clampLimit(Integer limit, int defaultValue, int max) {
    int value = limit == null || limit <= 0 ? defaultValue : limit;
    return Math.min(value, max);
  }

  /**
   * Transforms an HTML text the way the platform renders it for a viewer.
   *
   * @param htmlText    the HTML text
   * @param aclIdentity the viewer's identity
   * @param locale      the viewer's locale
   * @return the transformed HTML text
   */
  public static String transformHtmlContent(String htmlText, Identity aclIdentity, Locale locale) {
    HtmlTransformerContext htmlContext = new HtmlTransformerContext(aclIdentity, locale);
    return HtmlUtils.transform(htmlText, htmlContext);
  }

}

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
package io.meeds.mcp.server.model;

import java.util.List;
import java.util.Locale;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class UserPromptContext {

  private static final ThreadLocal<UserPromptContext> CONTEXT = new ThreadLocal<>();

  private String                                      conversationId;

  private Long                                        retryMessageId;

  private String                                      retryType;

  private String                                      agentNameId;

  private String                                      userName;

  private Locale                                      locale;

  List<String>                                        contentTypes;

  public static void set(String conversationId,
                         Long retryMessageId,
                         String retryType,
                         String agentNameId,
                         String userName,
                         Locale locale,
                         List<String> contentTypes) {
    CONTEXT.set(new UserPromptContext(conversationId, retryMessageId, retryType, agentNameId, userName, locale, contentTypes));
  }

  public static void set(UserPromptContext userPromptContext) {
    if (userPromptContext == null) {
      clear();
    } else {
      CONTEXT.set(userPromptContext);
    }
  }

  public static UserPromptContext get() {
    return CONTEXT.get();
  }

  public static void clear() {
    CONTEXT.remove();
  }

  public static String userName() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.userName;
  }

  public static String conversationId() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.conversationId;
  }

  public static Long retryMessageId() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.retryMessageId;
  }

  public static String retryType() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.retryType;
  }

  public static String agentNameId() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.agentNameId;
  }

  public static List<String> contentTypes() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.contentTypes;
  }

  public static Locale locale() {
    UserPromptContext userPromptContext = CONTEXT.get();
    return userPromptContext == null ? null : userPromptContext.locale;
  }

}

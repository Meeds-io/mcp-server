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
package io.meeds.mcp.server.model;

/**
 * A limit on the arguments a standing tool approval covers, for example
 * {@code email_domain} / {@code example.com}: only calls whose recipients are
 * all in that domain. The kind and the value are produced and checked by the
 * tool's own {@code McpToolGrantConstraintEvaluator}, never by the client, so
 * the check and the tool read the arguments with one parser.
 *
 * @param kind  the constraint kind, e.g. {@code email_domain}
 * @param value the constraint value, e.g. the normalised domain
 */
public record McpToolGrantConstraint(String kind, String value) {

  /** The only constraint kind of the first phase. */
  public static final String EMAIL_DOMAIN_KIND = "email_domain";

}

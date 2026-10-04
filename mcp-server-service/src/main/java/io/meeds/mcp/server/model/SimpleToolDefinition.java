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

import java.io.IOException;

import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One MCP tool definition, as shipped in an {@code ai-tool-definitions.json}
 * file and as persisted with the administrator's edits. Unknown properties are
 * ignored, so a field added by a newer add-on never drops the whole file on an
 * older server.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimpleToolDefinition implements ToolDefinition {

  @JsonProperty("name")
  private String          name;

  @JsonProperty("title")
  private String          title;

  @JsonProperty("description")
  private String          description;

  @JsonProperty("input_schema")
  @JsonDeserialize(using = JsonObjectToStringDeserializer.class)
  private String          inputSchema;

  @JsonProperty("require_approval")
  private boolean         requireApproval;

  @JsonProperty("disabled")
  private boolean         disabled;

  @JsonProperty("annotations")
  @JsonInclude(Include.NON_EMPTY)
  private ToolAnnotations annotations;

  /**
   * The Font Awesome icon name of the tool ({@code fa-clipboard}), declared by
   * the add-on that ships it. Owned by the add-on: always read from its file,
   * never from the persisted copy.
   */
  @JsonProperty("icon")
  @JsonInclude(Include.NON_EMPTY)
  private String          icon;

  /**
   * Builds a definition with no icon.
   *
   * @param name            the tool name
   * @param title           the readable title
   * @param description     the description given to the model
   * @param inputSchema     the JSON schema of the arguments
   * @param requireApproval whether a call asks the user first
   * @param disabled        whether the tool is switched off
   * @param annotations     the MCP hints, or null
   */
  public SimpleToolDefinition(String name,
                              String title,
                              String description,
                              String inputSchema,
                              boolean requireApproval,
                              boolean disabled,
                              ToolAnnotations annotations) {
    this.name = name;
    this.title = title;
    this.description = description;
    this.inputSchema = inputSchema;
    this.requireApproval = requireApproval;
    this.disabled = disabled;
    this.annotations = annotations;
  }

  /**
   * Copies a definition, its icon included.
   *
   * @param simpleToolDefinition the definition to copy
   */
  public SimpleToolDefinition(SimpleToolDefinition simpleToolDefinition) {
    this(simpleToolDefinition.name,
         simpleToolDefinition.title,
         simpleToolDefinition.description,
         simpleToolDefinition.inputSchema,
         simpleToolDefinition.requireApproval,
         simpleToolDefinition.disabled,
         simpleToolDefinition.annotations);
    this.icon = simpleToolDefinition.icon;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return description;
  }

  public String title() {
    return title;
  }

  @Override
  public String inputSchema() {
    return inputSchema;
  }

  public static class JsonObjectToStringDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      ObjectMapper mapper = (ObjectMapper) p.getCodec();
      return mapper.readTree(p).toString();
    }
  }

}

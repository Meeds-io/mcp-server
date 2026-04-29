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
package io.meeds.mcp.server.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ReactiveElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.meeds.kernel.test.AbstractSpringTest;
import io.meeds.kernel.test.KernelExtension;
import io.meeds.mcp.server.test.McpServiceIntegrationTestSupport.Config;
import io.meeds.spring.AvailableIntegration;

@SpringBootTest(classes = Config.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ExtendWith({ SpringExtension.class, KernelExtension.class })
public abstract class McpServiceIntegrationTestSupport extends AbstractSpringTest {

  @BeforeEach
  public void beginRequest() {
    getContainer();
    begin();
    setUp();
  }

  @AfterEach
  public void endRequest() {
    tearDown();
    end();
  }

  protected void setUp() {
  }

  protected void tearDown() {
  }

  @SpringBootApplication(scanBasePackages = {
    "io.meeds.mcp.server",
    AvailableIntegration.KERNEL_TEST_MODULE,
  })
  @EnableAutoConfiguration(exclude = {
    SecurityAutoConfiguration.class,
    ElasticsearchClientAutoConfiguration.class,
    ElasticsearchRestClientAutoConfiguration.class,
    ReactiveElasticsearchClientAutoConfiguration.class,
    StatelessServerSpecificationFactoryAutoConfiguration.class,
    OAuth2AuthorizationServerAutoConfiguration.class,
    McpServerStreamableHttpWebMvcAutoConfiguration.class,
  })
  @EnableWebSecurity
  @EnableMethodSecurity
  @PropertySource("classpath:application.properties")
  @PropertySource("classpath:application-common.properties")
  @PropertySource("classpath:auth-server.properties")
  @PropertySource("classpath:mcp-server.properties")
  public static class Config {
  }

}

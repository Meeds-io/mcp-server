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
package io.meeds.mcp.server.tool.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import org.exoplatform.container.PortalContainer;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.core.jpa.search.ActivitySearchConnector;
import org.exoplatform.social.core.jpa.search.ProfileSearchConnector;

import io.meeds.kernel.test.AbstractSpringTest;
import io.meeds.kernel.test.KernelExtension;
import io.meeds.spring.AvailableIntegration;

@SpringBootTest(classes = IntegrationTestBase.Config.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ExtendWith({ SpringExtension.class, KernelExtension.class })
public abstract class IntegrationTestBase extends AbstractSpringTest {

  protected static final String     USERNAME = "john";

  @MockitoBean
  protected ProfileSearchConnector  profileSearchConnector;

  @MockitoBean
  protected ActivitySearchConnector activitySearchConnector;

  @BeforeEach
  protected void beginRequest() {
    getContainer();
    registerServiceMocks();

    Identity userIdentity = new Identity(USERNAME);
    ConversationState state = new ConversationState(userIdentity);
    ConversationState.setCurrent(state);

    begin();
    setUp();
  }

  @AfterEach
  protected void endRequest() {
    ConversationState.setCurrent(null);
    tearDown();
    end();
  }

  protected void setUp() {
    // Add Test Class init behavior here
  }

  protected void tearDown() {
    // Add Test Class init behavior here
  }

  protected void registerServiceMocks() {
    registerServiceMock(ProfileSearchConnector.class, profileSearchConnector);
    registerServiceMock(ActivitySearchConnector.class, activitySearchConnector);
  }

  protected <T> void registerServiceMock(Class<T> componentType, T service) {
    PortalContainer container = getContainer();
    T existingService = container.getComponentInstanceOfType(componentType);
    if (existingService != null) {
      container.unregisterComponent(componentType);
    }
    container.registerComponentInstance(componentType, service);
  }

  @SpringBootApplication(scanBasePackages = {
    "io.meeds.mcp.server.tool",
    "io.meeds.portal",
    "io.meeds.social",
    AvailableIntegration.KERNEL_TEST_MODULE,
    AvailableIntegration.JPA_MODULE,
  }, exclude = {
    LiquibaseAutoConfiguration.class,
    ElasticsearchClientAutoConfiguration.class,
    ElasticsearchRestClientAutoConfiguration.class,
  })
  @EnableJpaRepositories(basePackages = {
    "io.meeds.mcp.server.tool",
    "io.meeds.portal",
    "io.meeds.social",
  })
  @PropertySource("classpath:application.properties")
  @PropertySource("classpath:application-common.properties")
  public static class Config {
  }

}

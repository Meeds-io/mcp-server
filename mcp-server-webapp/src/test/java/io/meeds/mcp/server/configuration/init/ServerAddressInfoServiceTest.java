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
package io.meeds.mcp.server.configuration.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.container.monitor.jvm.J2EEServerInfo;

@ExtendWith(MockitoExtension.class)
class ServerAddressInfoServiceTest {

  private static final String      CATALINA_TYPE_CONNECTOR = "Catalina:type=Connector,*";

  private static final String      PROP_LOCAL_HTTP_URL     = "meeds.mcp.server.local-url";

  @Mock
  private J2EEServerInfo           serverInfo;

  @Mock
  private MBeanServer              mBeanServer;

  @InjectMocks
  private ServerAddressInfoService service;

  @BeforeEach
  void setUp() {
    when(serverInfo.getMBeanServer()).thenReturn(mBeanServer);
    System.clearProperty(PROP_LOCAL_HTTP_URL);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(PROP_LOCAL_HTTP_URL);
  }

  @Test
  void init_shouldComputeLocalUrl_andSetSystemProperty_whenPropertyBlank() throws Exception { // NOSONAR
    // Given
    ObjectName connector = new ObjectName("Catalina:type=Connector,port=8080");
    Set<ObjectName> connectors = new HashSet<>();
    connectors.add(connector);

    when(mBeanServer.queryNames(eq(new ObjectName(CATALINA_TYPE_CONNECTOR)), isNull())).thenReturn(connectors);
    when(mBeanServer.getAttribute(connector, "protocol")).thenReturn("org.apache.coyote.http11.Http11NioProtocol");
    when(mBeanServer.getAttribute(connector, "port")).thenReturn(8080);
    when(mBeanServer.getAttribute(connector, "address")).thenReturn(InetAddress.getByName("0.0.0.0"));
    when(mBeanServer.getAttribute(connector, "SSLEnabled")).thenReturn(Boolean.FALSE);

    // When
    service.init();

    // Then
    assertEquals(8080, service.getPort());
    assertEquals("127.0.0.1", service.getAddress());
    assertEquals("http", service.getProtocol());
    assertEquals("http://127.0.0.1:8080", service.getLocalHttpUrl());
    assertEquals("http://127.0.0.1:8080", System.getProperty(PROP_LOCAL_HTTP_URL));
  }

  @Test
  void init_shouldNotOverrideSystemProperty_whenAlreadySet() throws Exception { // NOSONAR
    // Given
    System.setProperty(PROP_LOCAL_HTTP_URL, "http://already-set:9999");

    ObjectName connector = new ObjectName("Catalina:type=Connector,port=8443");
    Set<ObjectName> connectors = Collections.singleton(connector);

    when(mBeanServer.queryNames(eq(new ObjectName(CATALINA_TYPE_CONNECTOR)), isNull())).thenReturn(connectors);

    when(mBeanServer.getAttribute(connector, "protocol")).thenReturn("org.apache.coyote.http11.Http11NioProtocol");
    when(mBeanServer.getAttribute(connector, "port")).thenReturn(8443);
    when(mBeanServer.getAttribute(connector, "address")).thenReturn(InetAddress.getByName("127.0.0.1"));
    when(mBeanServer.getAttribute(connector, "SSLEnabled")).thenReturn(Boolean.TRUE);

    service.init();

    assertEquals("https://127.0.0.1:8443", service.getLocalHttpUrl());
    assertEquals("http://already-set:9999", System.getProperty(PROP_LOCAL_HTTP_URL));
  }

  @Test
  void init_shouldLeaveLocalUrlNull_whenNoConnectorsFound() throws Exception { // NOSONAR
    when(mBeanServer.queryNames(eq(new ObjectName(CATALINA_TYPE_CONNECTOR)), isNull())).thenReturn(Collections.emptySet());

    service.init();

    assertNull(service.getLocalHttpUrl());
    verify(mBeanServer, never()).getAttribute(org.mockito.ArgumentMatchers.any(), eq("port"));
  }
}

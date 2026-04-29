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
package io.meeds.mcp.server.service;

import java.net.InetAddress;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import org.exoplatform.container.monitor.jvm.J2EEServerInfo;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ServerAddressInfoService {

  private final J2EEServerInfo serverInfo;

  @Getter
  private int                  port;

  @Getter
  private String               address;

  @Getter
  private String               protocol;

  @Getter
  private String               localHttpUrl;

  public ServerAddressInfoService(J2EEServerInfo serverInfo) {
    this.serverInfo = serverInfo;
  }

  @SneakyThrows
  @PostConstruct
  protected void init() {
    computeServerUrl();
    if (StringUtils.isBlank(System.getProperty("meeds.mcp.server.local-url"))
        && StringUtils.isNotBlank(localHttpUrl)) {
      System.setProperty("meeds.mcp.server.local-url", localHttpUrl);
    }
  }

  private void computeServerUrl() throws MalformedObjectNameException {
    MBeanServer mBeanServer = serverInfo.getMBeanServer();
    ObjectName connectorObjectName = new ObjectName("Catalina:type=Connector,*");
    Set<ObjectName> connectorNames = mBeanServer.queryNames(connectorObjectName, null);
    if (CollectionUtils.isNotEmpty(connectorNames)) {
      ObjectName objectName = connectorNames.stream()
                                            .filter(name -> {
                                              Object protocolClass = getAttribute(name, "protocol");
                                              return protocolClass != null && protocolClass.toString().contains("Http");
                                            })
                                            .findFirst()
                                            .orElse(null);
      try {
        initPort(objectName);
        initAddress(objectName);
        initProtocol(objectName);

        this.localHttpUrl = "%s://%s:%s".formatted(protocol,
                                                   address,
                                                   port);
        log.info("Local Server HTTP URL: {}", this.localHttpUrl);
      } catch (Exception e) {
        log.warn("Error while retrieving Local HTTP URL", e);
      }
    }
  }

  private void initProtocol(ObjectName objectName) {
    Boolean sslEnabled = getAttribute(objectName, "SSLEnabled");
    this.protocol = sslEnabled == null || !sslEnabled.booleanValue() ? "http" : "https";
  }

  private void initAddress(ObjectName objectName) {
    InetAddress inetAddress = getAttribute(objectName, "address");
    if (inetAddress == null
        || StringUtils.equals(inetAddress.getHostAddress(), "0.0.0.0")) {
      this.address = "127.0.0.1";
    } else {
      this.address = inetAddress.getHostAddress();
    }
  }

  private void initPort(ObjectName objectName) {
    this.port = getAttribute(objectName, "port");
  }

  @SneakyThrows
  @SuppressWarnings("unchecked")
  private <T> T getAttribute(ObjectName name, String attribute) {
    return (T) serverInfo.getMBeanServer().getAttribute(name, attribute);
  }

}

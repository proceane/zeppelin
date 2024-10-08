/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.zeppelin.service;

import org.apache.zeppelin.MiniZeppelinServer;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.rest.AbstractTestRestApi;
import org.apache.zeppelin.user.AuthenticationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class ConfigurationServiceTest extends AbstractTestRestApi {

  private static ConfigurationService configurationService;

  private ServiceContext context =
      new ServiceContext(AuthenticationInfo.ANONYMOUS, new HashSet<>());

  private ServiceCallback callback = mock(ServiceCallback.class);

  private static MiniZeppelinServer zepServer;

  @BeforeAll
  public static void init() throws Exception {
    zepServer = new MiniZeppelinServer(ConfigurationServiceTest.class.getSimpleName());
    zepServer.getZeppelinConfiguration().setProperty(
        ZeppelinConfiguration.ConfVars.ZEPPELIN_HELIUM_REGISTRY.getVarName(),
        "helium");
    zepServer.start();
    configurationService = zepServer.getService(ConfigurationService.class);
  }

  @AfterAll
  public static void destroy() throws Exception {
    zepServer.destroy();
  }

  @BeforeEach
  void setUp() {
    zConf = zepServer.getZeppelinConfiguration();
  }

  @Test
  void testFetchConfiguration() throws IOException {
    Map<String, String> properties = configurationService.getAllProperties(context, callback);
    verify(callback).onSuccess(properties, context);
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      assertFalse(entry.getKey().contains("password"));
    }

    reset(callback);
    properties = configurationService.getPropertiesWithPrefix("zeppelin.server", context, callback);
    verify(callback).onSuccess(properties, context);
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      assertFalse(entry.getKey().contains("password"));
      assertTrue(entry.getKey().startsWith("zeppelin.server"));
    }
  }
}

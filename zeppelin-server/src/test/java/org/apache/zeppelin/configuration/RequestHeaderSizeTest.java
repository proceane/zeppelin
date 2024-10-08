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
package org.apache.zeppelin.configuration;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.zeppelin.MiniZeppelinServer;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.rest.AbstractTestRestApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestHeaderSizeTest extends AbstractTestRestApi {
  private static final int REQUEST_HEADER_MAX_SIZE = 20000;

  private static MiniZeppelinServer zep;

  @BeforeAll
  static void init() throws Exception {
    zep = new MiniZeppelinServer(RequestHeaderSizeTest.class.getSimpleName());
    zep.getZeppelinConfiguration().setProperty(ConfVars.ZEPPELIN_SERVER_JETTY_REQUEST_HEADER_SIZE
        .getVarName(), String.valueOf(REQUEST_HEADER_MAX_SIZE));
    zep.start();
  }

  @AfterAll
  static void destroy() throws Exception {
    zep.destroy();
  }

  @BeforeEach
  void setup() {
    zConf = zep.getZeppelinConfiguration();
  }

  @Test
  void increased_request_header_size_do_not_cause_431_when_request_size_is_over_8K()
      throws Exception {
    CloseableHttpClient client = HttpClients.createDefault();
    HttpGet httpGet = new HttpGet(getUrlToTest(zConf) + "/version");
    String headerValue = RandomStringUtils.randomAlphanumeric(REQUEST_HEADER_MAX_SIZE - 2000);
    httpGet.setHeader("not_too_large_header", headerValue);
    CloseableHttpResponse response = client.execute(httpGet);
    assertThat(response.getStatusLine().getStatusCode(), is(HttpStatus.SC_OK));
    response.close();

    httpGet = new HttpGet(getUrlToTest(zConf) + "/version");
    headerValue = RandomStringUtils.randomAlphanumeric(REQUEST_HEADER_MAX_SIZE + 2000);
    httpGet.setHeader("too_large_header", headerValue);
    response = client.execute(httpGet);
    assertThat(response.getStatusLine().getStatusCode(), is(431));
    response.close();
    client.close();
  }
}

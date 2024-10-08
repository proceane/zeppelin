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

package org.apache.zeppelin.integration;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsResponse;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.zeppelin.test.DownloadUtils;
import org.apache.zeppelin.MiniZeppelinServer;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.interpreter.ExecutionContext;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterFactory;
import org.apache.zeppelin.interpreter.InterpreterOption;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterSetting;
import org.apache.zeppelin.interpreter.InterpreterSettingManager;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

public abstract class SparkIntegrationTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(SparkIntegrationTest.class);

  private static MiniHadoopCluster hadoopCluster;
  private static InterpreterFactory interpreterFactory;
  protected static InterpreterSettingManager interpreterSettingManager;

  private String sparkVersion;
  private String hadoopVersion;
  private String sparkHome;

  private static MiniZeppelinServer zepServer;

  public void prepareSpark(String sparkVersion, String hadoopVersion) {
    LOGGER.info("Testing Spark Version: " + sparkVersion);
    LOGGER.info("Testing Hadoop Version: " + hadoopVersion);
    this.sparkVersion = sparkVersion;
    this.hadoopVersion = hadoopVersion;
    this.sparkHome = DownloadUtils.downloadSpark(sparkVersion, hadoopVersion);
  }

  @BeforeAll
  static void init() throws Exception {
    hadoopCluster = new MiniHadoopCluster();
    hadoopCluster.start();

    zepServer = new MiniZeppelinServer(SparkIntegrationTest.class.getSimpleName());
    zepServer.addInterpreter("sh");
    zepServer.addInterpreter("spark");
    zepServer.addInterpreter("spark-submit");
    zepServer.copyBinDir();
    zepServer.copyLogProperties();
    zepServer.getZeppelinConfiguration().setProperty(ZeppelinConfiguration.ConfVars.ZEPPELIN_HELIUM_REGISTRY.getVarName(),
        "helium");
    zepServer.start();
    interpreterSettingManager = zepServer.getService(InterpreterSettingManager.class);
    interpreterFactory = zepServer.getService(InterpreterFactory.class);
  }

  @AfterAll
  public static void tearDown() throws Exception {
    zepServer.destroy();
    if (hadoopCluster != null) {
      hadoopCluster.stop();
    }
  }

  protected void setUpSparkInterpreterSetting(InterpreterSetting interpreterSetting) {
    if (isSpark3()) {
      // spark3 doesn't support yarn-client and yarn-cluster anymore, use
      // spark.master and spark.submit.deployMode instead
      String sparkMaster = interpreterSetting.getJavaProperties().getProperty("spark.master");
      if (sparkMaster.equals("yarn-client")) {
        interpreterSetting.setProperty("spark.master", "yarn");
        interpreterSetting.setProperty("spark.submit.deployMode", "client");
      } else if (sparkMaster.equals("yarn-cluster")){
        interpreterSetting.setProperty("spark.master", "yarn");
        interpreterSetting.setProperty("spark.submit.deployMode", "cluster");
      } else if (sparkMaster.startsWith("local")) {
        interpreterSetting.setProperty("spark.submit.deployMode", "client");
      }
    }
  }

  /**
   * Configures ivy to download jar libraries only from remote.
   *
   * @param interpreterSetting
   * @throws IOException
   */
  private void setupIvySettings(InterpreterSetting interpreterSetting) throws IOException {
    String ivysettingsContent = IOUtils.toString(SparkIntegrationTest.class.getResourceAsStream("/ivysettings.xml"), StandardCharsets.UTF_8.name());
    File ivysettings = zepServer.addConfigFile("ivysettings.xml", ivysettingsContent);
    interpreterSetting.setProperty("spark.jars.ivySettings", ivysettings.getAbsolutePath());
  }

  private boolean isHadoopVersionMatch() {
    String version = org.apache.hadoop.util.VersionInfo.getVersion();
    String majorVersion = version.split("\\.")[0];
    return majorVersion.equals(hadoopVersion.split("\\.")[0]);
  }

  private void testInterpreterBasics() throws IOException, InterpreterException, XmlPullParserException {
    // add jars & packages for testing
    InterpreterSetting sparkInterpreterSetting = interpreterSettingManager.getInterpreterSettingByName("spark");
    sparkInterpreterSetting.setProperty("spark.jars.packages", "com.maxmind.geoip2:geoip2:2.16.1");
    sparkInterpreterSetting.setProperty("SPARK_PRINT_LAUNCH_COMMAND", "true");
    sparkInterpreterSetting.setProperty("zeppelin.python.gatewayserver_address", "127.0.0.1");

    MavenXpp3Reader reader = new MavenXpp3Reader();
    Model model = reader.read(new FileReader("pom.xml"));
    sparkInterpreterSetting.setProperty("spark.jars", new File("target/zeppelin-interpreter-integration-" + model.getVersion() + ".jar").getAbsolutePath());

    // test SparkInterpreter
    Interpreter sparkInterpreter = interpreterFactory.getInterpreter("spark.spark", new ExecutionContext("user1", "note1", "test"));

    InterpreterContext context = new InterpreterContext.Builder().setNoteId("note1").setParagraphId("paragraph_1").build();
    InterpreterResult interpreterResult = sparkInterpreter.interpret("sc.version", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
    String detectedSparkVersion = interpreterResult.message().get(0).getData();
    assertTrue(detectedSparkVersion.contains(this.sparkVersion), detectedSparkVersion + " doesn't contain " + this.sparkVersion);
    interpreterResult = sparkInterpreter.interpret("sc.range(1,10).sum()", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
    assertTrue(interpreterResult.message().get(0).getData().contains("45"), interpreterResult.toString());

    interpreterResult = sparkInterpreter.interpret("sc.getConf.get(\"spark.user.name\")", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
    assertTrue(interpreterResult.message().get(0).getData().contains("user1"), interpreterResult.toString());

    // test jars & packages can be loaded correctly
    interpreterResult = sparkInterpreter.interpret("import org.apache.zeppelin.interpreter.integration.DummyClass\n" +
            "import com.maxmind.geoip2._", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());

    // test PySparkInterpreter
    Interpreter pySparkInterpreter = interpreterFactory.getInterpreter("spark.pyspark", new ExecutionContext("user1", "note1", "test"));
    interpreterResult = pySparkInterpreter.interpret("sqlContext.createDataFrame([(1,'a'),(2,'b')], ['id','name']).registerTempTable('test')", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());

    // test IPySparkInterpreter
    Interpreter ipySparkInterpreter = interpreterFactory.getInterpreter("spark.ipyspark", new ExecutionContext("user1", "note1", "test"));
    interpreterResult = ipySparkInterpreter.interpret("sqlContext.table('test').show()", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());

    // test SparkSQLInterpreter
    Interpreter sqlInterpreter = interpreterFactory.getInterpreter("spark.sql", new ExecutionContext("user1", "note1", "test"));
    interpreterResult = sqlInterpreter.interpret("select count(1) as c from test", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
    assertEquals(InterpreterResult.Type.TABLE, interpreterResult.message().get(0).getType(), interpreterResult.toString());
    assertEquals("c\n2\n", interpreterResult.message().get(0).getData(), interpreterResult.toString());

    // test SparkRInterpreter
    Interpreter sparkrInterpreter = interpreterFactory.getInterpreter("spark.r", new ExecutionContext("user1", "note1", "test"));
    interpreterResult = sparkrInterpreter.interpret("df <- as.DataFrame(faithful)\nhead(df)", context);
    assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
    assertEquals(InterpreterResult.Type.TEXT, interpreterResult.message().get(0).getType(), interpreterResult.toString());
    assertTrue( interpreterResult.message().get(0).getData().contains("eruptions waiting"), interpreterResult.toString());
  }

  @Test
  public void testLocalMode() throws IOException, YarnException, InterpreterException, XmlPullParserException {
    assumeTrue(isHadoopVersionMatch(),"Hadoop version mismatch, skip test");

    InterpreterSetting sparkInterpreterSetting = interpreterSettingManager.getInterpreterSettingByName("spark");
    sparkInterpreterSetting.setProperty("spark.master", "local[*]");
    sparkInterpreterSetting.setProperty("SPARK_HOME", sparkHome);
    sparkInterpreterSetting.setProperty("ZEPPELIN_CONF_DIR", zepServer.getZeppelinConfiguration().getConfDir());
    sparkInterpreterSetting.setProperty("zeppelin.spark.useHiveContext", "false");
    sparkInterpreterSetting.setProperty("zeppelin.pyspark.useIPython", "false");
    sparkInterpreterSetting.setProperty("zeppelin.spark.scala.color", "false");
    sparkInterpreterSetting.setProperty("zeppelin.spark.deprecatedMsg.show", "false");
    sparkInterpreterSetting.setProperty("spark.user.name", "#{user}");

    try {
      setUpSparkInterpreterSetting(sparkInterpreterSetting);
      setupIvySettings(sparkInterpreterSetting);
      testInterpreterBasics();

      // no yarn application launched
      GetApplicationsRequest request = GetApplicationsRequest.newInstance(EnumSet.of(YarnApplicationState.RUNNING));
      GetApplicationsResponse response = hadoopCluster.getYarnCluster().getResourceManager().getClientRMService().getApplications(request);
      assertEquals(0, response.getApplicationList().size());
    } finally {
      interpreterSettingManager.close();
    }
  }

  @Test
  public void testYarnClientMode() throws IOException, YarnException, InterruptedException, InterpreterException, XmlPullParserException {
    assumeTrue(isHadoopVersionMatch(), "Hadoop version mismatch, skip test");

    InterpreterSetting sparkInterpreterSetting = interpreterSettingManager.getInterpreterSettingByName("spark");
    sparkInterpreterSetting.setProperty("spark.master", "yarn-client");
    sparkInterpreterSetting.setProperty("HADOOP_CONF_DIR", hadoopCluster.getConfigPath());
    sparkInterpreterSetting.setProperty("SPARK_HOME", sparkHome);
    sparkInterpreterSetting.setProperty("ZEPPELIN_CONF_DIR", zepServer.getZeppelinConfiguration().getConfDir());
    sparkInterpreterSetting.setProperty("zeppelin.spark.useHiveContext", "false");
    sparkInterpreterSetting.setProperty("zeppelin.pyspark.useIPython", "false");
    sparkInterpreterSetting.setProperty("PYSPARK_PYTHON", getPythonExec());
    sparkInterpreterSetting.setProperty("spark.driver.memory", "512m");
    sparkInterpreterSetting.setProperty("zeppelin.spark.scala.color", "false");
    sparkInterpreterSetting.setProperty("zeppelin.spark.deprecatedMsg.show", "false");
    sparkInterpreterSetting.setProperty("spark.user.name", "#{user}");
    sparkInterpreterSetting.setProperty("zeppelin.spark.run.asLoginUser", "false");
    sparkInterpreterSetting.setProperty("spark.r.command", getRScriptExec());

    try {
      setUpSparkInterpreterSetting(sparkInterpreterSetting);
      setupIvySettings(sparkInterpreterSetting);
      testInterpreterBasics();

      // 1 yarn application launched
      GetApplicationsRequest request = GetApplicationsRequest.newInstance(EnumSet.of(YarnApplicationState.RUNNING));
      GetApplicationsResponse response = hadoopCluster.getYarnCluster().getResourceManager().getClientRMService().getApplications(request);
      assertEquals(1, response.getApplicationList().size());

    } finally {
      interpreterSettingManager.close();
      waitForYarnAppCompleted(30 * 1000);
    }
  }

  private void waitForYarnAppCompleted(int timeout) throws YarnException {
    long start = System.currentTimeMillis();
    boolean yarnAppCompleted = false;
    while ((System.currentTimeMillis() - start) < timeout ) {
      GetApplicationsRequest request = GetApplicationsRequest.newInstance(EnumSet.of(YarnApplicationState.RUNNING));
      GetApplicationsResponse response = hadoopCluster.getYarnCluster().getResourceManager().getClientRMService().getApplications(request);
      if (response.getApplicationList().isEmpty()) {
        yarnAppCompleted = true;
        break;
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    assertTrue(yarnAppCompleted, "Yarn app is not completed in " + timeout + " milliseconds.");
  }

  @Test
  public void testYarnClusterMode() throws IOException, YarnException, InterruptedException, InterpreterException, XmlPullParserException {
    assumeTrue(isHadoopVersionMatch(), "Hadoop version mismatch, skip test");

    InterpreterSetting sparkInterpreterSetting = interpreterSettingManager.getInterpreterSettingByName("spark");
    sparkInterpreterSetting.setProperty("spark.master", "yarn-cluster");
    sparkInterpreterSetting.setProperty("HADOOP_CONF_DIR", hadoopCluster.getConfigPath());
    sparkInterpreterSetting.setProperty("SPARK_HOME", sparkHome);
    sparkInterpreterSetting.setProperty("ZEPPELIN_CONF_DIR", zepServer.getZeppelinConfiguration().getConfDir());
    sparkInterpreterSetting.setProperty("zeppelin.spark.useHiveContext", "false");
    sparkInterpreterSetting.setProperty("zeppelin.pyspark.useIPython", "false");
    sparkInterpreterSetting.setProperty("PYSPARK_PYTHON", getPythonExec());
    sparkInterpreterSetting.setProperty("spark.pyspark.python", getPythonExec());
    sparkInterpreterSetting.setProperty("zeppelin.R.cmd", getRExec());
    sparkInterpreterSetting.setProperty("spark.r.command", getRScriptExec());
    sparkInterpreterSetting.setProperty("spark.driver.memory", "512m");
    sparkInterpreterSetting.setProperty("zeppelin.spark.scala.color", "false");
    sparkInterpreterSetting.setProperty("zeppelin.spark.deprecatedMsg.show", "false");
    sparkInterpreterSetting.setProperty("spark.user.name", "#{user}");
    sparkInterpreterSetting.setProperty("zeppelin.spark.run.asLoginUser", "false");
    // parameters with whitespace
    sparkInterpreterSetting.setProperty("spark.app.name", "hello spark");

    String yarnAppId = null;
    try {
      setUpSparkInterpreterSetting(sparkInterpreterSetting);
      setupIvySettings(sparkInterpreterSetting);
      testInterpreterBasics();

      // 1 yarn application launched
      GetApplicationsRequest request = GetApplicationsRequest.newInstance(EnumSet.of(YarnApplicationState.RUNNING));
      GetApplicationsResponse response = hadoopCluster.getYarnCluster().getResourceManager().getClientRMService().getApplications(request);
      assertEquals(1, response.getApplicationList().size());
      assertEquals("hello spark", response.getApplicationList().get(0).getName());
      yarnAppId = response.getApplicationList().get(0).getApplicationId().toString();
    } finally {
      interpreterSettingManager.close();
      waitForYarnAppCompleted(30 * 1000);

      if (yarnAppId != null) {
        // ensure yarn app is finished with SUCCEEDED status.
        final String finalYarnAppId = yarnAppId;
        GetApplicationsRequest request = GetApplicationsRequest.newInstance(EnumSet.of(YarnApplicationState.FINISHED));
        GetApplicationsResponse response = hadoopCluster.getYarnCluster().getResourceManager().getClientRMService().getApplications(request);
        List<ApplicationReport> apps = response.getApplicationList().stream().filter(app -> app.getApplicationId().toString().equals(finalYarnAppId)).collect(Collectors.toList());
        assertEquals(1, apps.size());
        assertEquals(FinalApplicationStatus.SUCCEEDED, apps.get(0).getFinalApplicationStatus());
      }
    }
  }

  @Test
  public void testSparkSubmit() throws InterpreterException {
    assumeTrue(isHadoopVersionMatch(), "Hadoop version mismatch, skip test");

    try {
      InterpreterSetting sparkSubmitInterpreterSetting = interpreterSettingManager.getInterpreterSettingByName("spark-submit");
      sparkSubmitInterpreterSetting.setProperty("SPARK_HOME", sparkHome);
      // test SparkSubmitInterpreter
      InterpreterContext context = new InterpreterContext.Builder().setNoteId("note1").setParagraphId("paragraph_1").build();
      Interpreter sparkSubmitInterpreter = interpreterFactory.getInterpreter("spark-submit", new ExecutionContext("user1", "note1", "test"));
      InterpreterResult interpreterResult = sparkSubmitInterpreter.interpret("--class org.apache.spark.examples.SparkPi " + sparkHome + "/examples/jars/spark-examples*.jar ", context);

      assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
    } finally {
      interpreterSettingManager.close();
    }
  }

  @Test
  public void testScopedMode() throws InterpreterException {
    assumeTrue(isHadoopVersionMatch(), "Hadoop version mismatch, skip test");

    InterpreterSetting sparkInterpreterSetting = interpreterSettingManager.getInterpreterSettingByName("spark");
    try {
      sparkInterpreterSetting.setProperty("spark.master", "local[*]");
      sparkInterpreterSetting.setProperty("spark.submit.deployMode", "client");
      sparkInterpreterSetting.setProperty("SPARK_HOME", sparkHome);
      sparkInterpreterSetting.setProperty("ZEPPELIN_CONF_DIR", zepServer.getZeppelinConfiguration().getConfDir());
      sparkInterpreterSetting.setProperty("zeppelin.spark.useHiveContext", "false");
      sparkInterpreterSetting.setProperty("zeppelin.pyspark.useIPython", "false");
      sparkInterpreterSetting.setProperty("zeppelin.spark.scala.color", "false");
      sparkInterpreterSetting.setProperty("zeppelin.spark.deprecatedMsg.show", "false");
      sparkInterpreterSetting.getOption().setPerNote(InterpreterOption.SCOPED);


      Interpreter sparkInterpreter1 = interpreterFactory.getInterpreter("spark.spark", new ExecutionContext("user1", "note1", "test"));

      InterpreterContext context = new InterpreterContext.Builder().setNoteId("note1").setParagraphId("paragraph_1").build();
      InterpreterResult interpreterResult = sparkInterpreter1.interpret("sc.range(1,10).map(e=>e+1).sum()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
      assertTrue(interpreterResult.message().get(0).getData().contains("54"), interpreterResult.toString());

      Interpreter sparkInterpreter2 = interpreterFactory.getInterpreter("spark.spark", new ExecutionContext("user1", "note2", "test"));
      assertNotEquals(sparkInterpreter1, sparkInterpreter2);

      context = new InterpreterContext.Builder().setNoteId("note2").setParagraphId("paragraph_1").build();
      interpreterResult = sparkInterpreter2.interpret("sc.range(1,10).map(e=>e+1).sum()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, interpreterResult.code(), interpreterResult.toString());
      assertTrue(interpreterResult.message().get(0).getData().contains("54"), interpreterResult.toString());
    } finally {
      interpreterSettingManager.close();

      if (sparkInterpreterSetting != null) {
        // reset InterpreterOption so that it won't affect other tests.
        sparkInterpreterSetting.getOption().setPerNote(InterpreterOption.SHARED);
      }
    }
  }
  
  private boolean isSpark3() {
    return this.sparkVersion.startsWith("3.");
  }

  private String getPythonExec() throws IOException, InterruptedException {
    Process process = Runtime.getRuntime().exec(new String[]{"which", "python"});
    if (process.waitFor() != 0) {
      throw new RuntimeException("Fail to run command: which python.");
    }
    return IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8).trim();
  }

  private String getRScriptExec() throws IOException, InterruptedException {
    Process process = Runtime.getRuntime().exec(new String[]{"which", "Rscript"});
    if (process.waitFor() != 0) {
      throw new RuntimeException("Fail to run command: which Rscript.");
    }
    return IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8).trim();
  }

  private String getRExec() throws IOException, InterruptedException {
    Process process = Runtime.getRuntime().exec(new String[]{"which", "R"});
    if (process.waitFor() != 0) {
      throw new RuntimeException("Fail to run command: which R.");
    }
    return IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8).trim();
  }
}

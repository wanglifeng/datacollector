/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.hdfs;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.google.common.annotations.VisibleForTesting;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigDefBean;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.Target;
import com.streamsets.pipeline.api.ValueChooserModel;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.api.el.SdcEL;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.TimeZoneChooserValues;
import com.streamsets.pipeline.lib.el.DataUtilEL;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.TimeEL;
import com.streamsets.pipeline.lib.el.TimeNowEL;
import com.streamsets.pipeline.stage.destination.hdfs.writer.ActiveRecordWriters;
import com.streamsets.pipeline.stage.destination.hdfs.writer.RecordWriterManager;
import com.streamsets.pipeline.stage.destination.lib.DataGeneratorFormatConfig;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.util.KerberosUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class HdfsTargetConfigBean {

  private static final Logger LOG = LoggerFactory.getLogger(HdfsTargetConfigBean.class);
  private static final int MEGA_BYTE = 1024 * 1024;
  public static final String HDFS_TARGET_CONFIG_BEAN_PREFIX = "hdfsTargetConfigBean.";

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    label = "Hadoop FS URI",
    description = "",
    displayPosition = 10,
    group = "HADOOP_FS"
  )
  public String hdfsUri;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    label = "HDFS User",
    description = "If set, the data collector will write to HDFS as this user. " +
      "The data collector user must be configured as a proxy user in HDFS.",
    displayPosition = 20,
    group = "HADOOP_FS"
  )
  public String hdfsUser;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    label = "Kerberos Authentication",
    defaultValue = "false",
    description = "",
    displayPosition = 30,
    group = "HADOOP_FS"
  )
  public boolean hdfsKerberos;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    defaultValue = "",
    label = "Hadoop FS Configuration Directory",
    description = "An SDC resource directory or symbolic link with HDFS configuration files core-site.xml and hdfs-site.xml",
    displayPosition = 50,
    group = "HADOOP_FS"
  )
  public String hdfsConfDir;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.MAP,
    label = "Hadoop FS Configuration",
    description = "Additional Hadoop properties to pass to the underlying Hadoop FileSystem. These properties " +
      "have precedence over properties loaded via the 'Hadoop FS Configuration Directory' property.",
    displayPosition = 60,
    group = "HADOOP_FS"
  )
  public Map<String, String> hdfsConfigs;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    defaultValue = "sdc-${sdc:id()}",
    label = "Files Prefix",
    description = "File name prefix",
    displayPosition = 105,
    group = "OUTPUT_FILES",
    elDefs = SdcEL.class
  )
  public String uniquePrefix;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "/tmp/out/${YYYY()}-${MM()}-${DD()}-${hh()}",
    label = "Directory Template",
    description = "Template for the creation of output directories. Valid variables are ${YYYY()}, ${MM()}, ${DD()}, " +
      "${hh()}, ${mm()}, ${ss()} and {record:value(“/field”)} for values in a field. Directories are " +
      "created based on the smallest time unit variable used.",
    displayPosition = 110,
    group = "OUTPUT_FILES",
    elDefs = {RecordEL.class, TimeEL.class, ExtraTimeEL.class},
    evaluation = ConfigDef.Evaluation.EXPLICIT
  )
  public String dirPathTemplate;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "UTC",
    label = "Data Time Zone",
    description = "Time zone to use to resolve directory paths",
    displayPosition = 120,
    group = "OUTPUT_FILES"
  )
  @ValueChooserModel(TimeZoneChooserValues.class)
  public String timeZoneID;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "${time:now()}",
    label = "Time Basis",
    description = "Time basis to use for a record. Enter an expression that evaluates to a datetime. To use the " +
      "processing time, enter ${time:now()}. To use field values, use '${record:value(\"<filepath>\")}'.",
    displayPosition = 130,
    group = "OUTPUT_FILES",
    elDefs = {RecordEL.class, TimeEL.class, TimeNowEL.class},
    evaluation = ConfigDef.Evaluation.EXPLICIT
  )
  public String timeDriver;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.NUMBER,
    defaultValue = "0",
    label = "Max Records in File",
    description = "Number of records that triggers the creation of a new file. Use 0 to opt out.",
    displayPosition = 140,
    group = "OUTPUT_FILES",
    min = 0
  )
  public long maxRecordsPerFile;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.NUMBER,
    defaultValue = "0",
    label = "Max File Size (MB)",
    description = "Exceeding this size triggers the creation of a new file. Use 0 to opt out.",
    displayPosition = 150,
    group = "OUTPUT_FILES",
    min = 0
  )
  public long maxFileSize;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "NONE",
    label = "Compression Codec",
    description = "",
    displayPosition = 160,
    group = "OUTPUT_FILES"
  )
  @ValueChooserModel(CompressionChooserValues.class)
  public CompressionMode compression;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "",
    label = "Compression Codec Class",
    description = "Use the full class name",
    displayPosition = 170,
    group = "OUTPUT_FILES",
    dependsOn = "compression",
    triggeredByValue = "OTHER"
  )

  public String otherCompression;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "TEXT",
    label = "File Type",
    description = "",
    displayPosition = 100,
    group = "OUTPUT_FILES"
  )
  @ValueChooserModel(FileTypeChooserValues.class)
  public HdfsFileType fileType;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "${uuid()}",
    label = "Sequence File Key",
    description = "Record key for creating Hadoop sequence files. Valid options are " +
      "'${record:value(\"<field-path>\")}' or '${uuid()}'",
    displayPosition = 180,
    group = "OUTPUT_FILES",
    dependsOn = "fileType",
    triggeredByValue = "SEQUENCE_FILE",
    elDefs = {RecordEL.class, DataUtilEL.class}
  )
  public String keyEl;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "BLOCK",
    label = "Compression Type",
    description = "Compression type if using a CompressionCodec",
    displayPosition = 190,
    group = "OUTPUT_FILES",
    dependsOn = "fileType",
    triggeredByValue = "SEQUENCE_FILE"
  )
  @ValueChooserModel(HdfsSequenceFileCompressionTypeChooserValues.class)
  public HdfsSequenceFileCompressionType seqFileCompressionType;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "${1 * HOURS}",
    label = "Late Record Time Limit (secs)",
    description = "Time limit (in seconds) for a record to be written to the corresponding HDFS directory, if the " +
      "limit is exceeded the record will be written to the current late records file. 0 means no limit. " +
      "If a number is used it is considered seconds, it can be multiplied by 'MINUTES' or 'HOURS', ie: " +
      "'${30 * MINUTES}'",
    displayPosition = 200,
    group = "LATE_RECORDS",
    elDefs = {TimeEL.class},
    evaluation = ConfigDef.Evaluation.EXPLICIT
  )
  public String lateRecordsLimit;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "${1 * HOURS}",
      label = "Idle Timeout (secs)",
      description = "Time limit (in seconds) after which a file to which no records are written is closed. " +
          "If a number is used it is considered seconds, it can be multiplied by 'MINUTES' or 'HOURS', ie: " +
          "'${30 * MINUTES}'. Set this to -1 to not close the files on idle.",
      group = "OUTPUT_FILES",
      elDefs = {TimeEL.class},
      evaluation = ConfigDef.Evaluation.EXPLICIT
  )
  public String idleTimeout;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "SEND_TO_ERROR",
    label = "Late Record Handling",
    description = "Action for records considered late.",
    displayPosition = 210,
    group = "LATE_RECORDS"
  )
  @ValueChooserModel(LateRecordsActionChooserValues.class)
  public LateRecordsAction lateRecordsAction;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    defaultValue = "/tmp/late/${YYYY()}-${MM()}-${DD()}",
    label = "Late Record Directory Template",
    description = "Template for the creation of late record directories. Valid variables are ${YYYY()}, ${MM()}, " +
      "${DD()}, ${hh()}, ${mm()}, ${ss()}.",
    displayPosition = 220,
    group = "LATE_RECORDS",
    dependsOn = "lateRecordsAction",
    triggeredByValue = "SEND_TO_LATE_RECORDS_FILE",
    elDefs = {RecordEL.class, TimeEL.class},
    evaluation = ConfigDef.Evaluation.EXPLICIT
  )
  public String lateRecordsDirPathTemplate;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "TEXT",
    label = "Data Format",
    description = "Data Format",
    displayPosition = 100,
    group = "OUTPUT_FILES"
  )
  @ValueChooserModel(DataFormatChooserValues.class)
  public DataFormat dataFormat;

  @ConfigDefBean()
  public DataGeneratorFormatConfig dataGeneratorFormatConfig;

  //private members

  private Configuration hdfsConfiguration;
  private UserGroupInformation loginUgi;
  private long lateRecordsLimitSecs;
  private long idleTimeSecs = -1;
  private ActiveRecordWriters currentWriters;
  private ActiveRecordWriters lateWriters;
  private ELEval timeDriverElEval;
  private CompressionCodec compressionCodec;
  private Counter toHdfsRecordsCounter;
  private Meter toHdfsRecordsMeter;
  private Counter lateRecordsCounter;
  private Meter lateRecordsMeter;

  //public API

  public void init(Stage.Context context, List<Stage.ConfigIssue> issues) {
    boolean hadoopFSValidated = validateHadoopFS(context, issues);

    lateRecordsLimitSecs =
        initTimeConfigs(context, "lateRecordsLimit", lateRecordsLimit, Groups.LATE_RECORDS, issues);
    if (idleTimeout != null && !idleTimeout.isEmpty()) {
      idleTimeSecs = initTimeConfigs(context, "idleTimeout", idleTimeout, Groups.OUTPUT_FILES, issues);
    }
    if (maxFileSize < 0) {
      issues.add(
          context.createConfigIssue(
              Groups.LATE_RECORDS.name(),
              HDFS_TARGET_CONFIG_BEAN_PREFIX + "maxFileSize",
              Errors.HADOOPFS_08
          )
      );
    }

    if (maxRecordsPerFile < 0) {
      issues.add(
          context.createConfigIssue(
              Groups.LATE_RECORDS.name(),
              HDFS_TARGET_CONFIG_BEAN_PREFIX + "maxRecordsPerFile",
              Errors.HADOOPFS_09
          )
      );
    }

    if (uniquePrefix == null) {
      uniquePrefix = "";
    }

    dataGeneratorFormatConfig.init(
        context,
        dataFormat,
        Groups.OUTPUT_FILES.name(),
        HDFS_TARGET_CONFIG_BEAN_PREFIX + "dataGeneratorFormatConfig",
        issues
    );

    SequenceFile.CompressionType compressionType = (seqFileCompressionType != null)
      ? seqFileCompressionType.getType() : null;
    try {
      switch (compression) {
        case OTHER:
          try {
            Class klass = Thread.currentThread().getContextClassLoader().loadClass(otherCompression);
            if (CompressionCodec.class.isAssignableFrom(klass)) {
              compressionCodec = ((Class<? extends CompressionCodec> ) klass).newInstance();
            } else {
              throw new StageException(Errors.HADOOPFS_04, otherCompression);
            }
          } catch (Exception ex1) {
            throw new StageException(Errors.HADOOPFS_05, otherCompression, ex1.toString(), ex1);
          }
          break;
        case NONE:
          break;
        default:
          try {
            compressionCodec = compression.getCodec().newInstance();
          } catch (IllegalAccessException | InstantiationException ex) {
            LOG.info("Error: " + ex.getMessage(), ex.toString(), ex);
            issues.add(context.createConfigIssue(Groups.OUTPUT_FILES.name(), null, Errors.HADOOPFS_48, ex.toString(), ex));
          }
          break;
      }
      if (compressionCodec != null) {
        if (compressionCodec instanceof Configurable) {
          ((Configurable) compressionCodec).setConf(hdfsConfiguration);
        }
      }
    } catch (StageException ex) {
      LOG.info("Validation Error: " + ex.getMessage(), ex.toString(), ex);
      issues.add(context.createConfigIssue(Groups.OUTPUT_FILES.name(), null, ex.getErrorCode(), ex.toString(), ex));
    }

    if(hadoopFSValidated){
      try {
        // Creating RecordWriterManager for dirPathTemplate
        RecordWriterManager mgr = new RecordWriterManager(new URI(hdfsUri), hdfsConfiguration, uniquePrefix,
                dirPathTemplate, TimeZone.getTimeZone(timeZoneID), lateRecordsLimitSecs, maxFileSize * MEGA_BYTE,
                maxRecordsPerFile, fileType, compressionCodec, compressionType, keyEl,
                dataGeneratorFormatConfig.getDataGeneratorFactory(), (Target.Context) context, "dirPathTemplate");

        if (idleTimeSecs > 0) {
          mgr.setIdleTimeoutSeconds(idleTimeSecs);
        }

        // validate if the dirPathTemplate can be resolved by Els constants
        if (mgr.validateDirTemplate(
            Groups.OUTPUT_FILES.name(),
            "dirPathTemplate",
            HDFS_TARGET_CONFIG_BEAN_PREFIX + "dirPathTemplate",
            issues
        )) {
          String newDirPath = mgr.getDirPath(new Date()).toString();
          if (validateHadoopDir(       // permission check on the output directory
              context,
              HDFS_TARGET_CONFIG_BEAN_PREFIX + "dirPathTemplate",
              Groups.OUTPUT_FILES.name(),
              newDirPath, issues
          )) {
            currentWriters = new ActiveRecordWriters(mgr);
          }
        }
      }  catch (Exception ex) {
        LOG.info("Validation Error: " + Errors.HADOOPFS_11.getMessage(), ex.toString(), ex);
        issues.add(context.createConfigIssue(Groups.OUTPUT_FILES.name(), null, Errors.HADOOPFS_11, ex.toString(), ex));
      }

      // Creating RecordWriterManager for Late Records
      if(lateRecordsDirPathTemplate != null && !lateRecordsDirPathTemplate.isEmpty()) {
        try {
          RecordWriterManager mgr = new RecordWriterManager(
                  new URI(hdfsUri),
                  hdfsConfiguration,
                  uniquePrefix,
                  lateRecordsDirPathTemplate,
                  TimeZone.getTimeZone(timeZoneID),
                  lateRecordsLimitSecs,
                  maxFileSize * MEGA_BYTE,
                  maxRecordsPerFile,
                  fileType,
                  compressionCodec,
                  compressionType,
                  keyEl,
                  dataGeneratorFormatConfig.getDataGeneratorFactory(),
                  (Target.Context) context, "lateRecordsDirPathTemplate"
          );

          if (idleTimeSecs > 0) {
            mgr.setIdleTimeoutSeconds(idleTimeSecs);
          }

          // validate if the lateRecordsDirPathTemplate can be resolved by Els constants
          if (mgr.validateDirTemplate(
              Groups.OUTPUT_FILES.name(),
              "lateRecordsDirPathTemplate",
              HDFS_TARGET_CONFIG_BEAN_PREFIX + "lateRecordsDirPathTemplate",
              issues
          )) {
            String newLateRecordPath = mgr.getDirPath(new Date()).toString();
            if (lateRecordsAction == LateRecordsAction.SEND_TO_LATE_RECORDS_FILE &&
                lateRecordsDirPathTemplate != null && !lateRecordsDirPathTemplate.isEmpty() &&
                validateHadoopDir(       // permission check on the late record directory
                    context,
                    HDFS_TARGET_CONFIG_BEAN_PREFIX + "lateRecordsDirPathTemplate",
                    Groups.LATE_RECORDS.name(),
                    newLateRecordPath, issues
            )) {
              lateWriters = new ActiveRecordWriters(mgr);
            }
          }
        } catch (Exception ex) {
          issues.add(context.createConfigIssue(Groups.LATE_RECORDS.name(), null, Errors.HADOOPFS_17,
              ex.toString(), ex));
        }
      }
    }

    timeDriverElEval = context.createELEval("timeDriver");
    try {
      ELVars variables = context.createELVars();
      RecordEL.setRecordInContext(variables, context.createRecord("validationConfigs"));
      TimeNowEL.setTimeNowInContext(variables, new Date());
      context.parseEL(timeDriver);
      timeDriverElEval.eval(variables, timeDriver, Date.class);
    } catch (ELEvalException ex) {
      issues.add(
          context.createConfigIssue(
              Groups.OUTPUT_FILES.name(),
              HDFS_TARGET_CONFIG_BEAN_PREFIX + "timeDriver",
              Errors.HADOOPFS_19,
              ex.toString(),
              ex
          )
      );
    }

    if (issues.isEmpty()) {

      try {
        getUGI().doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            FileSystem fs = getFileSystemForInitDestroy();
            getCurrentWriters().commitOldFiles(fs);
            if (getLateWriters() != null) {
              getLateWriters().commitOldFiles(fs);
            }
            return null;
          }
        });
      } catch (Exception ex) {
        issues.add(context.createConfigIssue(null, null, Errors.HADOOPFS_23, ex.toString(), ex));
      }
      toHdfsRecordsCounter = context.createCounter("toHdfsRecords");
      toHdfsRecordsMeter = context.createMeter("toHdfsRecords");
      lateRecordsCounter = context.createCounter("lateRecords");
      lateRecordsMeter = context.createMeter("lateRecords");
    }
  }

  public void destroy() {
    LOG.info("Destroy");
    try {
      getUGI().doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          if (currentWriters != null) {
            currentWriters.closeAll();
          }
          if (lateWriters != null) {
            lateWriters.closeAll();
          }
          if (loginUgi != null) {
            getFileSystemForInitDestroy().close();
          }
          return null;
        }
      });
    } catch (Exception ex) {
      LOG.warn("Error while closing HDFS FileSystem URI='{}': {}", hdfsUri, ex.toString(), ex);
    }
  }

  private long initTimeConfigs(
      Stage.Context context,
      String configName,
      String configuredValue,
      Groups configGroup,
      List<Stage.ConfigIssue> issues) {
    long timeInSecs = 0;
    try {
      ELEval timeEvaluator = context.createELEval(configName);
      context.parseEL(configuredValue);
      timeInSecs = timeEvaluator.eval(context.createELVars(),
          configuredValue, Long.class);
      if (timeInSecs <= 0) {
        issues.add(
            context.createConfigIssue(
                configGroup.name(),
                HDFS_TARGET_CONFIG_BEAN_PREFIX + configName,
                Errors.HADOOPFS_10
            )
        );
      }
    } catch (Exception ex) {
      issues.add(
          context.createConfigIssue(
              configGroup.name(),
              HDFS_TARGET_CONFIG_BEAN_PREFIX + configName,
              Errors.HADOOPFS_06,
              configuredValue,
              ex.toString(),
              ex
          )
      );
    }
    return timeInSecs;
  }
  Counter getToHdfsRecordsCounter() {
    return toHdfsRecordsCounter;
  }

  Meter getToHdfsRecordsMeter() {
    return toHdfsRecordsMeter;
  }

  Counter getLateRecordsCounter() {
    return lateRecordsCounter;
  }

  Meter getLateRecordsMeter() {
    return lateRecordsMeter;
  }

  String getTimeDriver() {
    return timeDriver;
  }

  ELEval getTimeDriverElEval() {
    return timeDriverElEval;
  }

  UserGroupInformation getUGI() {
    return (hdfsUser.isEmpty()) ? loginUgi : UserGroupInformation.createProxyUser(hdfsUser, loginUgi);
  }

  protected ActiveRecordWriters getCurrentWriters() {
    return currentWriters;
  }

  protected ActiveRecordWriters getLateWriters() {
    return lateWriters;
  }

  @VisibleForTesting
  Configuration getHdfsConfiguration() {
    return hdfsConfiguration;
  }

  @VisibleForTesting
  CompressionCodec getCompressionCodec() throws StageException {
    return compressionCodec;
  }

  @VisibleForTesting
  long getLateRecordLimitSecs() {
    return lateRecordsLimitSecs;
  }

  //private implementation

  private Configuration getHadoopConfiguration(Stage.Context context, List<Stage.ConfigIssue> issues) {
    Configuration conf = new Configuration();
    conf.setClass("fs.file.impl", RawLocalFileSystem.class, FileSystem.class);
    if (hdfsKerberos) {
      conf.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
        UserGroupInformation.AuthenticationMethod.KERBEROS.name());
      try {
        conf.set(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY, "hdfs/_HOST@" + KerberosUtil.getDefaultRealm());
      } catch (Exception ex) {
        if (!hdfsConfigs.containsKey(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY)) {
          issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_28,
            ex.toString()));
        }
      }
    }
    if (hdfsConfDir != null && !hdfsConfDir.isEmpty()) {
      File hadoopConfigDir = new File(hdfsConfDir);
      if ((context.getExecutionMode() == ExecutionMode.CLUSTER_BATCH ||
        context.getExecutionMode() == ExecutionMode.CLUSTER_YARN_STREAMING ||
        context.getExecutionMode() == ExecutionMode.CLUSTER_MESOS_STREAMING) &&
        hadoopConfigDir.isAbsolute()
        ) {
        //Do not allow absolute hadoop config directory in cluster mode
        issues.add(
            context.createConfigIssue(
                Groups.HADOOP_FS.name(),
                HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsConfDir",
                Errors.HADOOPFS_45,
                hdfsConfDir
            )
        );
      } else {
        if (!hadoopConfigDir.isAbsolute()) {
          hadoopConfigDir = new File(context.getResourcesDirectory(), hdfsConfDir).getAbsoluteFile();
        }
        if (!hadoopConfigDir.exists()) {
          issues.add(
              context.createConfigIssue(
                  Groups.HADOOP_FS.name(),
                  HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsConfDir",
                  Errors.HADOOPFS_25,
                  hadoopConfigDir.getPath()
              )
          );
        } else if (!hadoopConfigDir.isDirectory()) {
          issues.add(
              context.createConfigIssue(
                  Groups.HADOOP_FS.name(),
                  HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsConfDir",
                  Errors.HADOOPFS_26,
                  hadoopConfigDir.getPath()
              )
          );
        } else {
          File coreSite = new File(hadoopConfigDir, "core-site.xml");
          if (coreSite.exists()) {
            if (!coreSite.isFile()) {
              issues.add(
                  context.createConfigIssue(
                      Groups.HADOOP_FS.name(),
                      HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsConfDir",
                      Errors.HADOOPFS_27,
                      coreSite.getPath()
                  )
              );
            }
            conf.addResource(new Path(coreSite.getAbsolutePath()));
          }
          File hdfsSite = new File(hadoopConfigDir, "hdfs-site.xml");
          if (hdfsSite.exists()) {
            if (!hdfsSite.isFile()) {
              issues.add(
                  context.createConfigIssue(
                      Groups.HADOOP_FS.name(),
                      HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsConfDir",
                      Errors.HADOOPFS_27,
                      hdfsSite.getPath()
                  )
              );
            }
            conf.addResource(new Path(hdfsSite.getAbsolutePath()));
          }
        }
      }
    }
    for (Map.Entry<String, String> config : hdfsConfigs.entrySet()) {
      conf.set(config.getKey(), config.getValue());
    }
    return conf;
  }

  private boolean validateHadoopFS(Stage.Context context, List<Stage.ConfigIssue> issues) {
    boolean validHapoopFsUri = true;
    // if hdfsUri is empty, we'll use the default fs uri from hdfs config. no validation required.
    if (!hdfsUri.isEmpty()) {
      if (hdfsUri.contains("://")) {
        try {
          new URI(hdfsUri);
        } catch (Exception ex) {
          issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_22, hdfsUri,
              ex.toString(), ex));
          validHapoopFsUri = false;
        }
      } else {
        issues.add(
            context.createConfigIssue(
                Groups.HADOOP_FS.name(),
                HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsUri",
                Errors.HADOOPFS_18,
                hdfsUri
            )
        );
        validHapoopFsUri = false;
      }
    }

    StringBuilder logMessage = new StringBuilder();
    try {
      hdfsConfiguration = getHadoopConfiguration(context, issues);

      hdfsConfiguration.set(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY, hdfsUri);

      // forcing UGI to initialize with the security settings from the stage
      UserGroupInformation.setConfiguration(hdfsConfiguration);
      Subject subject = Subject.getSubject(AccessController.getContext());
      if (UserGroupInformation.isSecurityEnabled()) {
        loginUgi = UserGroupInformation.getUGIFromSubject(subject);
      } else {
        UserGroupInformation.loginUserFromSubject(subject);
        loginUgi = UserGroupInformation.getLoginUser();
      }
      LOG.info("Subject = {}, Principals = {}, Login UGI = {}", subject,
        subject == null ? "null" : subject.getPrincipals(), loginUgi);
      if (hdfsKerberos) {
        logMessage.append("Using Kerberos");
        if (loginUgi.getAuthenticationMethod() != UserGroupInformation.AuthenticationMethod.KERBEROS) {
          issues.add(
              context.createConfigIssue(
                  Groups.HADOOP_FS.name(),
                  HDFS_TARGET_CONFIG_BEAN_PREFIX + "hdfsKerberos",
                  Errors.HADOOPFS_00,
                  loginUgi.getAuthenticationMethod(),
                  UserGroupInformation.AuthenticationMethod.KERBEROS
              )
          );
        }
      } else {
        logMessage.append("Using Simple");
        hdfsConfiguration.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
          UserGroupInformation.AuthenticationMethod.SIMPLE.name());
      }
      if (validHapoopFsUri) {
        getUGI().doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            try (FileSystem fs = getFileSystemForInitDestroy()) { //to trigger the close
            }
            return null;
          }
        });
      }
    } catch (Exception ex) {
      // Having both hdfsUri and hdfsConfDir empty is highly suspicious sign of miss configuration. However HDFS will
      // automatically scan the classpath for the HDFS configs and there are ways how they can get to the classpath
      // (for example in cluster mode). Hence we can't report both their absence immediately as an error state. Instead
      // we do the check here, after we know that we can't connect to HDFS.
      if(StringUtils.isEmpty(hdfsUri) && StringUtils.isEmpty(hdfsConfDir)) {
        LOG.info("Validation Error: " + Errors.HADOOPFS_49.getMessage(), ex.toString(), ex);
        issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_49, String.valueOf(ex), ex));
      } else {
        // Any other general error when connecting to HDFS
        LOG.info("Validation Error: " + Errors.HADOOPFS_01.getMessage(), hdfsUri, ex.toString(), ex);
        issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_01, hdfsUri,
          String.valueOf(ex), ex));
      }

      // We weren't able connect to the cluster and hence setting the validity to false
      validHapoopFsUri = false;
    }
    LOG.info("Authentication Config: " + logMessage);
    return validHapoopFsUri;
  }

  private boolean validateHadoopDir(final Stage.Context context, final String configName, final String configGroup,
                            String dirPathTemplate, final List<Stage.ConfigIssue> issues) {
    final AtomicBoolean ok = new AtomicBoolean(true);
    if (!dirPathTemplate.startsWith("/")) {
      issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_40));
      ok.set(false);
    } else {
      dirPathTemplate = (dirPathTemplate.isEmpty()) ? "/" : dirPathTemplate;
      try {
        final Path dir = new Path(dirPathTemplate);
        final FileSystem fs = getFileSystemForInitDestroy();
        getUGI().doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            if (!fs.exists(dir)) {
              try {
                if (fs.mkdirs(dir)) {
                  ok.set(true);
                } else {
                  issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_41));
                  ok.set(false);
                }
              } catch (IOException ex) {
                issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_42,
                    ex.toString()));
                ok.set(false);
              }
            } else {
              try {
                Path dummy = new Path(dir, "_sdc-dummy-" + UUID.randomUUID().toString());
                fs.create(dummy).close();
                fs.delete(dummy, false);
                ok.set(true);
              } catch (IOException ex) {
                issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_43,
                    ex.toString()));
                ok.set(false);
              }
            }
            return null;
          }
        });
      } catch (Exception ex) {
        issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_44,
          ex.toString()));
        ok.set(false);
      }
    }
    return ok.get();
  }

  private FileSystem getFileSystemForInitDestroy() throws Exception {
    try {
      return getUGI().doAs(new PrivilegedExceptionAction<FileSystem>() {
        @Override
        public FileSystem run() throws Exception {
          return FileSystem.get(new URI(hdfsUri), hdfsConfiguration);
        }
      });
    } catch (IOException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception) {
        throw (Exception)cause;
      }
      throw ex;
    }
  }

}

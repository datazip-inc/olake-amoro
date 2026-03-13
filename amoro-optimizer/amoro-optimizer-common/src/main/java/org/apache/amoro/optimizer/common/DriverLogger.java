/*
 * Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.amoro.optimizer.common;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Logger that writes driver-level logs to a single file per process ID. */
public class DriverLogger implements AutoCloseable {
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final String LOG_BASE_DIR = "/mnt/amoro-logs/compaction";

  private final long processId;
  private final String tableName;
  private transient PrintWriter writer;
  private transient Path logFile;

  public DriverLogger(long processId, String tableName) {
    this.processId = processId;
    this.tableName = tableName;
  }

  private synchronized void ensureWriter() throws IOException {
    if (writer == null) {
      Path processDir = Paths.get(LOG_BASE_DIR, String.valueOf(processId));
      Files.createDirectories(processDir);

      logFile = processDir.resolve("driver.log");
      File file = logFile.toFile();
      writer = new PrintWriter(new BufferedWriter(new FileWriter(file, true)), true);
    }
  }

  public void info(String message, Object... args) {
    log("INFO", String.format(message, args), null);
  }

  public void warn(String message, Object... args) {
    log("WARN", String.format(message, args), null);
  }

  public void error(String message, Throwable t) {
    log("ERROR", message, t);
  }

  public void error(String message, Object... args) {
    log("ERROR", String.format(message, args), null);
  }

  private synchronized void log(String level, String message, Throwable throwable) {
    try {
      ensureWriter();
      String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
      String logLine =
          String.format(
              "%s %-5s [DRIVER|P:%d|Table:%s] - %s",
              timestamp, level, processId, tableName != null ? tableName : "unknown", message);
      writer.println(logLine);

      if (throwable != null) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        writer.println(sw.toString());
      }
      writer.flush();
    } catch (IOException e) {
      System.err.println("Failed to write to driver log: " + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  public synchronized void close() {
    if (writer != null) {
      writer.flush();
      writer.close();
      writer = null;
    }
  }

  public long getProcessId() {
    return processId;
  }

  public String getTableName() {
    return tableName;
  }
}

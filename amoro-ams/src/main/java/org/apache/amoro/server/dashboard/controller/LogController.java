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
 *
 * Modified by Datazip Inc. in 2026
 */

package org.apache.amoro.server.dashboard.controller;

import io.javalin.http.Context;
import io.javalin.http.HttpCode;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogController {
  private static final Logger LOG = LoggerFactory.getLogger(LogController.class);
  private static final String LOG_BASE_DIR = "/mnt/amoro-logs/compaction";

  public void getProcessLogs(Context ctx) {
    String processId = ctx.pathParam("processId");
    Path processDir = Paths.get(LOG_BASE_DIR, processId);

    Map<String, Object> response = new HashMap<>();
    response.put("processId", processId);

    if (!Files.exists(processDir) || !Files.isDirectory(processDir)) {
      response.put("exists", false);
      response.put("message", "Process log directory not found");
      response.put("driverLog", null);
      response.put("taskLogs", new ArrayList<>());
      ctx.json(OkResponse.of(response));
      return;
    }

    response.put("exists", true);

    // reads driver log
    Path driverLogPath = processDir.resolve("driver.log");
    Map<String, Object> driverLog = new HashMap<>();
    if (Files.exists(driverLogPath)) {
      try {
        driverLog.put("exists", true);
        driverLog.put("content", Files.readString(driverLogPath));
      } catch (IOException e) {
        LOG.error("Failed to read driver log: {}", driverLogPath, e);
        driverLog.put("exists", true);
        driverLog.put("error", "Failed to read: " + e.getMessage());
      }
    } else {
      driverLog.put("exists", false);
    }
    response.put("driverLog", driverLog);

    // reads all sub-task logs
    List<Map<String, Object>> taskLogs = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
      for (Path taskLogPath : stream) {
        String fileName = taskLogPath.getFileName().toString();
        if (fileName.equals("driver.log")) {
          continue; // skip driver log
        }

        String taskId = fileName.replace(".log", "");
        Map<String, Object> taskLog = new HashMap<>();
        taskLog.put("taskId", taskId);

        try {
          taskLog.put("exists", true);
          taskLog.put("content", Files.readString(taskLogPath));
          taskLogs.add(taskLog);
        } catch (IOException e) {
          LOG.error("Failed to read task log: {}", taskLogPath, e);
          taskLog.put("exists", true);
          taskLog.put("error", "Failed to read: " + e.getMessage());
          taskLogs.add(taskLog);
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to list task logs in directory: {}", processDir, e);
    }

    response.put("taskLogs", taskLogs);
    ctx.json(OkResponse.of(response));
  }

  public void downloadLogFile(Context ctx) {
    String processId = ctx.pathParam("processId");
    String fileId = ctx.pathParam("fileId");
    // fileId is either "driver" or a taskId like "1", "2", etc.
    String fileName = fileId + ".log";
    Path logFile = Paths.get(LOG_BASE_DIR, processId, fileName);

    if (!Files.exists(logFile) || !Files.isRegularFile(logFile)) {
      ctx.status(HttpCode.NOT_FOUND).result("Log file not found: " + processId + "/" + fileName);
      return;
    }

    ctx.header("Content-Type", "text/plain; charset=UTF-8");
    ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

    try {
      ctx.result(Files.newInputStream(logFile));
    } catch (IOException e) {
      LOG.error("Failed to read log file: {}", logFile, e);
      ctx.status(HttpCode.INTERNAL_SERVER_ERROR).result("Failed to read log file");
    }
  }

  public void downloadProcessLogs(Context ctx) {
    String processId = ctx.pathParam("processId");
    Path processDir = Paths.get(LOG_BASE_DIR, processId);

    if (!Files.exists(processDir) || !Files.isDirectory(processDir)) {
      ctx.status(HttpCode.NOT_FOUND).result("Process log directory not found: " + processId);
      return;
    }

    String tarFileName = "compaction-logs-" + processId + ".tar";
    ctx.header("Content-Type", "application/x-tar");
    ctx.header("Content-Disposition", "attachment; filename=\"" + tarFileName + "\"");

    try {
      OutputStream out = ctx.res.getOutputStream();
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new BufferedOutputStream(out))) {
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
          for (Path logFile : stream) {
            long size = Files.size(logFile);
            TarArchiveEntry entry = new TarArchiveEntry(logFile.getFileName().toString());
            entry.setSize(size);
            tar.putArchiveEntry(entry);
            Files.copy(logFile, tar);
            tar.closeArchiveEntry();
          }
        }

        tar.finish();
      }
    } catch (IOException e) {
      LOG.error("Failed to create tar archive for process {}", processId, e);
    }
  }
}

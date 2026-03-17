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
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    // Read driver log
    Path driverLogPath = processDir.resolve("driver.log");
    Map<String, Object> driverLog = new HashMap<>();
    if (Files.exists(driverLogPath)) {
      try {
        driverLog.put("exists", true);
        driverLog.put("content", Files.readString(driverLogPath));
        driverLog.put("size", Files.size(driverLogPath));
      } catch (IOException e) {
        LOG.error("Failed to read driver log: {}", driverLogPath, e);
        driverLog.put("exists", true);
        driverLog.put("error", "Failed to read: " + e.getMessage());
      }
    } else {
      driverLog.put("exists", false);
    }
    response.put("driverLog", driverLog);

    // Read all task logs
    List<Map<String, Object>> taskLogs = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
      for (Path taskLogPath : stream) {
        String fileName = taskLogPath.getFileName().toString();
        if (fileName.equals("driver.log")) {
          continue; // Skip driver log
        }

        String taskId = fileName.replace(".log", "");
        Map<String, Object> taskLog = new HashMap<>();
        taskLog.put("taskId", taskId);

        try {
          taskLog.put("exists", true);
          taskLog.put("content", Files.readString(taskLogPath));
          taskLog.put("size", Files.size(taskLogPath));
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
}

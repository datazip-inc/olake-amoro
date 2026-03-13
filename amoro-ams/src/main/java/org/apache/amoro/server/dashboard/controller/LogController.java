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

package org.apache.amoro.server.dashboard.controller;

import io.javalin.http.Context;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class LogController {
  private static final Logger LOG = LoggerFactory.getLogger(LogController.class);
  private static final String LOG_BASE_DIR = "/usr/local/amoro/logs/compaction";

  public void getDriverLog(Context ctx) {
    String processId = ctx.pathParam("processId");

    Path logFilePath = Paths.get(LOG_BASE_DIR, processId, "driver.log");

    Map<String, Object> response = new HashMap<>();
    response.put("processId", processId);
    response.put("logType", "driver");
    response.put("logFilePath", logFilePath.toString());

    if (!Files.exists(logFilePath)) {
      response.put("exists", false);
      response.put("content", "");
      response.put("message", "Driver log file not found");
      ctx.json(OkResponse.of(response));
      return;
    }

    try {
      String content = Files.readString(logFilePath);
      response.put("exists", true);
      response.put("content", content);
      response.put("size", Files.size(logFilePath));
      ctx.json(OkResponse.of(response));
    } catch (IOException e) {
      LOG.error("Failed to read driver log file: {}", logFilePath, e);
      response.put("exists", true);
      response.put("content", "");
      response.put("error", "Failed to read driver log file: " + e.getMessage());
      ctx.json(OkResponse.of(response));
    }
  }

  public void getTaskLog(Context ctx) {
    String processId = ctx.pathParam("processId");
    String taskId = ctx.pathParam("taskId");

    Path logFilePath = Paths.get(LOG_BASE_DIR, processId, taskId + ".log");

    Map<String, Object> response = new HashMap<>();
    response.put("processId", processId);
    response.put("taskId", taskId);
    response.put("logFilePath", logFilePath.toString());

    if (!Files.exists(logFilePath)) {
      response.put("exists", false);
      response.put("content", "");
      response.put("message", "Log file not found");
      ctx.json(OkResponse.of(response));
      return;
    }

    try {
      String content = Files.readString(logFilePath);
      response.put("exists", true);
      response.put("content", content);
      response.put("size", Files.size(logFilePath));
      ctx.json(OkResponse.of(response));
    } catch (IOException e) {
      LOG.error("Failed to read log file: {}", logFilePath, e);
      response.put("exists", true);
      response.put("content", "");
      response.put("error", "Failed to read log file: " + e.getMessage());
      ctx.json(OkResponse.of(response));
    }
  }
}

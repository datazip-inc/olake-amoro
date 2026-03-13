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

package org.apache.amoro.optimizer.spark;

import org.apache.amoro.api.OptimizingTask;
import org.apache.amoro.api.OptimizingTaskResult;
import org.apache.amoro.optimizer.common.OptimizerConfig;
import org.apache.amoro.optimizer.common.OptimizerExecutor;
import org.apache.amoro.optimizer.common.TaskLogger;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code SparkOptimizingTaskExecuteFunction} defines the whole processing logic that how to
 * execute {@code OptimizingTask}
 */
public class SparkOptimizingTaskFunction implements Function<OptimizingTask, OptimizingTaskResult> {
  private static final Logger LOG = LoggerFactory.getLogger(SparkOptimizingTaskFunction.class);
  private final OptimizerConfig config;
  private final int threadId;
  private final TaskLogger taskLogger;

  public SparkOptimizingTaskFunction(OptimizerConfig config, int threadId, TaskLogger taskLogger) {
    this.config = config;
    this.threadId = threadId;
    this.taskLogger = taskLogger;
  }

  @Override
  public OptimizingTaskResult call(OptimizingTask task) {
    if (taskLogger != null) {
      taskLogger.info("Executing task on Spark executor");
    }
    try {
      OptimizingTaskResult result = OptimizerExecutor.executeTask(config, threadId, task, LOG);
      if (taskLogger != null) {
        taskLogger.info("Task execution completed on executor");
      }
      return result;
    } catch (Exception e) {
      if (taskLogger != null) {
        taskLogger.error("Task execution failed on executor:", e);
      }
      throw e;
    }
  }
}

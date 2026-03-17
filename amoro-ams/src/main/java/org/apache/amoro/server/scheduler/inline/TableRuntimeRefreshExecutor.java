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

package org.apache.amoro.server.scheduler.inline;

import org.apache.amoro.AmoroTable;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.config.OptimizingConfig;
import org.apache.amoro.config.TableConfiguration;
import org.apache.amoro.optimizing.plan.AbstractOptimizingEvaluator;
import org.apache.amoro.process.ProcessStatus;
import org.apache.amoro.server.optimizing.OptimizingProcess;
import org.apache.amoro.server.optimizing.OptimizingStatus;
import org.apache.amoro.server.scheduler.PeriodicTableScheduler;
import org.apache.amoro.server.table.DefaultTableRuntime;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.IcebergTableUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.table.MixedTable;
import org.apache.amoro.utils.CronUtils;

/** Executor that refreshes table runtimes and evaluates optimizing status periodically. */
public class TableRuntimeRefreshExecutor extends PeriodicTableScheduler {

  // 1 minutes
  private final long interval;
  private final int maxPendingPartitions;

  public TableRuntimeRefreshExecutor(
      TableService tableService, int poolSize, long interval, int maxPendingPartitions) {
    super(tableService, poolSize);
    this.interval = interval;
    this.maxPendingPartitions = maxPendingPartitions;
  }

  @Override
  protected boolean enabled(TableRuntime tableRuntime) {
    return tableRuntime instanceof DefaultTableRuntime;
  }

  @Override
  protected long getNextExecutingTime(TableRuntime tableRuntime) {
    // The existing periodic loop (REFRESH_TABLES_INTERVAL, default 1 min) already provides
    // the tick. CronUtils.hasFired() inside CommonPartitionEvaluator checks on each tick
    // whether a cron schedule has been reached — no extra timing logic is needed here.
    return interval;
  }

  private void tryEvaluatingPendingInput(DefaultTableRuntime tableRuntime, MixedTable table) {
    OptimizingConfig optimizingConfig = tableRuntime.getOptimizingConfig();

    if (!optimizingConfig.isEnabled()) {
      logger.info(
          "Table [{}] cron fired but compaction skipped: self-optimizing is disabled",
          tableRuntime.getTableIdentifier());
      return;
    }

    OptimizingStatus currentStatus = tableRuntime.getOptimizingStatus();
    if (!currentStatus.equals(OptimizingStatus.IDLE)) {
      logger.info(
          "Table [{}] cron fired but compaction skipped: already in progress (status={})",
          tableRuntime.getTableIdentifier(),
          currentStatus);
      return;
    }

    // Verify there is new user data since the last compaction commit.
    // If the current snapshot matches the post-compaction snapshot we recorded at commit time,
    // the latest write was done by the compactor itself — nothing new to compact.
    long currentSnapshotId = tableRuntime.getCurrentSnapshotId();
    long lastOptimizedSnapshotId = tableRuntime.getLastOptimizedSnapshotId();
    boolean hasNewData = currentSnapshotId != lastOptimizedSnapshotId;
    if (table.isKeyedTable()) {
      hasNewData =
          hasNewData
              || tableRuntime.getCurrentChangeSnapshotId()
                  != tableRuntime.getLastOptimizedChangeSnapshotId();
    }
    if (!hasNewData) {
      logger.info(
          "Table [{}] cron fired but compaction skipped:"
              + " current snapshot ({}) is already a compaction commit — no new data since last compaction",
          tableRuntime.getTableIdentifier(),
          currentSnapshotId);
      tableRuntime.optimizingNotNecessary();
      return;
    }

    AbstractOptimizingEvaluator evaluator =
        IcebergTableUtil.createOptimizingEvaluator(tableRuntime, table, maxPendingPartitions);
    if (evaluator.isNecessary()) {
      AbstractOptimizingEvaluator.PendingInput pendingInput =
          evaluator.getOptimizingPendingInput();
      logger.info(
          "Table [{}] compaction scheduled: partitions={}, dataFiles={}, dataBytes={}",
          tableRuntime.getTableIdentifier(),
          pendingInput.getPartitions() != null ? pendingInput.getPartitions().size() : 0,
          pendingInput.getDataFileCount(),
          pendingInput.getDataFileSize());
      tableRuntime.setPendingInput(pendingInput);
    } else {
      long now = System.currentTimeMillis();
      logger.info(
          "Table [{}] compaction skipped: no partition needs work"
              + " | full-cron={} | major-cron={} | minor-cron={}",
          tableRuntime.getTableIdentifier(),
          cronFiredStatus(
              optimizingConfig.getFullTriggerCron(),
              tableRuntime.getLastFullOptimizingTime(),
              now),
          cronFiredStatus(
              optimizingConfig.getMajorTriggerCron(),
              tableRuntime.getLastMajorOptimizingTime(),
              now),
          cronFiredStatus(
              optimizingConfig.getMinorTriggerCron(),
              tableRuntime.getLastMinorOptimizingTime(),
              now));
      tableRuntime.optimizingNotNecessary();
    }
    tableRuntime.setTableSummary(evaluator.getPendingInput());
  }

  @Override
  public void handleConfigChanged(TableRuntime tableRuntime, TableConfiguration originalConfig) {
    Preconditions.checkArgument(tableRuntime instanceof DefaultTableRuntime);
    DefaultTableRuntime defaultTableRuntime = (DefaultTableRuntime) tableRuntime;
    // After disabling self-optimizing, close the currently running optimizing process.
    if (originalConfig.getOptimizingConfig().isEnabled()
        && !tableRuntime.getTableConfiguration().getOptimizingConfig().isEnabled()) {
      OptimizingProcess optimizingProcess = defaultTableRuntime.getOptimizingProcess();
      if (optimizingProcess != null && optimizingProcess.getStatus() == ProcessStatus.RUNNING) {
        optimizingProcess.close(false);
      }
    }
  }

  @Override
  protected long getExecutorDelay() {
    return 0;
  }

  /**
   * Returns a human-readable string describing whether a cron schedule has fired.
   * Examples: "not-configured", "fired('0 * * * *')", "pending('0 0 * * *')"
   */
  private static String cronFiredStatus(String cronExpr, long lastRunTimeMs, long nowMs) {
    if (cronExpr == null || cronExpr.isBlank()) {
      return "not-configured";
    }
    boolean fired = CronUtils.hasFired(cronExpr, lastRunTimeMs, nowMs);
    return (fired ? "fired" : "pending") + "('" + cronExpr + "')";
  }

  /** Returns which compaction type crons fired, e.g. "[FULL, MINOR]". */
  private static String firedTypes(boolean fullFired, boolean majorFired, boolean minorFired) {
    StringBuilder sb = new StringBuilder("[");
    if (fullFired) sb.append("FULL");
    if (majorFired) sb.append(sb.length() > 1 ? ", MAJOR" : "MAJOR");
    if (minorFired) sb.append(sb.length() > 1 ? ", MINOR" : "MINOR");
    sb.append("]");
    return sb.toString();
  }

  @Override
  public void execute(TableRuntime tableRuntime) {
    try {
      Preconditions.checkArgument(tableRuntime instanceof DefaultTableRuntime);
      DefaultTableRuntime defaultTableRuntime = (DefaultTableRuntime) tableRuntime;

      AmoroTable<?> table = loadTable(tableRuntime);
      defaultTableRuntime.refresh(table);
      MixedTable mixedTable = (MixedTable) table.originalTable();

      OptimizingConfig cfg = defaultTableRuntime.getOptimizingConfig();
      long now = System.currentTimeMillis();

      boolean fullFired =
          CronUtils.hasFired(
              cfg.getFullTriggerCron(), defaultTableRuntime.getLastFullOptimizingTime(), now);
      boolean majorFired =
          CronUtils.hasFired(
              cfg.getMajorTriggerCron(), defaultTableRuntime.getLastMajorOptimizingTime(), now);
      boolean minorFired =
          CronUtils.hasFired(
              cfg.getMinorTriggerCron(), defaultTableRuntime.getLastMinorOptimizingTime(), now);
      boolean anyCronFired = fullFired || majorFired || minorFired;

      if (anyCronFired) {
        logger.info(
            "Table [{}] cron(s) fired: {} | full-cron={} | major-cron={} | minor-cron={}",
            defaultTableRuntime.getTableIdentifier(),
            firedTypes(fullFired, majorFired, minorFired),
            cronFiredStatus(
                cfg.getFullTriggerCron(), defaultTableRuntime.getLastFullOptimizingTime(), now),
            cronFiredStatus(
                cfg.getMajorTriggerCron(), defaultTableRuntime.getLastMajorOptimizingTime(), now),
            cronFiredStatus(
                cfg.getMinorTriggerCron(), defaultTableRuntime.getLastMinorOptimizingTime(), now));
        tryEvaluatingPendingInput(defaultTableRuntime, mixedTable);
      } else {
        logger.debug(
            "Table [{}] tick: no cron fired — skipping | full-cron={} | major-cron={} | minor-cron={}",
            defaultTableRuntime.getTableIdentifier(),
            cronFiredStatus(
                cfg.getFullTriggerCron(), defaultTableRuntime.getLastFullOptimizingTime(), now),
            cronFiredStatus(
                cfg.getMajorTriggerCron(), defaultTableRuntime.getLastMajorOptimizingTime(), now),
            cronFiredStatus(
                cfg.getMinorTriggerCron(),
                defaultTableRuntime.getLastMinorOptimizingTime(),
                now));
      }
    } catch (Throwable throwable) {
      logger.error("Refreshing table {} failed.", tableRuntime.getTableIdentifier(), throwable);
    }
  }
}

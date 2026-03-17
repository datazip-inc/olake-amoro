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

package org.apache.amoro.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TreeSet;

/**
 * Lightweight cron expression evaluator backed by pure Java {@code java.time}.
 *
 * <p>Supports the standard 5-field UNIX cron syntax:
 *
 * <pre>  minute  hour  day-of-month  month  day-of-week</pre>
 *
 * <p>Supported field tokens:
 *
 * <ul>
 *   <li>{@code *} – every unit
 *   <li>{@code n} – specific value
 *   <li>{@code n-m} – inclusive range
 *   <li>{@code * /n} – every n units (step)
 *   <li>{@code n-m/n} – range with step
 *   <li>{@code n,m,k} – comma-separated list (can mix the above)
 * </ul>
 *
 * <p>All evaluation is done in UTC.
 */
public final class CronUtils {

  private CronUtils() {}

  /**
   * Returns {@code true} if the cron expression fired at least once in the half-open interval
   * {@code (lastRunTimeMs, currentTimeMs]}.
   *
   * <p>A {@code null} or blank expression always returns {@code false} (i.e. disabled).
   * The caller is expected to invoke this on every periodic tick (e.g. the existing
   * {@code TableRuntimeRefreshExecutor} loop that already runs every minute).
   */
  public static boolean hasFired(String cronExpr, long lastRunTimeMs, long currentTimeMs) {
    if (cronExpr == null || cronExpr.isBlank()) {
      return false;
    }
    LocalDateTime lastRun = toUtc(lastRunTimeMs);
    LocalDateTime current = toUtc(currentTimeMs);
    LocalDateTime nextAfterLastRun = ParsedCron.parse(cronExpr).nextFireTime(lastRun);
    return nextAfterLastRun != null && !nextAfterLastRun.isAfter(current);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static LocalDateTime toUtc(long epochMs) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
  }

  // ── inner cron model ────────────────────────────────────────────────────────

  static final class ParsedCron {

    private final int[] minutes;   // 0-59
    private final int[] hours;     // 0-23
    private final int[] doms;      // 1-31  (day-of-month)
    private final int[] months;    // 1-12
    private final int[] dows;      // 0-6   (0=Sunday … 6=Saturday)

    private ParsedCron(int[] minutes, int[] hours, int[] doms, int[] months, int[] dows) {
      this.minutes = minutes;
      this.hours = hours;
      this.doms = doms;
      this.months = months;
      this.dows = dows;
    }

    static ParsedCron parse(String expr) {
      String[] parts = expr.trim().split("\\s+");
      if (parts.length != 5) {
        throw new IllegalArgumentException(
            "Invalid cron expression – expected 5 fields (minute hour dom month dow): " + expr);
      }
      return new ParsedCron(
          parseField(parts[0], 0, 59),
          parseField(parts[1], 0, 23),
          parseField(parts[2], 1, 31),
          parseField(parts[3], 1, 12),
          parseDow(parts[4]));
    }

    /**
     * Returns the next scheduled {@link LocalDateTime} (UTC) strictly after {@code reference},
     * or {@code null} if none exists within four years.
     */
    LocalDateTime nextFireTime(LocalDateTime reference) {
      // Start one minute ahead, truncated to the minute boundary.
      LocalDateTime dt = reference.withSecond(0).withNano(0).plusMinutes(1);
      LocalDateTime limit = reference.plusYears(4);

      while (!dt.isAfter(limit)) {

        // ── month ─────────────────────────────────────────────────────────────
        if (!has(months, dt.getMonthValue())) {
          // Jump to the 1st of the next month and restart.
          dt = dt.withDayOfMonth(1).withHour(0).withMinute(0).plusMonths(1);
          continue;
        }

        // ── day-of-month and day-of-week (AND semantics) ──────────────────────
        int cronDow = dt.getDayOfWeek().getValue() % 7; // Java Mon=1…Sun=7 → cron Sun=0…Sat=6
        if (!has(doms, dt.getDayOfMonth()) || !has(dows, cronDow)) {
          dt = dt.plusDays(1).withHour(0).withMinute(0);
          continue;
        }

        // ── hour ──────────────────────────────────────────────────────────────
        if (!has(hours, dt.getHour())) {
          dt = dt.plusHours(1).withMinute(0);
          continue;
        }

        // ── minute ────────────────────────────────────────────────────────────
        if (!has(minutes, dt.getMinute())) {
          dt = dt.plusMinutes(1);
          continue;
        }

        return dt;
      }
      return null;
    }

    // ── field helpers ──────────────────────────────────────────────────────

    private static int[] parseDow(String field) {
      // UNIX cron allows 0 and 7 both to mean Sunday.
      int[] raw = parseField(field, 0, 7);
      TreeSet<Integer> set = new TreeSet<>();
      for (int v : raw) {
        set.add(v == 7 ? 0 : v);
      }
      return set.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] parseField(String field, int min, int max) {
      TreeSet<Integer> values = new TreeSet<>();
      for (String token : field.split(",")) {
        token = token.trim();
        if (token.equals("*")) {
          for (int i = min; i <= max; i++) values.add(i);
        } else if (token.startsWith("*/")) {
          int step = Integer.parseInt(token.substring(2));
          for (int i = min; i <= max; i += step) values.add(i);
        } else if (token.contains("/")) {
          String[] rangeParts = token.split("/");
          int step = Integer.parseInt(rangeParts[1]);
          String rangePart = rangeParts[0];
          int start, end;
          if (rangePart.contains("-")) {
            String[] bounds = rangePart.split("-");
            start = Integer.parseInt(bounds[0]);
            end = Integer.parseInt(bounds[1]);
          } else {
            start = Integer.parseInt(rangePart);
            end = max;
          }
          for (int i = start; i <= end; i += step) values.add(i);
        } else if (token.contains("-")) {
          String[] bounds = token.split("-");
          int start = Integer.parseInt(bounds[0]);
          int end = Integer.parseInt(bounds[1]);
          for (int i = start; i <= end; i++) values.add(i);
        } else {
          values.add(Integer.parseInt(token));
        }
      }
      return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean has(int[] arr, int value) {
      for (int v : arr) {
        if (v == value) return true;
      }
      return false;
    }
  }
}

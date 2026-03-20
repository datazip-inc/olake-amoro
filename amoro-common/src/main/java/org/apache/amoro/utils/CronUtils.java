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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/**
 * Lightweight utility for evaluating whether a cron expression has fired in a given time window,
 * and for converting a cron expression to the equivalent repeat interval in milliseconds.
 *
 * <p>Supports standard 5-field Unix cron format: {@code minute hour day-of-month month
 * day-of-week}
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code "0 0 * * *"} - daily at midnight
 *   <li>{@code "0 12 * * *"} - daily at noon
 *   <li>{@code "0 0 * * 0"} - every Sunday at midnight
 *   <li>{@code "0 0 1 * *"} - first day of every month at midnight
 *   <li>{@code "0 0/6 * * *"} - every 6 hours (step from midnight)
 * </ul>
 *
 * <p>Day-of-week uses Unix convention: 0 = Sunday, 1 = Monday, 6 = Saturday.
 */
public final class CronUtils {

  /** Maximum look-back window when searching for a cron fire (32 days). */
  private static final long MAX_SEARCH_WINDOW_MS = 32L * 24 * 60 * 60 * 1000;

  private CronUtils() {}

  // ── public API ──────────────────────────────────────────────────────────────

  /**
   * Converts a 5-field UNIX cron expression to the interval in milliseconds between two
   * consecutive firings (the period of the schedule).
   *
   * <p>The interval is computed by finding the first two upcoming fire times from the current
   * minute and measuring the gap. For a uniform periodic expression such as {@code "* /3 * * * *"}
   * (every 3 minutes) this produces exactly 180 000 ms. For a calendar-anchored expression such as
   * {@code "0 0 * * *"} (midnight daily) this produces 86 400 000 ms.
   *
   * <p>Returns {@code -1L} for a {@code null}, blank, or un-parseable expression (disabled).
   */
  public static long cronToIntervalMs(String cronExpr) {
    if (cronExpr == null || cronExpr.trim().isEmpty()) {
      return -1L;
    }
    try {
      CronFields fields = CronFields.parse(cronExpr);
      ZoneId zone = ZoneId.systemDefault();
      LocalDateTime ref = LocalDateTime.now(zone).withSecond(0).withNano(0);

      LocalDateTime t1 = nextFireTime(fields, ref, ref.plusYears(1));
      if (t1 == null) {
        return -1L;
      }
      LocalDateTime t2 = nextFireTime(fields, t1, t1.plusYears(1));
      if (t2 == null) {
        return -1L;
      }
      // Cron fires are always at minute boundaries, so minutes * 60 000 is exact.
      return ChronoUnit.MINUTES.between(t1, t2) * 60_000L;
    } catch (Exception e) {
      return -1L;
    }
  }

  /**
   * Returns true if the cron expression fired at least once in the half-open interval
   * {@code (lastFiredMs, currentTimeMs]}.
   *
   * <p>If {@code cronExpr} is {@code null} or blank, always returns {@code false}.
   *
   * <p>To avoid scanning an arbitrarily large window (e.g. when a table has never been
   * compacted), the search is capped at {@value #MAX_SEARCH_WINDOW_MS} ms before
   * {@code currentTimeMs}.
   */
  public static boolean hasFiredSince(String cronExpr, long lastFiredMs, long currentTimeMs) {
    if (cronExpr == null || cronExpr.trim().isEmpty()) {
      return false;
    }
    if (lastFiredMs >= currentTimeMs) {
      return false;
    }

    CronFields fields = CronFields.parse(cronExpr);
    ZoneId zone = ZoneId.systemDefault();

    // Cap the search start to avoid iterating over years of history.
    long effectiveStartMs = Math.max(lastFiredMs + 1, currentTimeMs - MAX_SEARCH_WINDOW_MS);
    LocalDateTime candidate =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(effectiveStartMs), zone)
            .truncatedTo(ChronoUnit.MINUTES);
    LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(currentTimeMs), zone);

    // After truncating to a minute boundary the candidate may fall at or before lastFiredMs
    // (e.g. effectiveStartMs = 20:06:45.339 → candidate = 20:06:00, but lastFiredMs = 20:06:45.338).
    // We must only count fires strictly after lastFiredMs, so advance past that point.
    LocalDateTime lastFiredDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastFiredMs), zone);
    if (!candidate.isAfter(lastFiredDt)) {
      candidate = lastFiredDt.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    }

    while (!candidate.isAfter(end)) {
      if (fields.matches(candidate)) {
        return true;
      }
      candidate = candidate.plusMinutes(1);
    }
    return false;
  }

  // ── internal helpers ────────────────────────────────────────────────────────

  /** Returns the first fire time strictly after {@code after}, or {@code null} if none found. */
  private static LocalDateTime nextFireTime(
      CronFields fields, LocalDateTime after, LocalDateTime limit) {
    LocalDateTime candidate = after.plusMinutes(1).withSecond(0).withNano(0);
    while (!candidate.isAfter(limit)) {
      if (fields.matches(candidate)) {
        return candidate;
      }
      candidate = candidate.plusMinutes(1);
    }
    return null;
  }

  // ── internal cron field parser ──────────────────────────────────────────────

  static final class CronFields {

    /** Supported field ranges: minute(0-59), hour(0-23), dom(1-31), month(1-12), dow(0-6). */
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;

    private CronFields(
        BitSet minutes,
        BitSet hours,
        BitSet daysOfMonth,
        BitSet months,
        BitSet daysOfWeek) {
      this.minutes = minutes;
      this.hours = hours;
      this.daysOfMonth = daysOfMonth;
      this.months = months;
      this.daysOfWeek = daysOfWeek;
    }

    boolean matches(LocalDateTime dt) {
      // Java ISO: Monday=1 … Sunday=7; Unix cron: Sunday=0 … Saturday=6
      int dow = dt.getDayOfWeek().getValue() % 7;
      return minutes.get(dt.getMinute())
          && hours.get(dt.getHour())
          && daysOfMonth.get(dt.getDayOfMonth())
          && months.get(dt.getMonthValue())
          && daysOfWeek.get(dow);
    }

    static CronFields parse(String cronExpr) {
      String[] parts = cronExpr.trim().split("\\s+");
      if (parts.length != 5) {
        throw new IllegalArgumentException(
            "Invalid cron expression (expected 5 fields: minute hour dom month dow): " + cronExpr);
      }
      return new CronFields(
          parseBitSet(parts[0], 0, 59),
          parseBitSet(parts[1], 0, 23),
          parseBitSet(parts[2], 1, 31),
          parseBitSet(parts[3], 1, 12),
          parseBitSet(parts[4], 0, 6));
    }

    private static BitSet parseBitSet(String field, int min, int max) {
      BitSet bits = new BitSet(max + 1);
      if ("*".equals(field)) {
        bits.set(min, max + 1);
        return bits;
      }
      for (String token : field.split(",")) {
        token = token.trim();
        if (token.contains("/")) {
          // Step expression: "*/5" or "0/15"
          String[] stepParts = token.split("/", 2);
          int start = "*".equals(stepParts[0]) ? min : Integer.parseInt(stepParts[0].trim());
          int step = Integer.parseInt(stepParts[1].trim());
          for (int i = start; i <= max; i += step) {
            bits.set(i);
          }
        } else if (token.contains("-")) {
          // Range expression: "1-5"
          String[] rangeParts = token.split("-", 2);
          int from = Integer.parseInt(rangeParts[0].trim());
          int to = Integer.parseInt(rangeParts[1].trim());
          bits.set(from, to + 1);
        } else {
          bits.set(Integer.parseInt(token));
        }
      }
      return bits;
    }
  }
}

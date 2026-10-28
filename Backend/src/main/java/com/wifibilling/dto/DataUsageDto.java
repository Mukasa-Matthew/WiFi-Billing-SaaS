package com.wifibilling.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Tenant data usage for one dashboard period. Sessions count toward the slot
 * they started in, matching the other dashboard usage figures.
 *
 * @param previousBytes download + upload over the same elapsed stretch of the
 *                      previous period (yesterday until this time, and so on)
 */
public record DataUsageDto(
    String period,
    OffsetDateTime from,
    OffsetDateTime to,
    long downBytes,
    long upBytes,
    long users,
    long previousBytes,
    List<Bucket> buckets
) {
    /** One hour (today) or one day (week, month), keyed by its reporting-zone start. */
    public record Bucket(String start, String label, long downBytes, long upBytes, boolean future) {}
}

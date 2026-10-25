package com.wifibilling.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Calendar boundaries for business reporting ("today", "this month", daily
 * buckets). Timestamps stay stored and compared as UTC instants; only the
 * boundaries are computed in the reporting zone, so a sale at 00:30 in
 * Kampala counts towards that Kampala day, not the previous UTC day.
 *
 * The zone is platform-wide (reporting.timezone, default Africa/Kampala)
 * until tenants get their own timezone setting.
 */
@Component
public class ReportingClock {

    public static final String DEFAULT_ZONE = "Africa/Kampala";

    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public ReportingClock(@Value("${reporting.timezone:" + DEFAULT_ZONE + "}") String zoneId) {
        this(ZoneId.of(zoneId), Clock.systemUTC());
    }

    public ReportingClock(ZoneId zone, Clock clock) {
        this.zone = zone;
        this.clock = clock;
    }

    public ZoneId zone() { return zone; }

    /** Current instant, expressed in UTC. */
    public OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC); }

    /** Today's calendar date in the reporting zone. */
    public LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    /** The reporting-zone calendar date an instant falls on. */
    public LocalDate dateOf(OffsetDateTime instant) {
        return instant == null ? null : instant.atZoneSameInstant(zone).toLocalDate();
    }

    /** Start of a reporting-zone day, as a UTC instant. */
    public OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }
}

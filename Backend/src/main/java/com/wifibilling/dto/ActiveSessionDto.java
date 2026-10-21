package com.wifibilling.dto;

import lombok.Builder;

import java.time.OffsetDateTime;

@Builder
public record ActiveSessionDto(
    Long radAcctId,
    String username,
    String framedIpAddress,
    String callingStationId,
    String nasIpAddress,
    OffsetDateTime acctStartTime,
    OffsetDateTime acctStopTime,
    Long acctSessionTime,
    Long acctInputOctets,
    Long acctOutputOctets,
    Long totalOctets,
    String status,
    String acctTerminateCause
) {}

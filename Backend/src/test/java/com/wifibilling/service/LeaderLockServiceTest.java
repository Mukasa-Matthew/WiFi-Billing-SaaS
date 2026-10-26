package com.wifibilling.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LeaderLockServiceTest {

    @Test
    void executeIfLeader_runsTaskWhenLeaseAcquired() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Simulate successful insert/update claiming the lease
        when(jdbc.update(anyString(), eq("TEST_JOB"), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        LeaderLockService service = new LeaderLockService(jdbc);
        service.init();
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = service.executeIfLeader("TEST_JOB", 60, 10, () -> ran.set(true));

        assertTrue(executed);
        assertTrue(ran.get());
        // Verify release/completion update was called
        verify(jdbc).update(contains("UPDATE app_job_leases"), any(), any(), eq("TEST_JOB"), anyString());
    }

    @Test
    void executeIfLeader_skipsTaskWhenLeaseHeldByAnotherNode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Simulate rows = 0 (another node holds lease or cycle interval has not elapsed)
        when(jdbc.update(anyString(), eq("TEST_JOB"), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        LeaderLockService service = new LeaderLockService(jdbc);
        service.init();
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = service.executeIfLeader("TEST_JOB", 60, 10, () -> ran.set(true));

        assertFalse(executed);
        assertFalse(ran.get());
        verify(jdbc, never()).update(contains("UPDATE app_job_leases"), any(), any(), eq("TEST_JOB"), anyString());
    }

    @Test
    void executeIfLeader_staggeredStartPreventsDoubleExecutionInSameCycle() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Instance 1 claims lease
        when(jdbc.update(anyString(), eq("CYCLE_JOB"), eq("node-1"), any(), any(), any(), any(), any()))
                .thenReturn(1);
        // Instance 2 attempts shortly after but min interval has not elapsed -> returns 0
        when(jdbc.update(anyString(), eq("CYCLE_JOB"), eq("node-2"), any(), any(), any(), any(), any()))
                .thenReturn(0);

        LeaderLockService node1 = new LeaderLockService(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(node1, "configuredInstanceId", "node-1");
        node1.init();

        LeaderLockService node2 = new LeaderLockService(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(node2, "configuredInstanceId", "node-2");
        node2.init();

        AtomicInteger executionCounter = new AtomicInteger(0);

        boolean node1Result = node1.executeIfLeader("CYCLE_JOB", 60, 30, executionCounter::incrementAndGet);
        boolean node2Result = node2.executeIfLeader("CYCLE_JOB", 60, 30, executionCounter::incrementAndGet);

        assertTrue(node1Result, "Node 1 should win the lease");
        assertFalse(node2Result, "Node 2 must be blocked from running duplicate cycle");
        assertEquals(1, executionCounter.get(), "Job must execute exactly once across both nodes");
    }
}

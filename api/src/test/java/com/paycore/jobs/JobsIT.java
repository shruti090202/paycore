package com.paycore.jobs;

import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobsIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    void jobsRequireTheInternalTokenAndRecordRuns() {
        Api api = api();
        assertThat(api.post("/internal/jobs/bank-status-check", null, Api.none()).status()).isIn(401, 403);
        assertThat(api.post("/internal/jobs/bank-status-check", null, Map.of("X-Internal-Token", "wrong")).status()).isIn(401, 403);

        Api.Response run = Flows.runJob(api, "bank-status-check");
        assertThat(run.status()).isEqualTo(200);
        assertThat(run.text("/status")).isEqualTo("succeeded");
        assertThat(run.text("/trigger")).isEqualTo("internal_api");
        assertThat(run.at("/result/payments/scanned").isNumber()).isTrue();

        Api.Response status = api.get("/internal/jobs/status", Map.of("X-Internal-Token", "test-internal-token"));
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.at("/jobs")).extracting(n -> n.asString()).contains("bank-status-check");
        assertThat(status.raw()).contains("\"job\":\"bank-status-check\"");

        assertThat(Flows.runJob(api, "no-such-job").status()).isEqualTo(404);
    }

    @Test
    void aRunningJobBlocksASecondTriggerAndStaleLocksAreReclaimed() {
        Api api = api();
        // Simulate a run that is in progress (e.g. another instance).
        jdbc.sql("INSERT INTO job_runs (id, job_name, status, trigger, started_at) VALUES ('job_fake1', 'bank-status-check', 'running', 'scheduler', :t)")
                .param("t", Instant.now().atOffset(java.time.ZoneOffset.UTC)).update();
        Api.Response busy = Flows.runJob(api, "bank-status-check");
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.text("/error/code")).isEqualTo("job_already_running");

        // Age it past the stale threshold: the next trigger reclaims the lock.
        jdbc.sql("UPDATE job_runs SET started_at = :t WHERE id = 'job_fake1'")
                .param("t", Instant.now().minus(JobRunner.STALE_AFTER).minusSeconds(5).atOffset(java.time.ZoneOffset.UTC)).update();
        Api.Response ok = Flows.runJob(api, "bank-status-check");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM job_runs WHERE id = 'job_fake1'").query(String.class).single()).isEqualTo("failed");
    }
}

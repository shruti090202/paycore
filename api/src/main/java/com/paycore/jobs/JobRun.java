package com.paycore.jobs;

import com.paycore.common.jdbc.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("job_runs")
public record JobRun(
        @Id String id,
        String jobName,
        String status,
        String trigger,
        Instant startedAt,
        Instant finishedAt,
        Jsonb result,
        String error
) {
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
}

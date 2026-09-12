package com.paycore.api.internal;

import com.paycore.jobs.JobRun;
import com.paycore.jobs.JobRunner;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Job triggers for GitHub Actions cron (Render free instances sleep, so nothing in-process can be relied on). */
@RestController
@RequestMapping("/internal/jobs")
@Hidden
public class JobsController {

    private final JobRunner runner;

    public JobsController(JobRunner runner) {
        this.runner = runner;
    }

    public record RunDto(String id, String job, String status, String trigger, Instant startedAt, Instant finishedAt,
                         Map<String, Object> result, String error) {
        static RunDto from(JobRun r) {
            return new RunDto(r.id(), r.jobName(), r.status(), r.trigger(), r.startedAt(), r.finishedAt(),
                    r.result() == null ? null : r.result().asMap(), r.error());
        }
    }

    @PostMapping("/{name}")
    public ResponseEntity<RunDto> run(@PathVariable String name) {
        JobRun run = runner.run(name, "internal_api");
        return ResponseEntity.status(JobRun.SUCCEEDED.equals(run.status()) ? 200 : 500).body(RunDto.from(run));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<RunDto> latest = runner.latestPerJob().stream().map(RunDto::from).toList();
        return Map.of("jobs", runner.names(), "latest_runs", latest);
    }
}

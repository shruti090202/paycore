package com.paycore.api.dashboard;

import com.paycore.banksim.BankSimConfig;
import com.paycore.banksim.TestCards;
import com.paycore.common.config.OpenApiConfig;
import com.paycore.common.error.PayCoreException;
import com.paycore.jobs.JobRun;
import com.paycore.jobs.JobRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Demo knobs for the bank simulator. Global (not per merchant) because the "bank" is shared. */
@RestController
@RequestMapping("/dashboard/simulator")
@Tag(name = "Dashboard: bank simulator")
@SecurityRequirement(name = OpenApiConfig.DASHBOARD_JWT)
public class SimulatorController {

    /** Jobs any signed-in merchant may trigger for demos. Real scheduling is the cron workflow -> /internal/jobs. */
    private static final java.util.Set<String> DEMO_JOBS = java.util.Set.of("webhook-dispatch", "bank-status-check",
            "settlement-generate", "reconcile", "payout-run", "retention-cleanup");

    private final BankSimConfig config;
    private final JobRunner jobs;

    public SimulatorController(BankSimConfig config, JobRunner jobs) {
        this.config = config;
        this.jobs = jobs;
    }

    public record JobResultDto(String job, String status, java.util.Map<String, Object> result, String error) {
    }

    @org.springframework.web.bind.annotation.PostMapping("/jobs/{name}")
    @Operation(summary = "Run a background job now (demo convenience; production uses the scheduled workflow)")
    public JobResultDto runJob(@org.springframework.web.bind.annotation.PathVariable String name) {
        if (!DEMO_JOBS.contains(name)) {
            throw PayCoreException.notFound("job", name);
        }
        JobRun run = jobs.run(name, "dashboard");
        return new JobResultDto(name, run.status(), run.result() == null ? null : run.result().asMap(), run.error());
    }

    public record SettingsDto(double randomDeclineRate, double randomTimeoutRate, int minLatencyMs, int maxLatencyMs,
                              double settlementAnomalyRate) {
    }

    public record TestCardDto(String number, String brand, String behaviour, String declineCode, String description) {
    }

    @GetMapping
    public SettingsDto get() {
        var s = config.get();
        return new SettingsDto(s.randomDeclineRate(), s.randomTimeoutRate(), s.minLatencyMs(), s.maxLatencyMs(), s.settlementAnomalyRate());
    }

    @PutMapping
    @Operation(summary = "Adjust decline/timeout rates and latency for APPROVE-type test cards")
    public SettingsDto set(@RequestBody SettingsDto dto) {
        try {
            config.set(new BankSimConfig.Settings(dto.randomDeclineRate(), dto.randomTimeoutRate(), dto.minLatencyMs(), dto.maxLatencyMs(),
                    dto.settlementAnomalyRate()));
        } catch (IllegalArgumentException e) {
            throw PayCoreException.invalid("simulator_settings_invalid", e.getMessage(), null);
        }
        return get();
    }

    @DeleteMapping
    public SettingsDto reset() {
        config.reset();
        return get();
    }

    @GetMapping("/test-cards")
    public List<TestCardDto> testCards() {
        return TestCards.ALL.stream()
                .map(c -> new TestCardDto(c.number(), c.brand(), c.behaviour().name().toLowerCase(), c.declineCode(), c.description()))
                .toList();
    }
}

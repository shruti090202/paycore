package com.paycore.jobs;

import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import com.paycore.common.jdbc.Jsonb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs jobs by name with a database-level mutual exclusion that survives multiple instances and restarts:
 * the {@code job_runs} table has a partial unique index on {@code (job_name) WHERE status = 'running'}, so the
 * second concurrent trigger of the same job fails to insert its row and is told "already running".
 * <p>
 * Why not {@code pg_advisory_lock}: session-level advisory locks do not work behind Neon's transaction-mode
 * pooler, and a job spans many short transactions on purpose (each item commits independently).
 */
@Service
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final JobRunRepository runs;
    private final JdbcAggregateTemplate template;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JobRunner(List<Job> available, JobRunRepository runs, JdbcAggregateTemplate template,
                     PlatformTransactionManager txManager, Clock clock) {
        for (Job j : available) {
            jobs.put(j.name(), j);
        }
        this.runs = runs;
        this.template = template;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public List<String> names() {
        return List.copyOf(jobs.keySet());
    }

    public Optional<Job> find(String name) {
        return Optional.ofNullable(jobs.get(name));
    }

    public JobRun run(String name, String trigger) {
        Job job = find(name).orElseThrow(() -> PayCoreException.notFound("job", name));
        Instant now = clock.instant();

        JobRun started;
        try {
            started = tx.execute(s -> {
                runs.failStale(name, now.minus(STALE_AFTER), now);
                return template.insert(new JobRun(Ids.newId("job"), name, JobRun.RUNNING, trigger, now, null, null, null));
            });
        } catch (DuplicateKeyException e) {
            throw new PayCoreException(ErrorType.CONFLICT, "job_already_running", "Job '" + name + "' is already running");
        }

        try {
            Map<String, Object> result = job.run();
            JobRun done = new JobRun(started.id(), name, JobRun.SUCCEEDED, trigger, started.startedAt(), clock.instant(),
                    Jsonb.of(result), null);
            template.update(done);
            log.info("job {} finished: {}", name, result);
            return done;
        } catch (RuntimeException e) {
            log.error("job {} failed", name, e);
            JobRun failed = new JobRun(started.id(), name, JobRun.FAILED, trigger, started.startedAt(), clock.instant(),
                    null, e.getClass().getSimpleName() + ": " + e.getMessage());
            template.update(failed);
            return failed;
        }
    }

    public List<JobRun> latestPerJob() {
        return runs.findLatestPerJob();
    }
}

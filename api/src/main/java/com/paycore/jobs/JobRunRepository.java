package com.paycore.jobs;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface JobRunRepository extends CrudRepository<JobRun, String> {

    /** Latest run per job (for the status endpoint). */
    @Query("SELECT DISTINCT ON (job_name) * FROM job_runs ORDER BY job_name, started_at DESC")
    List<JobRun> findLatestPerJob();

    @Query("SELECT * FROM job_runs WHERE job_name = :name ORDER BY started_at DESC LIMIT :limit")
    List<JobRun> findRecent(@Param("name") String name, @Param("limit") int limit);

    /** A 'running' row older than this is a crashed run; mark it failed so the lock is released. */
    @Modifying
    @Query("UPDATE job_runs SET status = 'failed', finished_at = :now, error = 'stale: marked failed by a later run' "
            + "WHERE job_name = :name AND status = 'running' AND started_at < :before")
    int failStale(@Param("name") String name, @Param("before") Instant before, @Param("now") Instant now);
}

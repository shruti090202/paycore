package com.paycore.jobs;

import java.util.Map;

/** A unit of background work. Implementations must be idempotent: cron can fire twice, or fire after a crash. */
public interface Job {

    String name();

    /** Returns a small JSON-able summary (counts) stored on the run row and returned to the caller. */
    Map<String, Object> run();
}

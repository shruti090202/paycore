package com.paycore.jobs;

import com.paycore.reconciliation.Reconciler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ReconcileJob implements Job {

    public static final String NAME = "reconcile";

    private final Reconciler reconciler;

    public ReconcileJob(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> run() {
        List<Map<String, Object>> runs = reconciler.reconcilePending().stream()
                .map(s -> Map.<String, Object>of("run_id", s.runId(), "rows_total", s.rowsTotal(), "rows_matched", s.rowsMatched(),
                        "items_open", s.itemsOpen(), "settled_minor", s.settledMinor()))
                .toList();
        return Map.of("runs", runs);
    }
}

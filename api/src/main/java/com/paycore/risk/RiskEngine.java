package com.paycore.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Runs every enabled rule for a merchant, sums the points (capped at 100) and maps the total to a decision. */
@Service
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);
    static final int UNKNOWN_PENALTY = 10;
    private static final long CACHE_MS = 60_000;

    private record Cached(List<RuleConfig> rules, long expiresAtMs) {
    }

    private final Map<String, RiskRule> rulesByType;
    private final Function<String, List<RuleConfig>> configLoader;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RiskEngine(List<RiskRule> rules, RiskRuleRepository configs, Clock clock) {
        this(rules, configs::effectiveRules, clock);
    }

    RiskEngine(List<RiskRule> rules, Function<String, List<RuleConfig>> configLoader, Clock clock) {
        this.rulesByType = new java.util.HashMap<>();
        for (RiskRule r : rules) {
            rulesByType.put(r.type(), r);
        }
        this.configLoader = configLoader;
        this.clock = clock;
    }

    public RiskDecision evaluate(RiskContext ctx) {
        int total = 0;
        List<RiskDecision.Reason> reasons = new ArrayList<>();
        for (RuleConfig cfg : rulesFor(ctx.merchantId())) {
            if (!cfg.enabled()) {
                continue;
            }
            RiskRule rule = rulesByType.get(cfg.type());
            if (rule == null) {
                log.warn("no implementation for risk rule type {}", cfg.type());
                continue;
            }
            RiskRule.Result r;
            try {
                r = rule.evaluate(ctx, cfg);
            } catch (RuntimeException e) {
                log.error("risk rule {} failed", cfg.type(), e);
                r = RiskRule.Result.unknown("Rule failed to evaluate");
            }
            if (!r.fired()) {
                continue;
            }
            int points = r.unknown() ? UNKNOWN_PENALTY : r.score();
            total += points;
            reasons.add(new RiskDecision.Reason(cfg.type(), points, r.reason()));
        }
        int score = Math.min(100, total);
        return new RiskDecision(score, RiskDecision.outcomeFor(score), List.copyOf(reasons));
    }

    public List<RuleConfig> rulesFor(String merchantId) {
        long now = clock.millis();
        Cached c = cache.get(merchantId);
        if (c != null && c.expiresAtMs() > now) {
            return c.rules();
        }
        List<RuleConfig> rules = configLoader.apply(merchantId);
        cache.put(merchantId, new Cached(rules, now + CACHE_MS));
        return rules;
    }

    public void invalidate(String merchantId) {
        cache.remove(merchantId);
    }
}

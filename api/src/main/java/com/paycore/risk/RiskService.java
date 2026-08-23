package com.paycore.risk;

import com.paycore.common.error.PayCoreException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Facade used by checkout (evaluate + record) and the dashboard (rules, blocklist, decisions). */
@Service
public class RiskService {

    public static final List<String> RULE_TYPES = List.of("amount_threshold", "card_velocity",
            "customer_distinct_cards", "blocklist", "test_card_signal");

    private final RiskEngine engine;
    private final RiskRuleRepository rules;
    private final RiskDecisionRepository decisions;
    private final BlocklistRepository blocklist;
    private final Clock clock;

    public RiskService(RiskEngine engine, RiskRuleRepository rules, RiskDecisionRepository decisions,
                       BlocklistRepository blocklist, Clock clock) {
        this.engine = engine;
        this.rules = rules;
        this.decisions = decisions;
        this.blocklist = blocklist;
        this.clock = clock;
    }

    /** Pure evaluation (talks to Redis for velocity); no database writes. Call before opening a transaction. */
    public RiskDecision evaluate(RiskContext ctx) {
        return engine.evaluate(ctx);
    }

    /** Persist a decision inside the caller's transaction (alongside the payment state change). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String paymentId, String merchantId, RiskDecision decision) {
        decisions.insert(paymentId, merchantId, decision, clock.instant());
    }

    public Optional<RiskDecisionRepository.Row> decisionFor(String paymentId, String merchantId) {
        return decisions.forPayment(paymentId, merchantId);
    }

    public List<RiskDecisionRepository.Row> decisions(String merchantId, String decision, String cursor, int limit) {
        return decisions.list(merchantId, decision, cursor, limit);
    }

    public List<RuleConfig> rulesFor(String merchantId) {
        return engine.rulesFor(merchantId);
    }

    @Transactional
    public List<RuleConfig> overrideRule(String merchantId, String type, Map<String, Object> params, Integer weight, Boolean enabled) {
        if (!RULE_TYPES.contains(type)) {
            throw PayCoreException.invalid("rule_type_unknown", "Unknown rule type '" + type + "'; known: " + RULE_TYPES, "type");
        }
        RuleConfig current = engine.rulesFor(merchantId).stream().filter(r -> r.type().equals(type)).findFirst()
                .orElseThrow(() -> PayCoreException.notFound("risk_rule", type));
        int w = weight == null ? current.weight() : weight;
        if (w < 0 || w > 100) {
            throw PayCoreException.invalid("weight_invalid", "weight must be 0-100", "weight");
        }
        rules.upsertOverride(merchantId, type, current.name(), params == null ? current.params() : params, w,
                enabled == null ? current.enabled() : enabled, clock.instant());
        engine.invalidate(merchantId);
        return engine.rulesFor(merchantId);
    }

    @Transactional
    public List<RuleConfig> resetRule(String merchantId, String type) {
        rules.deleteOverride(merchantId, type);
        engine.invalidate(merchantId);
        return engine.rulesFor(merchantId);
    }

    public List<BlocklistRepository.Entry> blocklist(String merchantId) {
        return blocklist.list(merchantId);
    }

    @Transactional
    public BlocklistRepository.Entry block(String merchantId, String kind, String value, String reason) {
        String hash = switch (kind) {
            case "card_fingerprint" -> {
                if (value == null || !value.startsWith("fp_")) {
                    throw PayCoreException.invalid("value_invalid", "Card fingerprints look like fp_...", "value");
                }
                yield value;
            }
            case "email" -> {
                if (value == null || !value.contains("@")) {
                    throw PayCoreException.invalid("value_invalid", "Not an email address", "value");
                }
                yield BlocklistRepository.hashEmail(value);
            }
            default -> throw PayCoreException.invalid("kind_invalid", "kind must be card_fingerprint or email", "kind");
        };
        return blocklist.add(merchantId, kind, hash, reason, clock.instant());
    }

    @Transactional
    public void unblock(String merchantId, String id) {
        if (blocklist.remove(merchantId, id) == 0) {
            throw PayCoreException.notFound("blocklist_entry", id);
        }
    }
}

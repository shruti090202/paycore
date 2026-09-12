package com.paycore.banksim;

import com.paycore.common.id.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;

/** An in-process acquiring bank. */
@Component
public class SimulatedBank implements BankGateway {

    private final BankSimRepository repo;
    private final JdbcAggregateTemplate template;
    private final TransactionTemplate ownTx;
    private final BankSimConfig config;
    private final CardFingerprints fingerprints;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SimulatedBank(BankSimRepository repo, JdbcAggregateTemplate template,
                         org.springframework.transaction.PlatformTransactionManager txManager,
                         BankSimConfig config, CardFingerprints fingerprints, Clock clock) {
        this.repo = repo;
        this.template = template;
        this.ownTx = new TransactionTemplate(txManager);
        this.ownTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.config = config;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    @Override
    public BankResponse authorize(AuthorizeRequest req) throws BankTimeoutException {
        TestCards.TestCard card = TestCards.find(req.card().number())
                .orElseThrow(() -> new IllegalArgumentException("not a test card")); // callers validate first
        simulateLatency();

        // Idempotent at the bank: the same bankRef returns the stored answer instead of processing twice.
        Optional<BankSimTransaction> existing = repo.findByBankRef(req.bankRef());
        if (existing.isPresent()) {
            return toResponse(existing.get(), card);
        }

        String fp = fingerprints.fingerprint(card.number());
        switch (card.behaviour()) {
            case DECLINE -> {
                record(req.bankRef(), "authorize", null, req.amountMinor(), req.currency(), fp, card.last4(),
                        Outcome.DECLINED, card.declineCode(), null);
                return new BankResponse(Outcome.DECLINED, card.declineCode(), null, card.brand(), card.last4());
            }
            case TIMEOUT_THEN_APPROVED -> {
                record(req.bankRef(), "authorize", null, req.amountMinor(), req.currency(), fp, card.last4(),
                        Outcome.APPROVED, null, authCode());
                throw new BankTimeoutException("bank did not respond (approved at the bank)");
            }
            case TIMEOUT_THEN_DECLINED -> {
                record(req.bankRef(), "authorize", null, req.amountMinor(), req.currency(), fp, card.last4(),
                        Outcome.DECLINED, card.declineCode(), null);
                throw new BankTimeoutException("bank did not respond (declined at the bank)");
            }
            case TIMEOUT_NOT_RECEIVED -> throw new BankTimeoutException("bank did not respond (request lost)");
            default -> {
                // APPROVE-family cards: apply the demo randomness, then approve.
                BankSimConfig.Settings s = config.get();
                if (s.randomTimeoutRate() > 0 && random.nextDouble() < s.randomTimeoutRate()) {
                    record(req.bankRef(), "authorize", null, req.amountMinor(), req.currency(), fp, card.last4(),
                            Outcome.APPROVED, null, authCode());
                    throw new BankTimeoutException("bank did not respond (random timeout)");
                }
                if (s.randomDeclineRate() > 0 && random.nextDouble() < s.randomDeclineRate()) {
                    record(req.bankRef(), "authorize", null, req.amountMinor(), req.currency(), fp, card.last4(),
                            Outcome.DECLINED, "generic_decline", null);
                    return new BankResponse(Outcome.DECLINED, "generic_decline", null, card.brand(), card.last4());
                }
                String code = authCode();
                record(req.bankRef(), "authorize", null, req.amountMinor(), req.currency(), fp, card.last4(),
                        Outcome.APPROVED, null, code);
                return new BankResponse(Outcome.APPROVED, null, code, card.brand(), card.last4());
            }
        }
    }

    @Override
    public BankResponse refund(RefundRequest req) throws BankTimeoutException {
        simulateLatency();
        Optional<BankSimTransaction> existing = repo.findByBankRef(req.bankRef());
        if (existing.isPresent()) {
            BankSimTransaction t = existing.get();
            return new BankResponse(Outcome.valueOf(t.outcome().toUpperCase()), t.declineCode(), t.authCode(), null, t.cardLast4());
        }
        BankSimTransaction parent = repo.findByBankRef(req.parentBankRef()).orElse(null);
        if (parent == null || !"approved".equals(parent.outcome())) {
            record(req.bankRef(), "refund", req.parentBankRef(), req.amountMinor(), req.currency(), null, null,
                    Outcome.DECLINED, "original_not_found", null);
            return new BankResponse(Outcome.DECLINED, "original_not_found", null, null, null);
        }
        TestCards.Behaviour behaviour = TestCards.ALL.stream()
                .filter(c -> c.last4().equals(parent.cardLast4()) && fingerprints.fingerprint(c.number()).equals(parent.cardFingerprint()))
                .map(TestCards.TestCard::behaviour).findFirst().orElse(TestCards.Behaviour.APPROVE);

        switch (behaviour) {
            case APPROVE_REFUND_DECLINES -> {
                record(req.bankRef(), "refund", req.parentBankRef(), req.amountMinor(), req.currency(),
                        parent.cardFingerprint(), parent.cardLast4(), Outcome.DECLINED, "refund_declined", null);
                return new BankResponse(Outcome.DECLINED, "refund_declined", null, null, parent.cardLast4());
            }
            case APPROVE_REFUND_TIMEOUT -> {
                record(req.bankRef(), "refund", req.parentBankRef(), req.amountMinor(), req.currency(),
                        parent.cardFingerprint(), parent.cardLast4(), Outcome.APPROVED, null, authCode());
                throw new BankTimeoutException("bank did not respond to refund (approved at the bank)");
            }
            default -> {
                String code = authCode();
                record(req.bankRef(), "refund", req.parentBankRef(), req.amountMinor(), req.currency(),
                        parent.cardFingerprint(), parent.cardLast4(), Outcome.APPROVED, null, code);
                return new BankResponse(Outcome.APPROVED, null, code, null, parent.cardLast4());
            }
        }
    }

    @Override
    public Optional<BankTransactionStatus> lookup(String bankRef) {
        return repo.findByBankRef(bankRef)
                .map(t -> new BankTransactionStatus(t.bankRef(), Outcome.valueOf(t.outcome().toUpperCase()), t.declineCode()));
    }

    // internals
    private void record(String bankRef, String kind, String parentRef, long amount, String currency, String fp,
                        String last4, Outcome outcome, String declineCode, String authCode) {
        try {
            ownTx.executeWithoutResult(s -> template.insert(new BankSimTransaction(bankRef, kind, parentRef, amount,
                    currency, fp, last4, outcome.name().toLowerCase(), declineCode, authCode, clock.instant(), null)));
        } catch (DuplicateKeyException ignored) {
            // A concurrent identical request already recorded this reference: idempotent by design.
        }
    }

    private BankResponse toResponse(BankSimTransaction t, TestCards.TestCard card) {
        return new BankResponse(Outcome.valueOf(t.outcome().toUpperCase()), t.declineCode(), t.authCode(),
                card.brand(), card.last4());
    }

    private void simulateLatency() {
        BankSimConfig.Settings s = config.get();
        int span = s.maxLatencyMs() - s.minLatencyMs();
        long ms = s.minLatencyMs() + (span == 0 ? 0 : random.nextInt(span + 1));
        if (ms > 0) {
            try {
                Thread.sleep(ms); // virtual threads: sleeping is cheap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String authCode() {
        return Ids.newId("auth").substring(5, 11).toUpperCase();
    }
}

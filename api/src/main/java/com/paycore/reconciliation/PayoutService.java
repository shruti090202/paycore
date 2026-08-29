package com.paycore.reconciliation;

import com.paycore.common.id.Ids;
import com.paycore.common.money.Money;
import com.paycore.ledger.AccountCodes;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.JournalEntry;
import com.paycore.ledger.LedgerRepository;
import com.paycore.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pays merchants what we owe them — but only out of money the bank has actually settled to us.
 * {@code payout = min(merchant_payable balance, remaining settlement_cash)}. A gateway that paid merchants
 * from unsettled receivables would be lending them money; this one never does.
 * <pre>
 *   DR merchant_payable:<m> / CR settlement_cash
 * </pre>
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    public record Paid(String payoutId, String merchantId, long amountMinor, String currency) {
    }

    private final ReconciliationRepository repo;
    private final LedgerRepository ledgerRepo;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayoutService(ReconciliationRepository repo, LedgerRepository ledgerRepo, LedgerService ledger,
                         PlatformTransactionManager txManager, Clock clock) {
        this.repo = repo;
        this.ledgerRepo = ledgerRepo;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public List<Paid> runPayouts() {
        List<Paid> paid = new ArrayList<>();
        Map<String, Long> cashByCurrency = new HashMap<>();
        for (ReconciliationRepository.MerchantBalance b : repo.positiveMerchantBalances()) {
            long cash = cashByCurrency.computeIfAbsent(b.currency(), c -> ledgerRepo.balanceByCode(AccountCodes.settlementCash(c)));
            long amount = Math.min(b.balanceMinor(), cash);
            if (amount <= 0) {
                log.info("payout skipped for {}: owed {} {} but no settled cash available", b.merchantId(), b.balanceMinor(), b.currency());
                continue;
            }
            Paid p = tx.execute(s -> pay(b.merchantId(), Money.of(amount, b.currency())));
            cashByCurrency.put(b.currency(), cash - amount);
            paid.add(p);
        }
        return paid;
    }

    private Paid pay(String merchantId, Money amount) {
        String payoutId = Ids.newId("po");
        JournalEntry je = ledger.post(LedgerService.KIND_PAYOUT, "payout", payoutId, "Payout to " + merchantId, List.of(
                LedgerService.Leg.debit(AccountCodes.merchantPayable(merchantId, amount.currency()), AccountType.LIABILITY, merchantId, amount),
                LedgerService.Leg.credit(AccountCodes.settlementCash(amount.currency()), AccountType.ASSET, null, amount)));
        repo.insertPayout(payoutId, merchantId, amount.minor(), amount.currency(), je.id(), clock.instant());
        return new Paid(payoutId, merchantId, amount.minor(), amount.currency());
    }

    public List<ReconciliationRepository.Payout> payouts(String merchantId) {
        return repo.payouts(merchantId, 100);
    }
}

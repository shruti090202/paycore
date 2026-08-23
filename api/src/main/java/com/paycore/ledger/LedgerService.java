package com.paycore.ledger;

import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import com.paycore.common.money.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Double-entry ledger. Every money movement is a {@link JournalEntry} with {@link Posting}s that sum to zero.
 * <p>
 * The service checks balance in Java (fast feedback) and the database re-checks at commit (the guarantee).
 * Balances are never stored; they are sums over postings. This makes the ledger auditable by construction:
 * any balance can be re-derived, and history cannot be edited (append-only triggers).
 */
@Service
public class LedgerService {

    public static final String KIND_CAPTURE = "capture";
    public static final String KIND_REFUND = "refund";
    public static final String KIND_SETTLEMENT = "settlement";
    public static final String KIND_PAYOUT = "payout";

    public static final String REF_PAYMENT = "payment";
    public static final String REF_REFUND = "refund";

    private final LedgerRepository repo;
    private final JdbcAggregateTemplate template;
    private final Clock clock;

    public LedgerService(LedgerRepository repo, JdbcAggregateTemplate template, Clock clock) {
        this.repo = repo;
        this.template = template;
        this.clock = clock;
    }

    /** A leg to post: which account, which direction, how much. */
    public record Leg(String accountCode, AccountType accountType, String merchantId, Direction direction, Money amount) {
        public static Leg debit(String code, AccountType type, String merchantId, Money amount) {
            return new Leg(code, type, merchantId, Direction.DEBIT, amount);
        }

        public static Leg credit(String code, AccountType type, String merchantId, Money amount) {
            return new Leg(code, type, merchantId, Direction.CREDIT, amount);
        }
    }

    /**
     * Records a capture: the bank now owes us the gross amount; we owe the merchant the net; the fee is ours.
     * <pre>
     *   DR bank_receivable      gross
     *   CR merchant_payable     gross - fee
     *   CR fee_revenue          fee
     * </pre>
     * Must run inside the caller's transaction (MANDATORY) so the ledger entry and the payment state change
     * commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry postCapture(String paymentId, String merchantId, Money gross, Money fee) {
        String ccy = gross.currency();
        Money net = gross.minus(fee);
        if (net.isNegative()) {
            throw new IllegalArgumentException("fee exceeds gross amount");
        }
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(AccountCodes.bankReceivable(ccy), AccountType.ASSET, null, gross));
        if (net.isPositive()) { // a tiny capture can be entirely fee: then the merchant leg is simply absent
            legs.add(Leg.credit(AccountCodes.merchantPayable(merchantId, ccy), AccountType.LIABILITY, merchantId, net));
        }
        if (fee.isPositive()) {
            legs.add(Leg.credit(AccountCodes.feeRevenue(ccy), AccountType.REVENUE, null, fee));
        }
        return post(KIND_CAPTURE, REF_PAYMENT, paymentId, "Capture of " + paymentId, legs);
    }

    /**
     * Records a refund: we owe the merchant less; the bank owes us less (it will claw the money back from us).
     * Fees are not returned on refund (documented policy; see README).
     * <pre>
     *   DR merchant_payable     amount
     *   CR bank_receivable      amount
     * </pre>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry postRefund(String refundId, String paymentId, String merchantId, Money amount) {
        String ccy = amount.currency();
        List<Leg> legs = List.of(
                Leg.debit(AccountCodes.merchantPayable(merchantId, ccy), AccountType.LIABILITY, merchantId, amount),
                Leg.credit(AccountCodes.bankReceivable(ccy), AccountType.ASSET, null, amount));
        return post(KIND_REFUND, REF_REFUND, refundId, "Refund " + refundId + " of " + paymentId, legs);
    }

    /** Generic posting used by the specific flows above and by settlement/payout in later phases. */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry post(String kind, String referenceType, String referenceId, String description, List<Leg> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two legs");
        }
        String ccy = legs.get(0).amount().currency();
        long balance = 0;
        for (Leg leg : legs) {
            if (!leg.amount().currency().equals(ccy)) {
                throw new IllegalArgumentException("mixed currencies in journal entry");
            }
            if (!leg.amount().isPositive()) {
                throw new IllegalArgumentException("posting amounts must be positive");
            }
            balance += leg.direction() == Direction.DEBIT ? leg.amount().minor() : -leg.amount().minor();
        }
        if (balance != 0) {
            throw new IllegalArgumentException("journal entry does not balance: " + balance);
        }

        Instant now = clock.instant();
        JournalEntry entry;
        try {
            entry = template.insert(new JournalEntry(Ids.newId(Ids.JOURNAL_ENTRY), kind, referenceType, referenceId,
                    ccy, description, now));
        } catch (DuplicateKeyException e) {
            // (kind, reference) is unique: the same business event can never be posted twice.
            throw new PayCoreException(ErrorType.CONFLICT, "ledger_entry_exists",
                    kind + " for " + referenceType + " " + referenceId + " is already posted");
        }
        for (Leg leg : legs) {
            LedgerAccount account = repo.ensureAccount(Ids.newId(Ids.ACCOUNT), leg.accountCode(), leg.accountType(),
                    ccy, leg.merchantId(), now);
            template.insert(new Posting(Ids.newId(Ids.POSTING), entry.id(), account.id(), leg.direction().wire(),
                    leg.amount().minor(), ccy, now));
        }
        return entry;
    }

    @Transactional(readOnly = true)
    public Money merchantBalance(String merchantId, String currency) {
        return Money.of(repo.balanceByCode(AccountCodes.merchantPayable(merchantId, currency)), currency);
    }

    @Transactional(readOnly = true)
    public List<LedgerRepository.AccountBalance> merchantBalances(String merchantId) {
        return repo.balancesForMerchant(merchantId);
    }

    public record EntryWithPostings(JournalEntry entry, List<PostingView> postings) {
    }

    public record PostingView(String id, String accountCode, String accountType, String direction, long amountMinor,
                              String currency) {
    }

    @Transactional(readOnly = true)
    public List<EntryWithPostings> entriesFor(String referenceType, String referenceId) {
        List<JournalEntry> entries = repo.findEntriesByReference(referenceType, referenceId);
        List<EntryWithPostings> out = new ArrayList<>();
        for (JournalEntry e : entries) {
            List<PostingView> views = repo.findPostingsByEntry(e.id()).stream().map(this::view).toList();
            out.add(new EntryWithPostings(e, views));
        }
        return out;
    }

    private PostingView view(Posting p) {
        LedgerAccount a = template.findById(p.accountId(), LedgerAccount.class);
        return new PostingView(p.id(), a == null ? p.accountId() : a.code(), a == null ? null : a.type(),
                p.direction(), p.amountMinor(), p.currency());
    }
}

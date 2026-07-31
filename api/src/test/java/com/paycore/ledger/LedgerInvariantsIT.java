package com.paycore.ledger;

import com.paycore.common.id.Ids;
import com.paycore.common.money.Money;
import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantService;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attacks the ledger with raw SQL. Every test here bypasses the Java services on purpose:
 * the point is that Postgres itself refuses to store a broken ledger.
 */
class LedgerInvariantsIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired LedgerService ledger;
    @Autowired LedgerRepository repo;
    @Autowired MerchantService merchants;

    private String account(String code, String type, String ccy) {
        String id = Ids.newId(Ids.ACCOUNT);
        jdbc.sql("INSERT INTO ledger_accounts (id, code, type, currency, created_at) VALUES (:id, :code, :type, :ccy, now())")
                .param("id", id).param("code", code).param("type", type).param("ccy", ccy).update();
        return id;
    }

    private String entry(String ccy) {
        String id = Ids.newId(Ids.JOURNAL_ENTRY);
        jdbc.sql("INSERT INTO journal_entries (id, kind, reference_type, reference_id, currency, created_at) VALUES (:id, 'test', 'test', :ref, :ccy, now())")
                .param("id", id).param("ref", UUID.randomUUID().toString()).param("ccy", ccy).update();
        return id;
    }

    private void posting(String entryId, String accountId, String direction, long amount, String ccy) {
        jdbc.sql("INSERT INTO postings (id, journal_entry_id, account_id, direction, amount_minor, currency, created_at) VALUES (:id, :e, :a, :d, :amt, :ccy, now())")
                .param("id", Ids.newId(Ids.POSTING)).param("e", entryId).param("a", accountId).param("d", direction)
                .param("amt", amount).param("ccy", ccy).update();
    }

    private String uniq(String prefix) {
        return prefix + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        String a = tx.execute(s -> account(uniq("t_asset"), "asset", "INR"));
        String b = tx.execute(s -> account(uniq("t_liab"), "liability", "INR"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            String e = entry("INR");
            posting(e, a, "debit", 100, "INR");
            posting(e, b, "credit", 90, "INR");   // 10 short -> rejected when the deferred trigger fires at COMMIT
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("unbalanced");
    }

    @Test
    void singlePostingAndEmptyEntriesAreRejected() {
        String a = tx.execute(s -> account(uniq("t_asset"), "asset", "INR"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            String e = entry("INR");
            posting(e, a, "debit", 100, "INR");
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("at least two postings");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> entry("INR")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("at least two postings");
    }

    @Test
    void balancedEntryWithinOneTransactionCommits() {
        String a = tx.execute(s -> account(uniq("t_asset"), "asset", "INR"));
        String b = tx.execute(s -> account(uniq("t_liab"), "liability", "INR"));

        String e = tx.execute(s -> {
            String id = entry("INR");
            posting(id, a, "debit", 100, "INR");   // transiently unbalanced here...
            posting(id, b, "credit", 100, "INR");  // ...balanced by commit
            return id;
        });
        assertThat(repo.findPostingsByEntry(e)).hasSize(2);
    }

    @Test
    void currencyMustMatchAccountAndEntry() {
        String inr = tx.execute(s -> account(uniq("t_inr"), "asset", "INR"));
        String usd = tx.execute(s -> account(uniq("t_usd"), "liability", "USD"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            String e = entry("INR");
            posting(e, inr, "debit", 100, "INR");
            posting(e, usd, "credit", 100, "USD");   // posting currency != entry currency
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("currency");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            String e = entry("INR");
            posting(e, inr, "debit", 100, "INR");
            posting(e, usd, "credit", 100, "INR");   // INR posting on a USD account
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("currency");
    }

    @Test
    void ledgerRowsCannotBeUpdatedOrDeleted() {
        String a = tx.execute(s -> account(uniq("t_asset"), "asset", "INR"));
        String b = tx.execute(s -> account(uniq("t_liab"), "liability", "INR"));
        String e = tx.execute(s -> {
            String id = entry("INR");
            posting(id, a, "debit", 100, "INR");
            posting(id, b, "credit", 100, "INR");
            return id;
        });

        assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount_minor = 999 WHERE journal_entry_id = :e").param("e", e).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings WHERE journal_entry_id = :e").param("e", e).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entries SET description = 'x' WHERE id = :e").param("e", e).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries WHERE id = :e").param("e", e).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repo.findPostingsByEntry(e)).hasSize(2).allSatisfy(p -> assertThat(p.amountMinor()).isEqualTo(100));
    }

    @Test
    void negativeOrZeroPostingsAreRejected() {
        String a = tx.execute(s -> account(uniq("t_asset"), "asset", "INR"));
        String b = tx.execute(s -> account(uniq("t_liab"), "liability", "INR"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            String e = entry("INR");
            posting(e, a, "debit", -100, "INR");
            posting(e, b, "debit", 100, "INR");   // would "balance" with a negative debit
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void captureFlowPostsThreeLegsAndMerchantBalanceIsDerived() {
        Merchant m = merchants.signup("Ledger Co", Api.uniqueEmail(), "correct-horse-battery");
        String paymentRef = "pay_test_" + UUID.randomUUID().toString().substring(0, 8);

        JournalEntry entry = tx.execute(s -> ledger.postCapture(paymentRef, m.id(), Money.of(10_000, "INR"), Money.of(500, "INR")));

        var postings = repo.findPostingsByEntry(entry.id());
        assertThat(postings).hasSize(3);
        assertThat(postings.stream().mapToLong(Posting::signedMinor).sum()).isZero();
        assertThat(ledger.merchantBalance(m.id(), "INR")).isEqualTo(Money.of(9_500, "INR"));
        assertThat(repo.balanceByCode(AccountCodes.feeRevenue("INR"))).isGreaterThanOrEqualTo(500);

        // Posting the same capture twice is impossible (unique on kind + reference).
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                ledger.postCapture(paymentRef, m.id(), Money.of(10_000, "INR"), Money.of(500, "INR"))))
                .hasMessageContaining("already posted");

        // Global invariant: the entire ledger nets to zero.
        assertThat(repo.sumOfAllPostingsSigned()).isZero();
    }

    @Test
    void ledgerServiceRefusesUnbalancedLegsBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post("adjustment", "test", UUID.randomUUID().toString(), "bad",
                java.util.List.of(
                        LedgerService.Leg.debit(uniq("x"), AccountType.ASSET, null, Money.of(100, "INR")),
                        LedgerService.Leg.credit(uniq("y"), AccountType.LIABILITY, null, Money.of(99, "INR"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not balance");
    }

    @Test
    void ensureAccountIsIdempotent() {
        String code = uniq("idem");
        LedgerAccount first = repo.ensureAccount(Ids.newId(Ids.ACCOUNT), code, AccountType.ASSET, "INR", null, Instant.now());
        LedgerAccount second = repo.ensureAccount(Ids.newId(Ids.ACCOUNT), code, AccountType.ASSET, "INR", null, Instant.now());
        assertThat(second.id()).isEqualTo(first.id());
    }
}

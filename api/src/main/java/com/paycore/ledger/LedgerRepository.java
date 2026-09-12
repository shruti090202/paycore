package com.paycore.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Hand-written SQL for the ledger: reads by reference, balances from the view, account upsert. */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LedgerAccount> findAccountByCode(String code) {
        return jdbc.sql("SELECT * FROM ledger_accounts WHERE code = :code")
                .param("code", code).query(LedgerAccount.class).optional();
    }

    /** Insert-if-absent, then read. */
    public LedgerAccount ensureAccount(String id, String code, AccountType type, String currency, String merchantId,
                                       java.time.Instant now) {
        jdbc.sql("""
                INSERT INTO ledger_accounts (id, code, type, currency, merchant_id, created_at)
                VALUES (:id, :code, :type, :currency, :merchantId, :now)
                ON CONFLICT (code) DO NOTHING
                """)
                .param("id", id).param("code", code).param("type", type.wire()).param("currency", currency)
                .param("merchantId", merchantId).param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .update();
        return findAccountByCode(code).orElseThrow();
    }

    public List<JournalEntry> findEntriesByReference(String referenceType, String referenceId) {
        return jdbc.sql("SELECT * FROM journal_entries WHERE reference_type = :t AND reference_id = :id ORDER BY id")
                .param("t", referenceType).param("id", referenceId).query(JournalEntry.class).list();
    }

    public List<Posting> findPostingsByEntry(String entryId) {
        return jdbc.sql("SELECT * FROM postings WHERE journal_entry_id = :id ORDER BY id")
                .param("id", entryId).query(Posting.class).list();
    }

    public List<Posting> findPostingsByEntries(List<String> entryIds) {
        if (entryIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM postings WHERE journal_entry_id IN (:ids) ORDER BY id")
                .param("ids", entryIds).query(Posting.class).list();
    }

    /** Natural-direction balance from the {@code account_balances} view; 0 for an account that does not exist yet. */
    public long balanceByCode(String code) {
        return jdbc.sql("SELECT balance_minor FROM account_balances WHERE code = :code")
                .param("code", code).query(Long.class).optional().orElse(0L);
    }

    public List<AccountBalance> balancesForMerchant(String merchantId) {
        return jdbc.sql("""
                SELECT account_id, code, type, currency, merchant_id, balance_minor, posting_count
                FROM account_balances WHERE merchant_id = :m ORDER BY code
                """).param("m", merchantId).query(AccountBalance.class).list();
    }

    public List<AccountBalance> allBalances() {
        return jdbc.sql("""
                SELECT account_id, code, type, currency, merchant_id, balance_minor, posting_count
                FROM account_balances ORDER BY code
                """).query(AccountBalance.class).list();
    }

    /** Global invariant check used by tests and the reconciliation job: the whole ledger must net to zero. */
    public long sumOfAllPostingsSigned() {
        return jdbc.sql("SELECT COALESCE(SUM(CASE WHEN direction = 'debit' THEN amount_minor ELSE -amount_minor END), 0) FROM postings")
                .query(Long.class).single();
    }

    public record AccountBalance(String accountId, String code, String type, String currency, String merchantId,
                                 long balanceMinor, long postingCount) {
    }
}

package com.paycore.api.dto;

import com.paycore.ledger.LedgerService;

import java.time.Instant;
import java.util.List;

public final class LedgerDtos {

    private LedgerDtos() {
    }

    public record PostingDto(String id, String accountCode, String accountType, String direction, long amountMinor,
                             String currency) {
    }

    public record JournalEntryDto(String id, String kind, String referenceType, String referenceId, String currency,
                                  String description, Instant createdAt, List<PostingDto> postings) {
    }

    public static JournalEntryDto from(LedgerService.EntryWithPostings e) {
        List<PostingDto> postings = e.postings().stream()
                .map(p -> new PostingDto(p.id(), p.accountCode(), p.accountType(), p.direction(), p.amountMinor(), p.currency()))
                .toList();
        var je = e.entry();
        return new JournalEntryDto(je.id(), je.kind(), je.referenceType(), je.referenceId(), je.currency(),
                je.description(), je.createdAt(), postings);
    }

    public record BalanceDto(String accountCode, String type, String currency, long balanceMinor, long postingCount) {
    }
}

package com.paycore.payments;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BankAttemptRepository extends CrudRepository<BankAttempt, String> {

    List<BankAttempt> findByPaymentIdOrderByIdAsc(String paymentId);

    Optional<BankAttempt> findByBankRef(String bankRef);

    @Modifying
    @Query("UPDATE bank_attempts SET outcome = :outcome, decline_code = :declineCode, latency_ms = :latencyMs WHERE id = :id")
    int recordOutcome(@Param("id") String id, @Param("outcome") String outcome, @Param("declineCode") String declineCode,
                      @Param("latencyMs") int latencyMs);

    @Modifying
    @Query("UPDATE bank_attempts SET resolution = :resolution, resolved_at = :at WHERE id = :id AND resolved_at IS NULL")
    int recordResolution(@Param("id") String id, @Param("resolution") String resolution, @Param("at") Instant at);
}

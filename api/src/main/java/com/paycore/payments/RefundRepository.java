package com.paycore.payments;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends CrudRepository<Refund, String> {

    Optional<Refund> findByIdAndMerchantId(String id, String merchantId);

    List<Refund> findByPaymentIdOrderByIdAsc(String paymentId);

    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_id = :paymentId AND status = 'pending'")
    long sumPending(@Param("paymentId") String paymentId);

    @Query("SELECT * FROM refunds WHERE status = 'pending' AND created_at < :before ORDER BY id LIMIT :limit")
    List<Refund> findStalePending(@Param("before") java.time.Instant before, @Param("limit") int limit);
}

package com.paycore.payments;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends CrudRepository<Payment, String> {

    Optional<Payment> findByIdAndMerchantId(String id, String merchantId);

    Optional<Payment> findByCheckoutToken(String checkoutToken);

    /** Row lock for every state change. */
    @Query("SELECT * FROM payments WHERE id = :id AND merchant_id = :merchantId FOR UPDATE")
    Optional<Payment> lockByIdAndMerchantId(@Param("id") String id, @Param("merchantId") String merchantId);

    @Query("SELECT * FROM payments WHERE id = :id FOR UPDATE")
    Optional<Payment> lockById(@Param("id") String id);

    @Query("SELECT * FROM payments WHERE checkout_token = :token FOR UPDATE")
    Optional<Payment> lockByCheckoutToken(@Param("token") String token);

    @Query("SELECT * FROM payments WHERE status = 'pending_bank' AND updated_at < :before ORDER BY id LIMIT :limit")
    java.util.List<Payment> findStalePendingBank(@Param("before") java.time.Instant before, @Param("limit") int limit);
}

package com.paycore.payments;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends CrudRepository<Payment, String> {

    Optional<Payment> findByIdAndMerchantId(String id, String merchantId);

    Optional<Payment> findByCheckoutToken(String checkoutToken);

    /**
     * Row lock for every state change. Concurrent captures/refunds/cancels on one payment serialize here,
     * so the "read counters, validate, write counters" sequence is atomic per payment.
     */
    @Query("SELECT * FROM payments WHERE id = :id AND merchant_id = :merchantId FOR UPDATE")
    Optional<Payment> lockByIdAndMerchantId(@Param("id") String id, @Param("merchantId") String merchantId);

    @Query("SELECT * FROM payments WHERE id = :id FOR UPDATE")
    Optional<Payment> lockById(@Param("id") String id);
}

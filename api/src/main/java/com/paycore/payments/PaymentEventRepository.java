package com.paycore.payments;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface PaymentEventRepository extends CrudRepository<PaymentEvent, String> {

    List<PaymentEvent> findByPaymentIdOrderByIdAsc(String paymentId);
}

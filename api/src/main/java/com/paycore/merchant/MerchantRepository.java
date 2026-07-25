package com.paycore.merchant;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface MerchantRepository extends CrudRepository<Merchant, String> {

    Optional<Merchant> findByEmail(String email);

    Optional<Merchant> findFirstByIsDemoTrue();
}

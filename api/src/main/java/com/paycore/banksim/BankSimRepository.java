package com.paycore.banksim;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface BankSimRepository extends CrudRepository<BankSimTransaction, String> {

    Optional<BankSimTransaction> findByBankRef(String bankRef);
}

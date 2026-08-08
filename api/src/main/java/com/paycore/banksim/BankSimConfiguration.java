package com.paycore.banksim;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BankSimConfiguration {

    @Bean
    public BankSimConfig bankSimConfig() {
        return new BankSimConfig();
    }
}

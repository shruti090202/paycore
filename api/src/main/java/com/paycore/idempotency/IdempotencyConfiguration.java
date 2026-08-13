package com.paycore.idempotency;

import com.paycore.auth.MerchantApiFilter;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration
public class IdempotencyConfiguration {

    /** After rate limiting, before the controller. */
    public static final int ORDER = 200;

    @Bean
    public MerchantApiFilter idempotencyApiFilter(IdempotencyStore store, ObjectMapper mapper, Clock clock) {
        IdempotencyFilter filter = new IdempotencyFilter(store, mapper, clock);
        return new MerchantApiFilter() {
            @Override
            public Filter filter() {
                return filter;
            }

            @Override
            public int order() {
                return ORDER;
            }
        };
    }
}

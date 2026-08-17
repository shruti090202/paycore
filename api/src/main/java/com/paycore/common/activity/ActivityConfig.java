package com.paycore.common.activity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ActivityConfig {

    @Bean
    public ActivityGate activityGate(Clock clock, @Value("${paycore.scheduler.active-window-seconds:600}") long seconds) {
        return new ActivityGate(clock, Duration.ofSeconds(seconds));
    }
}

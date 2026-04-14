package com.customercare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Spring Boot entry point.
 *
 * <p>{@code @SpringBootApplication} scans from {@code com.customercare}, picking up
 * all {@code @Component}/{@code @Service}/{@code @Repository} beans across the
 * {@code domain}, {@code infra}, and {@code app} modules automatically.
 */
@SpringBootApplication
public class CustomerCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerCareApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}


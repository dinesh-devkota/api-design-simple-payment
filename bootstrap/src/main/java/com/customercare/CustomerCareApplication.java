package com.customercare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point.
 *
 * <p>{@code @SpringBootApplication} scans from {@code com.customercare}, picking up
 * all {@code @Component}/{@code @Service}/{@code @Repository} beans across the
 * {@code domain}, {@code infra}, and {@code app} modules automatically.
 *
 * <p>The {@link java.time.Clock} bean is provided by
 * {@link com.customercare.config.ClockConfig} — set {@code app.fixed-date=YYYY-MM-DD}
 * in a profile config to pin the date for manual Swagger testing.
 */
@SpringBootApplication
public class CustomerCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerCareApplication.class, args);
    }
}


package com.customercare.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Provides the application-wide {@link Clock} bean.
 *
 * <p>Two variants are available:
 *
 * <ul>
 *   <li><b>Fixed clock</b> — activated by setting {@code app.fixed-date=YYYY-MM-DD} in any
 *       Spring profile config (e.g. {@code application-local.yml}).  Useful for manually testing
 *       weekend due-date shifting via Swagger UI without waiting for the right calendar day.
 *
 *       <pre>
 *       Dates that trigger a Saturday shift → Monday in Swagger:
 *         app.fixed-date=2026-04-17   # Friday  +15 = 2026-05-02 (Sat) → 2026-05-04 (Mon)
 *         app.fixed-date=2026-04-18   # Saturday +15 = 2026-05-03 (Sun) → 2026-05-05 (Mon)
 *       </pre>
 *
 *   <li><b>System clock</b> — default when {@code app.fixed-date} is absent.
 * </ul>
 */
@Configuration
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    /**
     * Fixed clock — used when {@code app.fixed-date} is configured.
     *
     * <p>The date string must be ISO-8601 format: {@code YYYY-MM-DD}.
     * The clock is anchored to midnight in the system's default timezone so that
     * {@code LocalDate.now(clock)} returns exactly the configured date.
     */
    @Bean
    @ConditionalOnProperty(name = "app.fixed-date")
    public Clock fixedClock(@Value("${app.fixed-date}") String fixedDate) {
        ZoneId zone = ZoneId.systemDefault();
        Clock clock = Clock.fixed(
                LocalDate.parse(fixedDate).atStartOfDay(zone).toInstant(),
                zone);
        log.warn("*** FIXED CLOCK ACTIVE — app.fixed-date={} ({}). Do NOT use in production. ***",
                fixedDate, LocalDate.parse(fixedDate).getDayOfWeek());
        return clock;
    }

    /**
     * Real system clock — default when {@code app.fixed-date} is not set.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}


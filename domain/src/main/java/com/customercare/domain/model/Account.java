package com.customercare.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Domain model representing a customer account.
 *
 * <p>This is a pure domain object — it carries no persistence annotations.
 * Redis-specific mapping (e.g. {@code @RedisHash}) lives in the infra module's
 * {@code AccountEntity}, which is mapped to/from this class by {@code AccountEntityMapper}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    /** Unique user identifier. */
    private String userId;

    /** Current outstanding balance (scale 2). */
    private BigDecimal balance;
}


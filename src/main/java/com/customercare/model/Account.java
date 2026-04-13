package com.customercare.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.math.BigDecimal;

/**
 * Redis-backed domain object representing a customer account.
 * Stored as a Redis Hash under key {@code account:{userId}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("account")
public class Account {

    /** Primary identifier — becomes the Redis hash key. */
    @Id
    private String userId;

    /** Current outstanding balance (scale 2). Stored as a string in Redis. */
    private BigDecimal balance;
}


package com.customercare.infra.redis.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.math.BigDecimal;

/**
 * Redis persistence entity for an account.
 *
 * <p>This class owns all Redis/Spring-Data annotations so the domain model
 * ({@code Account}) can remain a plain POJO.  {@code AccountEntityMapper}
 * translates between the two representations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("account")
public class AccountEntity {

    @Id
    private String userId;

    private BigDecimal balance;
}


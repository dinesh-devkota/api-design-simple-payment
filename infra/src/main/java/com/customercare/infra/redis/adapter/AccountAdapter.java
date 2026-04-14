package com.customercare.infra.redis.adapter;

import com.customercare.domain.model.Account;
import com.customercare.domain.spi.AccountSpi;
import com.customercare.infra.redis.mapper.AccountEntityMapper;
import com.customercare.infra.redis.repository.AccountRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Secondary-port adapter that fulfils the domain's {@link AccountSpi} contract
 * using Spring Data Redis ({@link AccountRedisRepository}).
 *
 * <p>This is the only class that needs to change if the persistence store is
 * swapped (e.g. Redis → JPA).  The domain and app modules are unaffected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountAdapter implements AccountSpi {

    private final AccountRedisRepository accountRedisRepository;
    private final AccountEntityMapper    accountEntityMapper;

    @Override
    public Optional<Account> findById(String userId) {
        log.debug("Looking up account: userId={}", userId);
        Optional<Account> result = accountRedisRepository.findById(userId)
                .map(accountEntityMapper::toDomain);
        if (result.isEmpty()) {
            log.debug("Account not found in Redis: userId={}", userId);
        }
        return result;
    }

    @Override
    public Account save(Account account) {
        log.debug("Saving account: userId={} balance={}", account.getUserId(), account.getBalance());
        return accountEntityMapper.toDomain(
                accountRedisRepository.save(accountEntityMapper.toEntity(account)));
    }
}


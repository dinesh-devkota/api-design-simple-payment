package com.customercare.domain.spi;

import com.customercare.domain.model.Account;

import java.util.Optional;

/**
 * Secondary port (SPI) — declares what the domain needs from a persistence store.
 *
 * <p>The domain defines this interface; the infra module provides the implementation
 * ({@code AccountAdapter}) backed by Redis. To swap to any other store, only the infra
 * adapter changes — the domain and app modules remain untouched.
 */
public interface AccountSpi {

    /**
     * Retrieves an account by its user identifier.
     *
     * @param userId the unique user identifier
     * @return the account wrapped in {@link Optional}, or empty if not found
     */
    Optional<Account> findById(String userId);

    /**
     * Persists (creates or updates) the given account.
     *
     * @param account the account to save
     * @return the saved account
     */
    Account save(Account account);
}


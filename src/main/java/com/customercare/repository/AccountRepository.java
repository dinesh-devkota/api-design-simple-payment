package com.customercare.repository;

import com.customercare.model.Account;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Redis repository for {@link Account}.
 *
 * <p>Spring auto-generates the Redis implementation at startup.
 * To swap to Oracle/JPA in a future iteration, replace this interface with one that
 * extends {@code JpaRepository<Account, String>} — no service or controller changes required.
 */
@Repository
public interface AccountRepository extends CrudRepository<Account, String> {
}


package com.customercare.infra.redis.repository;

import com.customercare.infra.redis.entity.AccountEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Redis repository for {@link AccountEntity}.
 *
 * <p>Spring auto-generates the implementation at startup.  This interface is intentionally
 * kept in the infra module so that the domain never sees Redis-specific types.
 */
@Repository
public interface AccountRedisRepository extends CrudRepository<AccountEntity, String> {
}


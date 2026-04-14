package com.customercare.infra.redis.mapper;

import com.customercare.domain.model.Account;
import com.customercare.infra.redis.entity.AccountEntity;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper translating between the Redis persistence entity
 * ({@link AccountEntity}) and the domain model ({@link Account}).
 *
 * <p>Generated implementation ({@code AccountEntityMapperImpl}) is created at
 * compile time.  Do <strong>not</strong> edit it by hand.
 */
@Mapper(componentModel = "spring")
public interface AccountEntityMapper {

    /** Converts a Redis entity to the domain model. */
    Account toDomain(AccountEntity entity);

    /** Converts a domain model to the Redis entity. */
    AccountEntity toEntity(Account account);
}


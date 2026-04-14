package com.customercare.app.mapper;

import com.customercare.domain.payment.PaymentResult;
import com.customercare.dto.OneTimePaymentResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper that converts the domain's {@link PaymentResult} into the
 * contract DTO {@link OneTimePaymentResponse}.
 *
 * <p>All field names match between source and target, so no {@code @Mapping}
 * annotations are required.  The generated implementation ({@code PaymentResponseMapperImpl})
 * is created at compile time — do <strong>not</strong> edit it by hand.
 */
@Mapper(componentModel = "spring")
public interface PaymentResponseMapper {

    OneTimePaymentResponse toResponse(PaymentResult result);
}


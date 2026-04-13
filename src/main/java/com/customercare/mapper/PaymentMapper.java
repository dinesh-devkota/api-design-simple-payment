package com.customercare.mapper;

import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import com.customercare.service.PaymentCalculationResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;

/**
 * MapStruct mapper that assembles a {@link OneTimePaymentResponse} from three sources:
 * <ol>
 *   <li>The original {@link OneTimePaymentRequest} (userId, paymentAmount)</li>
 *   <li>The account's {@code previousBalance} before deduction</li>
 *   <li>The {@link PaymentCalculationResult} (match tier, amounts, new balance, due date)</li>
 * </ol>
 *
 * <p>The implementation class ({@code PaymentMapperImpl}) is generated at compile time
 * by MapStruct. Do <strong>not</strong> edit it by hand.
 */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    /**
     * Builds the HTTP response DTO from the payment request, the pre-deduction balance,
     * and the calculated payment result.
     *
     * @param request          the original payment request
     * @param previousBalance  account balance <em>before</em> the payment (mapped by name)
     * @param result           calculated match, updated balance, and next due date
     * @return fully populated payment response
     */
    @Mapping(source = "request.userId",         target = "userId")
    @Mapping(source = "previousBalance",         target = "previousBalance")
    @Mapping(source = "request.paymentAmount",  target = "paymentAmount")
    @Mapping(source = "result.matchPercentage", target = "matchPercentage")
    @Mapping(source = "result.matchAmount",     target = "matchAmount")
    @Mapping(source = "result.newBalance",      target = "newBalance")
    @Mapping(source = "result.nextDueDate",     target = "nextPaymentDueDate")
    OneTimePaymentResponse toPaymentResponse(
            OneTimePaymentRequest    request,
            BigDecimal               previousBalance,
            PaymentCalculationResult result);
}


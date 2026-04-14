package com.customercare.domain.payment;

import java.math.BigDecimal;

/**
 * Primary port (API) — the inbound use-case for processing a one-time payment.
 *
 * <p>The app module drives this port; the domain provides the implementation
 * ({@link ProcessPaymentService}).  Controllers and any other entry-points talk to
 * the domain exclusively through this interface.
 */
public interface ProcessPaymentUseCase {

    /**
     * Processes a one-time payment: validates the amount, debits the account,
     * applies the tier-based match, and calculates the next due date.
     *
     * @param userId        the customer's identifier
     * @param paymentAmount the amount being paid; must be {@code > 0}
     * @return fully computed {@link PaymentResult}
     */
    PaymentResult process(String userId, BigDecimal paymentAmount);
}


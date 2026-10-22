package com.ledger.settlement;

import java.time.Instant;

/**
 * API view of a recorded payment, kept separate from PaymentEntity so the database
 * shape can change without changing the API contract.
 */
public record PaymentResponse(String id, String merchantId, long amountMinor, String currency, Instant recordedAt) {

    static PaymentResponse from(PaymentEntity payment) {
        return new PaymentResponse(payment.getId(), payment.getMerchantId(), payment.getAmountMinor(),
                payment.getCurrency(), payment.getRecordedAt());
    }
}

package com.ledger.settlement;

/**
 * Thrown when settlement is requested for a merchant with no recorded payments.
 */
public class UnknownMerchantException extends RuntimeException {

    private final String merchantId;

    public UnknownMerchantException(String merchantId) {
        super("No payments recorded for merchant " + merchantId);
        this.merchantId = merchantId;
    }

    public String getMerchantId() {
        return merchantId;
    }
}

package com.ledger.settlement;

/**
 * What a merchant is owed: the payment total less the settlement fee, all in minor units.
 */
public record Settlement(String merchantId, long totalMinor, long feeMinor, long owedMinor, String currency) {
}

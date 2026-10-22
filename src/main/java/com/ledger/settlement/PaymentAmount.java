package com.ledger.settlement;

/**
 * Read-only projection of a payment: only the two columns settlement needs. Spring Data selects
 * just these columns and returns them without creating managed entities, so neither recorded_at
 * (timestamp conversion) nor the persistence context is touched.
 */
public interface PaymentAmount {

    long getAmountMinor();

    String getCurrency();
}

package com.ledger.settlement;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Currency reference data used when settling a merchant.
 *
 * Fetched once, eagerly, when Spring creates this bean at startup. Singletons are created before
 * the web server opens its port, so the blocking HTTP call runs on the startup (platform) thread
 * before any request exists. Request threads (virtual) only read an immutable map: nothing
 * blocks in a class initializer, so nothing pins. Replaces a static {} fetch (docs/threads.md).
 */
@Component
class CurrencyTable {

    private final Map<String, Integer> minorUnits;

    CurrencyTable(ReferenceDataClient referenceDataClient) {
        this.minorUnits = Map.copyOf(referenceDataClient.fetchCurrencyTable());
    }

    void requireSupported(String currency) {
        if (!minorUnits.containsKey(currency)) {
            throw new IllegalStateException("Currency not in reference data: " + currency);
        }
    }
}

package com.ledger.settlement;

import java.util.Map;

/**
 * Currency reference data used when settling a merchant.
 *
 * DELIBERATE DEFECT (Lab 1, step 6): the table is fetched over HTTP inside the class initializer.
 * The first request that touches this class runs that blocking call inside <clinit>, where a
 * virtual thread cannot unmount, so it pins its carrier; every other request that touches the
 * class meanwhile waits for initialization to finish. Only a cold JVM shows it.
 */
final class CurrencyTable {

    private static final Map<String, Integer> MINOR_UNITS;

    static {
        MINOR_UNITS = ReferenceDataClient.fetchCurrencyTable();
    }

    private CurrencyTable() {
    }

    static void requireSupported(String currency) {
        if (!MINOR_UNITS.containsKey(currency)) {
            throw new IllegalStateException("Currency not in reference data: " + currency);
        }
    }
}

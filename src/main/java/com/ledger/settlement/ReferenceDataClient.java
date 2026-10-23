package com.ledger.settlement;

import java.util.Map;

/** Client for the reference-data service that owns the currency table. */
interface ReferenceDataClient {

    /** Currency code -> number of minor-unit digits (ISO 4217 exponent). Blocks on the network. */
    Map<String, Integer> fetchCurrencyTable();
}

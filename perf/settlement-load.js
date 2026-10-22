// Lab 1 load profile: 200 concurrent virtual users hammering the main read endpoint for 60 s.
// Every run in Lab 1 (baseline, virtual threads, pinned, fixed) uses this file unchanged.
//
//   k6 run perf/settlement-load.js
//   k6 run -e DURATION=15s perf/settlement-load.js      (warm-up)
//
// Seed the merchant first with perf/seed-load-merchant.sql.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANT = __ENV.MERCHANT || 'MR-LOAD';
const EXPECTED_OWED = Number(__ENV.EXPECTED_OWED || 2611698);

export const options = {
    vus: 200,
    duration: __ENV.DURATION || '60s',
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        // A run with errors is not a valid measurement; make that loud.
        checks: ['rate==1.0'],
        http_req_failed: ['rate==0'],
    },
};

export default function () {
    const res = http.get(`${BASE_URL}/payments/settlement?merchantId=${MERCHANT}`, {
        tags: { name: 'GET /payments/settlement' },
    });
    check(res, {
        'status is 200': (r) => r.status === 200,
        'owedMinor is correct': (r) => r.status === 200 && r.json('owedMinor') === EXPECTED_OWED,
    });
}

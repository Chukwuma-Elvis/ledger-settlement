// Lab 2 load: a constant arrival rate against the busiest endpoint for ten minutes.
//
//   k6 run perf/steady-load.js
//
// Busiest endpoint: GET /payments/settlement reads and sums every payment of a merchant
// (500 rows for MR-LOAD, seeded by perf/seed-load-merchant.sql); it is the most expensive request
// the service serves. Rate: 300 requests/s, about a third of the ~950 req/s this endpoint sustained
// in Lab 1, so the service is busy and allocating but not saturated (queueing would hide GC effects).
// Constant arrival rate (not fixed VUs) keeps the offered load identical even if one run is slower.
//
// Stages: 1 minute warm-up at the same rate, then the measured 10 minutes. Start the service with
// -XX:StartFlightRecording=delay=60s,duration=10m,... so the recording covers exactly the steady 10 minutes.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 300);

const steady = {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 400,
};

export const options = {
    scenarios: {
        warmup: { ...steady, duration: __ENV.WARMUP || '1m' },
        steady: { ...steady, duration: __ENV.DURATION || '10m', startTime: __ENV.WARMUP || '1m' },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        // Results come from the measured 10 minutes only.
        'http_req_duration{scenario:steady}': ['p(99)<2000'],
        'http_req_failed{scenario:steady}': ['rate==0'],
        'checks{scenario:steady}': ['rate==1.0'],
        'dropped_iterations{scenario:steady}': ['count==0'],
        'http_reqs{scenario:steady}': ['count>0'],
    },
};

export default function () {
    const res = http.get(`${BASE_URL}/payments/settlement?merchantId=MR-LOAD`, {
        tags: { name: 'GET /payments/settlement' },
    });
    check(res, {
        'status is 200': (r) => r.status === 200,
        'owedMinor is correct': (r) => r.status === 200 && r.json('owedMinor') === 2611698,
    });
}

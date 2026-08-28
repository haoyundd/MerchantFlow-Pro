import http from 'k6/http';
import { check, sleep } from 'k6';

const vus = Number(__ENV.K6_VUS || 80);
const duration = __ENV.K6_DURATION || '3m';

export const options = {
  stages: [
    { duration: '20s', target: Math.max(10, Math.floor(vus / 2)) },
    { duration, target: vus },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.20'],
  },
};

const target = __ENV.TARGET_URL || 'http://backend:8081';

export default function () {
  const response = http.get(`${target}/shop-type/list`, {
    headers: { 'X-Request-ID': `k6-${__VU}-${__ITER}` },
  });
  check(response, { 'business endpoint responded': (res) => res.status === 200 });
  sleep(0.05);
}

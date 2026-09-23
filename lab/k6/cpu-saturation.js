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
const scenarioToken = __ENV.LAB_SCENARIO_TOKEN || '';

export default function () {
  // 调用仅 Lab Profile 存在的有界 CPU 接口；每次最多运行 1 秒且不分配大对象。
  // 下一步：Prometheus 同时观察该 URI 的 P95 与 JVM process_cpu_usage。
  const response = http.post(`${target}/internal/lab/cpu?durationMs=1000`, null, {
    headers: {
      'X-Request-ID': `k6-cpu-${__VU}-${__ITER}`,
      'X-Lab-Scenario-Token': scenarioToken,
    },
    tags: { lab_scenario: 'cpu-saturation' },
  });
  check(response, { 'bounded CPU endpoint responded': (res) => res.status === 200 });
  sleep(0.02);
}

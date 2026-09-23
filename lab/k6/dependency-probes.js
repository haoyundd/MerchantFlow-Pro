import http from 'k6/http';
import { sleep } from 'k6';

// 这个脚本只产生真实 HTTP 流量，不修改数据库，也不伪造 Prometheus 指标。
// 下一步：配合 scenarios.ps1 的 Toxiproxy 故障，让 Redis/MySQL 的真实异常进入观测链路。
const targetUrl = __ENV.TARGET_URL || 'http://backend:8081';
const scenario = __ENV.SCENARIO || 'redis';

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '90s',
  thresholds: {
    // 故障实验允许请求失败，探针本身不能因为预期故障提前停止。
    http_req_failed: [],
  },
};

function request(path) {
  // 每次请求都保留真实响应，让 Spring Boot、Micrometer 和 OpenTelemetry 记录结果。
  http.get(`${targetUrl}${path}`, {
    tags: { lab_scenario: scenario },
  });
}

export default function () {
  if (scenario === 'redis') {
    // 店铺类型接口会读取 Redis 缓存，是 Redis 延迟/中断的主要业务探针。
    request('/shop-type/list');
    request('/shop/1');
  } else if (scenario === 'mysql') {
    // 优惠券列表直接查询数据库，是 MySQL 延迟/中断的主要业务探针。
    request('/voucher/list/1');
    request(`/shop/1?lab_request=${__VU}-${__ITER}`);
  } else if (scenario === 'health') {
    // 后端停止时，这个请求会真实失败；恢复后会重新返回健康状态。
    request('/actuator/health');
  } else {
    // 未知场景不静默运行，避免实验命令写错却没有产生有效流量。
    throw new Error(`Unsupported lab probe scenario: ${scenario}`);
  }

  // 控制请求速率，避免探针本身把机器压垮，CPU 场景使用独立的 cpu-saturation.js。
  sleep(0.2);
}

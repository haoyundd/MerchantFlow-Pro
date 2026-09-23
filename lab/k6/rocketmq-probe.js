import http from 'k6/http';
import { check } from 'k6';

// 这个探针只发送一次真实秒杀请求，用来触发 RocketMQ Producer 的发送路径。
// 下一步：配合 scenarios.ps1 的 rocketmq-cut，让 AIOps 从 5xx、日志和 Trace 取证。
const targetUrl = __ENV.TARGET_URL || 'http://backend:8081';
const account = __ENV.LOGIN_ACCOUNT || '';
const password = __ENV.LOGIN_PASSWORD || '';
if (!account || !password) {
  throw new Error('LOGIN_ACCOUNT and LOGIN_PASSWORD are required for the RocketMQ lab probe');
}
const voucherIds = (__ENV.VOUCHER_IDS || __ENV.VOUCHER_ID || '8')
  .split(',')
  .map((item) => item.trim())
  .filter((item) => item.length > 0);

export const options = {
  // RocketMQ 实验不需要持续压测，单请求更容易区分故障信号并控制业务副作用。
  vus: 1,
  iterations: 1,
  thresholds: {
    // Broker 故障时请求失败是预期结果，不能让 k6 阈值提前掩盖实验结果。
    http_req_failed: [],
  },
};

export function setup() {
  // 复用项目现有账号密码登录接口，拿到真实 Token，不绕过登录拦截器。
  const response = http.post(
    `${targetUrl}/user/login`,
    JSON.stringify({ phone: account, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(response, {
    'login request completed': (item) => item.status >= 200 && item.status < 500,
  });
  const body = response.json();
  const token = body && body.data ? body.data : '';
  if (!token) {
    throw new Error(`Login failed with status=${response.status}`);
  }
  return { token };
}

export default function (data) {
  // 每个优惠券只请求一次，避免同一用户的 Redis 一人一单规则阻断后续请求。
  // 下一步：每个请求都必须结合响应、Loki 和 Trace 证明已经进入 RocketMQ 发送路径。
  for (const voucherId of voucherIds) {
    const response = http.post(
      `${targetUrl}/voucher-order/seckill/${voucherId}`,
      null,
      {
        headers: {
          Authorization: data.token,
          'User-Agent': 'aiops-lab-rocketmq-probe/1.0',
        },
        tags: { lab_scenario: 'rocketmq', voucher_id: voucherId },
        timeout: '35s',
      },
    );
    const payload = response.json();
    const errorMessage = payload && payload.errorMsg;
    const publishFailureObserved = payload
      && payload.success === false
      && errorMessage === '下单失败，请稍后重试';
    // 只接受 Producer 发送失败这一条明确业务结果。活动过期、库存不足或重复下单
    // 都代表请求尚未完成本轮 MQ 故障路径，必须立即让实验失败，不能靠 HTTP 200 猜测。
    console.log(
      `rocketmq probe voucher=${voucherId} status=${response.status} success=${payload && payload.success} error=${errorMessage}`,
    );
    check(response, {
      [`seckill request reached backend voucher=${voucherId}`]: (item) => item.status >= 200 || item.status === 0,
      [`RocketMQ publish failure observed voucher=${voucherId}`]: () => publishFailureObserved,
    });
    if (!publishFailureObserved) {
      throw new Error(`Expected RocketMQ publish failure but got: ${errorMessage || 'unknown response'}`);
    }
  }
}

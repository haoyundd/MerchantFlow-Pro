package com.hmdp.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 验证业务故障指标只使用固定标签，避免把订单号、用户号等高基数字段写入 Prometheus。
 */
class MerchantFlowMetricsTest {

    @Test
    void mqPublishFailureCounterMustAccumulateByWhitelistedFlow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantFlowMetrics metrics = new MerchantFlowMetrics(registry);

        // 首次故障前必须已经存在 0 基线，否则 Prometheus increase() 会漏掉第一次增量。
        Assertions.assertEquals(
                0.0,
                registry.get("merchantflow.mq.publish.failures")
                        .tag("flow", MerchantFlowMetrics.FLOW_SECKILL_ORDER)
                        .counter()
                        .count()
        );
        metrics.recordMqPublishFailure(MerchantFlowMetrics.FLOW_SECKILL_ORDER);
        metrics.recordMqPublishFailure(MerchantFlowMetrics.FLOW_SECKILL_ORDER);
        metrics.recordMqPublishFailure("unexpected-order-123");

        Assertions.assertEquals(
                2.0,
                registry.get("merchantflow.mq.publish.failures")
                        .tag("flow", MerchantFlowMetrics.FLOW_SECKILL_ORDER)
                        .counter()
                        .count()
        );
        Assertions.assertEquals(
                1.0,
                registry.get("merchantflow.mq.publish.failures")
                        .tag("flow", MerchantFlowMetrics.FLOW_OTHER)
                        .counter()
                        .count()
        );
        Assertions.assertEquals(
                0.0,
                registry.get("merchantflow.mq.publish.failures")
                        .tag("flow", MerchantFlowMetrics.FLOW_CACHE_DELETE_COMPENSATION)
                        .counter()
                        .count()
        );
    }
}

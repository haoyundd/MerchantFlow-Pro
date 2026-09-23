package com.hmdp.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * MerchantFlow 业务故障指标统一出口。
 *
 * <p>指标标签只允许固定业务流，禁止写入订单号、用户号、异常文本等高基数字段，
 * 防止 Prometheus 时间序列数量失控。下一步由 Prometheus 告警规则和 AIOps MCP
 * 工具读取这些真实计数，不再依赖 HTTP 5xx 猜测消息发送是否失败。</p>
 */
@Component
public class MerchantFlowMetrics {

    public static final String FLOW_SECKILL_ORDER = "seckill-order";
    public static final String FLOW_CACHE_DELETE_COMPENSATION = "cache-delete-compensation";
    public static final String FLOW_OTHER = "other";

    private static final Set<String> ALLOWED_MQ_FLOWS = new HashSet<>(Arrays.asList(
            FLOW_SECKILL_ORDER,
            FLOW_CACHE_DELETE_COMPENSATION
    ));

    private final Map<String, Counter> mqPublishFailureCounters = new HashMap<>();

    /**
     * @param meterRegistry Spring Actuator 使用的指标注册中心
     */
    public MerchantFlowMetrics(MeterRegistry meterRegistry) {
        // Counter 必须在应用启动时以 0 注册。若等第一次失败才创建，Prometheus 首个样本就是 1，
        // increase() 没有零基线会得到 0，从而漏掉第一次真实故障。
        Set<String> flows = new HashSet<>(ALLOWED_MQ_FLOWS);
        flows.add(FLOW_OTHER);
        for (String flow : flows) {
            Counter counter = Counter.builder("merchantflow.mq.publish.failures")
                    .description("MerchantFlow RocketMQ publish failures")
                    .tag("flow", flow)
                    .register(meterRegistry);
            mqPublishFailureCounters.put(flow, counter);
        }
        // 下一步：Prometheus 至少抓取两个零基线样本后，故障实验才允许停止 Broker。
    }

    /**
     * 记录一次 RocketMQ 发布失败。
     *
     * @param flow 固定的业务流名称；未知值会折叠为 {@code other}
     */
    public void recordMqPublishFailure(String flow) {
        String safeFlow = ALLOWED_MQ_FLOWS.contains(flow) ? flow : FLOW_OTHER;
        mqPublishFailureCounters.get(safeFlow).increment();
        // 下一步：Prometheus 的 increase(...[5m]) 触发告警，AIOps 再通过 MCP 取证。
    }
}

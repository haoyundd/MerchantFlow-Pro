package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 隔离实验环境的真实故障负载入口。
 *
 * <p>该 Bean 只在 Spring {@code lab} Profile 注册，且必须携带独立实验令牌。
 * CPU 循环有 100-5000ms 硬上限，不创建集合或大对象，避免把 CPU 实验变成内存故障。</p>
 */
@Profile("lab")
@RestController
@RequestMapping("/internal/lab")
public class LabFaultController {

    private static volatile long cpuSink;
    private final String scenarioToken;

    public LabFaultController(@Value("${LAB_SCENARIO_TOKEN:}") String scenarioToken) {
        this.scenarioToken = scenarioToken;
    }

    /**
     * 执行有界 CPU 运算。
     *
     * @param token 实验环境专用令牌
     * @param durationMs 单次执行时长，必须在 100-5000ms
     * @return 403 表示令牌错误，400 表示时长越界，200 表示真实运算完成
     */
    @PostMapping("/cpu")
    public ResponseEntity<Result> cpuLoad(
            @RequestHeader(value = "X-Lab-Scenario-Token", required = false) String token,
            @RequestParam(defaultValue = "1000") int durationMs
    ) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail("实验令牌无效"));
        }
        if (durationMs < 100 || durationMs > 5000) {
            return ResponseEntity.badRequest().body(Result.fail("durationMs 必须在 100-5000 之间"));
        }

        long deadline = System.nanoTime() + durationMs * 1_000_000L;
        long value = System.nanoTime() | 1L;
        while (System.nanoTime() < deadline) {
            // 仅执行整数混合运算，不分配集合、数组或字符串；volatile 写防止 JIT 删除整个循环。
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
        }
        cpuSink = value;
        // 下一步：k6 持续调用本接口，Prometheus 从真实 JVM/HTTP 指标触发 HighCPU 告警。
        return ResponseEntity.ok(Result.ok(durationMs));
    }

    /** 使用常量时间比较令牌，且拒绝服务端或请求端空令牌。 */
    private boolean tokenMatches(String token) {
        if (scenarioToken == null || scenarioToken.isEmpty() || token == null || token.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                scenarioToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }
}

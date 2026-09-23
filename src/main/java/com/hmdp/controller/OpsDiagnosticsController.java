package com.hmdp.controller;

import com.hmdp.annotation.AkSkAuth;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitType;
import com.hmdp.dto.Result;
import com.hmdp.service.ThreadSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部运维诊断入口。
 *
 * <p>所有接口必须同时具备 AK/SK 防重放认证和全局限流；该路径不通过 Nginx
 * 对外暴露，AIOps 的 mcp-ops 通过 Docker 内部网络访问。</p>
 */
@RestController
@RequestMapping("/internal/ops")
public class OpsDiagnosticsController {

    private final ThreadSnapshotService threadSnapshotService;

    public OpsDiagnosticsController(ThreadSnapshotService threadSnapshotService) {
        this.threadSnapshotService = threadSnapshotService;
    }

    /**
     * 返回有界线程快照；下一步由 MCP 持久化为 CPU 诊断证据。
     */
    @GetMapping("/thread-snapshot")
    @AkSkAuth
    @RateLimit(max = 6, windowSeconds = 60, type = RateLimitType.GLOBAL)
    public Result threadSnapshot() {
        return Result.ok(threadSnapshotService.snapshot());
    }
}

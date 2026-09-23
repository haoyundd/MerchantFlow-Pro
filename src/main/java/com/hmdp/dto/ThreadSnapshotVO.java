package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 受控 JVM 线程快照响应。
 *
 * <p>只包含线程调度状态、CPU 增量和结构化栈帧，不包含线程本地变量、请求参数、
 * 环境变量或系统属性。下一步由 AIOps MCP 将该结果作为 CPU 根因的补充证据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreadSnapshotVO {

    @JsonProperty("sampled_at_epoch_ms")
    private long sampledAtEpochMs;

    @JsonProperty("sample_interval_ms")
    private long sampleIntervalMs;

    @JsonProperty("cpu_time_supported")
    private boolean cpuTimeSupported;

    @Builder.Default
    private List<ThreadItem> threads = new ArrayList<>();

    /** 单个热点线程的有限字段。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThreadItem {
        private long id;
        private String name;
        private String state;

        @JsonProperty("cpu_delta_nanos")
        private long cpuDeltaNanos;

        @JsonProperty("blocked_count")
        private long blockedCount;

        @JsonProperty("waited_count")
        private long waitedCount;

        @Builder.Default
        @JsonProperty("stack_frames")
        private List<StackFrameItem> stackFrames = new ArrayList<>();
    }

    /** 栈帧不包含方法参数和值，只返回定位源码所需坐标。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StackFrameItem {
        @JsonProperty("class_name")
        private String className;

        @JsonProperty("method_name")
        private String methodName;

        @JsonProperty("file_name")
        private String fileName;

        @JsonProperty("line_number")
        private int lineNumber;

        @JsonProperty("native_method")
        private boolean nativeMethod;
    }
}

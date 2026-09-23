package com.hmdp.service;

import com.hmdp.dto.ThreadSnapshotVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 ThreadMXBean 做两次短采样，输出有界的热点线程快照。
 *
 * <p>该服务不会生成文件，也不会读取堆、线程本地变量、环境变量或请求内容；
 * 适合在 CPU 告警时作为只读诊断工具。</p>
 */
@Service
public class ThreadSnapshotService {

    private final ThreadMXBean threadMXBean;
    private final long sampleIntervalMs;
    private final int topThreads;
    private final int maxFrames;

    /**
     * 生产构造器会对采样参数再做边界限制，避免配置错误造成长时间阻塞或大响应。
     */
    @Autowired
    public ThreadSnapshotService(
            @Value("${hmdp.ops.thread-snapshot.sample-interval-ms:200}") long sampleIntervalMs,
            @Value("${hmdp.ops.thread-snapshot.top-threads:10}") int topThreads,
            @Value("${hmdp.ops.thread-snapshot.max-frames:20}") int maxFrames
    ) {
        this(ManagementFactory.getThreadMXBean(), sampleIntervalMs, topThreads, maxFrames);
    }

    /** 测试和本地验证构造器，仍应用与生产一致的安全上限。 */
    ThreadSnapshotService(
            ThreadMXBean threadMXBean,
            long sampleIntervalMs,
            int topThreads,
            int maxFrames
    ) {
        this.threadMXBean = threadMXBean;
        this.sampleIntervalMs = Math.min(Math.max(sampleIntervalMs, 10L), 1000L);
        this.topThreads = Math.min(Math.max(topThreads, 1), 10);
        this.maxFrames = Math.min(Math.max(maxFrames, 1), 20);
    }

    /**
     * 采集热点线程。
     *
     * @return 最多 10 个线程、每线程最多 20 个结构化栈帧
     * @throws IllegalStateException 采样线程被中断时抛出，调用方不能返回不完整快照
     */
    public ThreadSnapshotVO snapshot() {
        boolean cpuSupported = enableCpuTimeIfPossible();
        Map<Long, Long> before = readCpuTimes(cpuSupported);
        sleepSampleInterval();
        Map<Long, Long> after = readCpuTimes(cpuSupported);

        List<ThreadDelta> deltas = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : after.entrySet()) {
            long first = before.getOrDefault(entry.getKey(), entry.getValue());
            deltas.add(new ThreadDelta(entry.getKey(), Math.max(entry.getValue() - first, 0L)));
        }
        deltas.sort(Comparator.comparingLong(ThreadDelta::getCpuDeltaNanos).reversed());

        List<ThreadSnapshotVO.ThreadItem> threads = new ArrayList<>();
        for (ThreadDelta delta : deltas) {
            if (threads.size() >= topThreads) {
                break;
            }
            ThreadInfo info = threadMXBean.getThreadInfo(delta.getThreadId(), maxFrames);
            if (info != null) {
                threads.add(toThreadItem(info, delta.getCpuDeltaNanos()));
            }
        }
        // 下一步：Controller 返回该有限结构，AIOps 只在 CPU 超阈值时通过 MCP 获取。
        return ThreadSnapshotVO.builder()
                .sampledAtEpochMs(System.currentTimeMillis())
                .sampleIntervalMs(sampleIntervalMs)
                .cpuTimeSupported(cpuSupported)
                .threads(threads)
                .build();
    }

    /** 尝试启用 CPU 时间；权限不允许时仍可返回线程状态和栈帧。 */
    private boolean enableCpuTimeIfPossible() {
        if (!threadMXBean.isThreadCpuTimeSupported()) {
            return false;
        }
        try {
            if (!threadMXBean.isThreadCpuTimeEnabled()) {
                threadMXBean.setThreadCpuTimeEnabled(true);
            }
            return threadMXBean.isThreadCpuTimeEnabled();
        } catch (SecurityException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    /** 读取全部存活线程的 CPU 时间，负值按零处理。 */
    private Map<Long, Long> readCpuTimes(boolean cpuSupported) {
        Map<Long, Long> times = new HashMap<>();
        for (long threadId : threadMXBean.getAllThreadIds()) {
            long value = cpuSupported ? threadMXBean.getThreadCpuTime(threadId) : 0L;
            times.put(threadId, Math.max(value, 0L));
        }
        return times;
    }

    /** 采样等待有最大一秒上限，并正确恢复中断标记。 */
    private void sleepSampleInterval() {
        try {
            Thread.sleep(sampleIntervalMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("线程快照采样被中断", exception);
        }
    }

    /** 将 JDK ThreadInfo 映射为不含参数和值的安全响应。 */
    private ThreadSnapshotVO.ThreadItem toThreadItem(ThreadInfo info, long cpuDeltaNanos) {
        List<ThreadSnapshotVO.StackFrameItem> frames = new ArrayList<>();
        StackTraceElement[] stackTrace = info.getStackTrace();
        for (int index = 0; index < stackTrace.length && index < maxFrames; index++) {
            StackTraceElement frame = stackTrace[index];
            frames.add(ThreadSnapshotVO.StackFrameItem.builder()
                    .className(frame.getClassName())
                    .methodName(frame.getMethodName())
                    .fileName(frame.getFileName())
                    .lineNumber(frame.getLineNumber())
                    .nativeMethod(frame.isNativeMethod())
                    .build());
        }
        return ThreadSnapshotVO.ThreadItem.builder()
                .id(info.getThreadId())
                .name(info.getThreadName())
                .state(String.valueOf(info.getThreadState()))
                .cpuDeltaNanos(cpuDeltaNanos)
                .blockedCount(info.getBlockedCount())
                .waitedCount(info.getWaitedCount())
                .stackFrames(frames)
                .build();
    }

    /** 内部排序对象，不对外暴露。 */
    private static class ThreadDelta {
        private final long threadId;
        private final long cpuDeltaNanos;

        ThreadDelta(long threadId, long cpuDeltaNanos) {
            this.threadId = threadId;
            this.cpuDeltaNanos = cpuDeltaNanos;
        }

        long getThreadId() {
            return threadId;
        }

        long getCpuDeltaNanos() {
            return cpuDeltaNanos;
        }
    }
}

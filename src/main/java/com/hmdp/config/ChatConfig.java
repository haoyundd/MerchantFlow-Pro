package com.hmdp.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 为聊天流统一配置 HTTP 生命周期和有界执行线程池。 */
@Configuration
public class ChatConfig {

    @Bean
    public OkHttpClient okHttpClient(
            @Value("${hmdp.chat.stream.call-timeout-seconds:150}") long callTimeoutSeconds) {
        // readTimeout 只限制两次读取之间的空闲时间；callTimeout 才能限制完整 SSE 调用的总时长。
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 创建有界聊天线程池。
     *
     * <p>固定线程数限制同时占用的 LLM 流，有限队列限制等待任务占用的内存；队列满后
     * 立即拒绝，由 Controller 返回 SSE error，下一步不会再进入外部模型调用。</p>
     */
    @Bean(name = "chatExecutor", destroyMethod = "shutdown")
    public ExecutorService chatExecutor(
            @Value("${hmdp.chat.executor.pool-size:10}") int poolSize,
            @Value("${hmdp.chat.executor.queue-capacity:50}") int queueCapacity) {
        int safePoolSize = Math.max(1, poolSize);
        int safeQueueCapacity = Math.max(1, queueCapacity);
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                safePoolSize,
                safePoolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(safeQueueCapacity),
                runnable -> new Thread(runnable, "chat-stream-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}

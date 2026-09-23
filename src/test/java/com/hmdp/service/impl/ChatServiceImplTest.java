package com.hmdp.service.impl;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.hmdp.config.ChatConfig;

/**
 * 聊天流资源边界测试。
 *
 * <p>这些测试不访问真实大模型，专门验证无限 SSE 和任务堆积不会无限占用内存。</p>
 */
class ChatServiceImplTest {

    @Test
    void streamMustStopWhenAccumulatedContentExceedsLimit() throws Exception {
        ChatServiceImpl service = new ChatServiceImpl();
        ReflectionTestUtils.setField(service, "maxStreamCharacters", 5);
        ReflectionTestUtils.setField(service, "maxStreamEvents", 10);

        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"abc\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"def\"}}]}\n\n";
        try (Response response = streamResponse(stream)) {
            IOException error = Assertions.assertThrows(IOException.class,
                    () -> service.parseStreamResponse(new SseEmitter(), response));
            Assertions.assertTrue(error.getMessage().contains("字符"));
        }
    }

    @Test
    void boundedExecutorMustRejectWhenPoolAndQueueAreFull() throws Exception {
        ChatConfig config = new ChatConfig();
        ExecutorService executor = config.chatExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> await(release));
            executor.execute(() -> await(release));

            Assertions.assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> await(release)));
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    /** 创建只包含内存 SSE 内容的 OkHttp Response，避免测试依赖外部网络。 */
    private Response streamResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://localhost/chat").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.parse("text/event-stream")))
                .build();
    }

    /** 等待测试释放线程；中断时恢复标记，避免吞掉测试框架的取消信号。 */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

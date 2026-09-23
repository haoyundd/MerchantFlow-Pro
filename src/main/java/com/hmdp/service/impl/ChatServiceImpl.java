package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.ChatMessageVO;
import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IChatService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CHAT_SESSION_KEY;
import static com.hmdp.utils.RedisConstants.CHAT_SESSION_TTL;
import static com.hmdp.utils.RedisConstants.CHAT_USER_SESSION_KEY;

@Slf4j
@Service
public class ChatServiceImpl implements IChatService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "chatExecutor")
    private ExecutorService chatExecutor;

    @Resource
    private OkHttpClient okHttpClient;

    @Value("${hmdp.chat.model.base-url}")
    private String modelBaseUrl;

    @Value("${hmdp.chat.model.api-key}")
    private String modelApiKey;

    @Value("${hmdp.chat.model.model-name}")
    private String modelName;

    @Value("${hmdp.chat.model.temperature}")
    private Double temperature;

    @Value("${hmdp.chat.model.max-tokens}")
    private Integer maxTokens;

    @Value("${hmdp.chat.session.ttl-minutes}")
    private Integer sessionTtlMinutes;

    @Value("${hmdp.chat.session.max-history}")
    private Integer maxHistory;

    @Value("${hmdp.chat.stream.max-characters:32768}")
    private Integer maxStreamCharacters;

    @Value("${hmdp.chat.stream.max-events:1024}")
    private Integer maxStreamEvents;

    @Override
    public SseEmitter chat(ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicReference<Call> activeCall = new AtomicReference<>();
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        // 浏览器断开、SSE 超时或服务端完成时都取消上游 Call，避免后台读取循环继续占用线程。
        Runnable closeStream = () -> {
            streamClosed.set(true);
            Call call = activeCall.getAndSet(null);
            if (call != null && !call.isCanceled()) {
                call.cancel();
            }
        };
        emitter.onTimeout(closeStream);
        emitter.onCompletion(closeStream);
        emitter.onError(error -> closeStream.run());

        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        String sessionId = getOrCreateSession(userId, request.getSessionId());

        try {
            chatExecutor.execute(() -> {
                if (streamClosed.get()) {
                    return;
                }
                try {
                    List<JSONObject> history = loadHistory(sessionId);

                    JSONObject userMsg = new JSONObject();
                    userMsg.set("role", "user");
                    userMsg.set("content", request.getMessage());
                    history.add(userMsg);

                    saveRedisMessage(sessionId, "user", request.getMessage());

                    // 下一步进入受总超时、字符数和事件数约束的模型 SSE 调用。
                    doChatLoop(emitter, history, sessionId, activeCall, streamClosed);

                    if (!streamClosed.get()) {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    }
                } catch (Exception e) {
                    if (!streamClosed.get()) {
                        log.error("AI dialogue error", e);
                        sendStreamError(emitter, "Service unavailable, please try again later", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 有界队列已满时快速失败，不能继续堆积 Runnable 造成内存压力。
            sendStreamError(emitter, "Chat service is busy, please try again later", e);
        }

        return emitter;
    }

    @Override
    public Result history() {
        UserDTO user = UserHolder.getUser();
        String userKey = CHAT_USER_SESSION_KEY + user.getId();
        String sessionId = stringRedisTemplate.opsForValue().get(userKey);
        if (sessionId == null) {
            return Result.ok(Collections.emptyList());
        }
        List<JSONObject> messages = loadHistory(sessionId);
        List<ChatMessageVO> vos = messages.stream()
                .map(m -> new ChatMessageVO(
                        m.getStr("role"),
                        m.getStr("content"),
                        m.getStr("timestamp")))
                .collect(Collectors.toList());
        return Result.ok(vos);
    }

    @Override
    public Result clearSession() {
        UserDTO user = UserHolder.getUser();
        String userKey = CHAT_USER_SESSION_KEY + user.getId();
        String sessionId = stringRedisTemplate.opsForValue().get(userKey);
        if (sessionId != null) {
            stringRedisTemplate.delete(CHAT_SESSION_KEY + sessionId);
        }
        stringRedisTemplate.delete(userKey);
        return Result.ok();
    }

    /** 调用上游模型并在任何路径关闭 Response；下一步由解析器执行流大小限制。 */
    private void doChatLoop(SseEmitter emitter, List<JSONObject> messages,
                            String sessionId, AtomicReference<Call> activeCall,
                            AtomicBoolean streamClosed) throws IOException {
        JSONObject requestBody = buildChatRequest(messages);

        Request httpRequest = new Request.Builder()
                .url(modelBaseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + modelApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                .build();

        Call call = okHttpClient.newCall(httpRequest);
        activeCall.set(call);
        if (streamClosed.get()) {
            call.cancel();
            return;
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("LLM call failed, status={}, body={}", response.code(), errorBody);
                throw new IOException("AI service returned HTTP " + response.code());
            }

            String fullContent = parseStreamResponse(emitter, response, streamClosed);
            if (!fullContent.isEmpty() && !streamClosed.get()) {
                saveRedisMessage(sessionId, "assistant", fullContent);
            }
        } finally {
            activeCall.compareAndSet(call, null);
        }
    }

    /** 同包测试入口；生产调用使用带关闭标记的重载。 */
    String parseStreamResponse(SseEmitter emitter, Response response) throws IOException {
        return parseStreamResponse(emitter, response, new AtomicBoolean(false));
    }

    /**
     * 解析 OpenAI 兼容 SSE。
     *
     * <p>事件数、单行长度和累计字符数都有硬上限，避免上游持续发送数据时
     * `StringBuilder` 无界增长；触发限制后抛出 IOException，由上一层结束 SSE 并释放线程。</p>
     */
    private String parseStreamResponse(SseEmitter emitter, Response response,
                                       AtomicBoolean streamClosed) throws IOException {
        if (response.body() == null) {
            throw new IOException("AI service returned an empty stream");
        }
        StringBuilder contentBuilder = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()));

        String line;
        int eventCount = 0;
        while ((line = reader.readLine()) != null) {
            if (streamClosed.get()) {
                throw new IOException("聊天流已关闭");
            }
            if (line.length() > maxStreamCharacters) {
                throw new IOException("聊天流单行字符数超过限制");
            }
            if (line.isEmpty() || !line.startsWith("data: ")) {
                continue;
            }
            String data = line.substring(6);
            if ("[DONE]".equals(data)) {
                break;
            }
            eventCount++;
            if (eventCount > maxStreamEvents) {
                throw new IOException("聊天流事件数超过限制");
            }
            String content;
            try {
                JSONObject chunk = JSONUtil.parseObj(data);
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                if (delta == null) {
                    continue;
                }
                content = delta.getStr("content");
            } catch (Exception e) {
                log.debug("Parse SSE line failed: {}", line);
                continue;
            }
            if (content != null) {
                if (contentBuilder.length() + content.length() > maxStreamCharacters) {
                    throw new IOException("聊天流累计字符数超过限制");
                }
                contentBuilder.append(content);
                emitter.send(SseEmitter.event().name("text").data(content));
            }
        }
        return contentBuilder.toString();
    }

    /** 向前端发送统一错误事件并结束 emitter；错误对象只写日志，不暴露给浏览器。 */
    private void sendStreamError(SseEmitter emitter, String message, Exception error) {
        log.warn("Chat stream stopped: {}", error.getMessage());
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
    }

    private JSONObject buildChatRequest(List<JSONObject> messages) {
        JSONObject body = new JSONObject();
        body.set("model", modelName);
        body.set("temperature", temperature);
        body.set("max_tokens", maxTokens);

        List<JSONObject> fullMessages = new ArrayList<>();
        fullMessages.add(buildSystemMessage());
        fullMessages.addAll(messages);
        body.set("messages", fullMessages);

        body.set("stream", true);

        return body;
    }

    private JSONObject buildSystemMessage() {
        JSONObject sys = new JSONObject();
        sys.set("role", "system");
        sys.set("content",
                "You are the AI customer service assistant for 'Merchant Review' local life service platform.\n\n" +
                "## Platform Introduction\n" +
                "- The platform provides merchant query, voucher flash sale, and review note sharing features\n" +
                "- Merchant types: 1=Food, 2=KTV, 3=Hotel, 4=Shopping, 5=Beauty, " +
                "6=Entertainment, 7=Travel, 8=Education, 9=Life Services, 10=Fitness\n\n" +
                "## Your Capabilities\n" +
                "1. Query merchants by type (e.g., 'any good food nearby?')\n" +
                "2. Search merchants by name\n" +
                "3. Query merchant vouchers and flash sales\n" +
                "4. View popular review notes\n" +
                "5. Answer platform usage questions\n\n" +
                "## Response Requirements\n" +
                "- Only answer questions related to local life, merchants, vouchers, and platform usage\n" +
                "- If users ask out-of-scope questions, politely decline and guide them to relevant topics\n" +
                "- Use concise, friendly Chinese replies\n" +
                "- Use Markdown format where appropriate to improve readability");
        return sys;
    }

    private String getOrCreateSession(Long userId, String requestSessionId) {
        if (requestSessionId != null && !requestSessionId.isEmpty()) {
            String key = CHAT_SESSION_KEY + requestSessionId;
            Boolean exists = stringRedisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                return requestSessionId;
            }
        }
        String newSessionId = UUID.randomUUID().toString(true);
        String userKey = CHAT_USER_SESSION_KEY + userId;
        stringRedisTemplate.opsForValue().set(userKey, newSessionId,
                CHAT_SESSION_TTL, TimeUnit.MINUTES);
        return newSessionId;
    }

    private List<JSONObject> loadHistory(String sessionId) {
        String key = CHAT_SESSION_KEY + sessionId;
        List<String> list = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        if (list.size() > maxHistory) {
            list = list.subList(list.size() - maxHistory, list.size());
        }
        return list.stream()
                .map(JSONUtil::parseObj)
                .collect(Collectors.toList());
    }

    private void saveRedisMessage(String sessionId, String role, String content) {
        String key = CHAT_SESSION_KEY + sessionId;
        JSONObject msg = new JSONObject();
        msg.set("role", role);
        msg.set("content", content);
        msg.set("timestamp", LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        stringRedisTemplate.opsForList().rightPush(key, msg.toString());
        stringRedisTemplate.expire(key, CHAT_SESSION_TTL, TimeUnit.MINUTES);
    }
}

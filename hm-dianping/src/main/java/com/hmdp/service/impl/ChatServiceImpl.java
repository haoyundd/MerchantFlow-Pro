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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(10);

    @Override
    public SseEmitter chat(ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(180_000L);

        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        String sessionId = getOrCreateSession(userId, request.getSessionId());

        chatExecutor.execute(() -> {
            try {
                List<JSONObject> history = loadHistory(sessionId);

                JSONObject userMsg = new JSONObject();
                userMsg.set("role", "user");
                userMsg.set("content", request.getMessage());
                history.add(userMsg);

                saveRedisMessage(sessionId, "user", request.getMessage());

                doChatLoop(emitter, history, sessionId);

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.error("AI dialogue error", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("Service unavailable, please try again later"));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

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

    private void doChatLoop(SseEmitter emitter, List<JSONObject> messages,
                            String sessionId) throws IOException {
        JSONObject requestBody = buildChatRequest(messages);

        Request httpRequest = new Request.Builder()
                .url(modelBaseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + modelApiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                .build();

        Response response = okHttpClient.newCall(httpRequest).execute();
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "";
            log.error("LLM call failed, status={}, body={}", response.code(), errorBody);
            emitter.send(SseEmitter.event().name("error")
                    .data("AI service call failed, please try again later"));
            return;
        }

        String fullContent = parseStreamResponse(emitter, response);
        if (fullContent != null && !fullContent.isEmpty()) {
            saveRedisMessage(sessionId, "assistant", fullContent);
        }
    }

    private String parseStreamResponse(SseEmitter emitter, Response response) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()));

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || !line.startsWith("data: ")) {
                continue;
            }
            String data = line.substring(6);
            if ("[DONE]".equals(data)) {
                break;
            }
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
                String content = delta.getStr("content");
                if (content != null) {
                    contentBuilder.append(content);
                    emitter.send(SseEmitter.event().name("text").data(content));
                }
            } catch (Exception e) {
                log.debug("Parse SSE line failed: {}", line);
            }
        }
        return contentBuilder.toString();
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
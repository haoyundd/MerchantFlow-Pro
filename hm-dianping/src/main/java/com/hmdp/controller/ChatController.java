package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitType;
import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IChatService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private IChatService chatService;

    @PostMapping("/send")
    @RateLimit(max = 10, windowSeconds = 30, type = RateLimitType.USER)
    public SseEmitter send(@RequestBody ChatRequestDTO request) {
        return chatService.chat(request);
    }

    @GetMapping("/history")
    public Result history() {
        return chatService.history();
    }

    @DeleteMapping("/session/clear")
    public Result clearSession() {
        return chatService.clearSession();
    }
}
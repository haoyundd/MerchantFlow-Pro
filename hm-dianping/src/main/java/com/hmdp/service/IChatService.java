package com.hmdp.service;

import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.Result;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IChatService {

    SseEmitter chat(ChatRequestDTO request);

    Result history();

    Result clearSession();
}
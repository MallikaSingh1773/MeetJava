package com.mallika.meetjava.config;

import com.mallika.meetjava.signal.AgentHandler;
import com.mallika.meetjava.signal.SignalingHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingHandler signalingHandler;
    private final AgentHandler agentHandler;

    public WebSocketConfig(SignalingHandler signalingHandler, AgentHandler agentHandler) {
        this.signalingHandler = signalingHandler;
        this.agentHandler = agentHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingHandler, "/signal").setAllowedOriginPatterns("*");
        registry.addHandler(agentHandler, "/agent").setAllowedOriginPatterns("*");
    }
}

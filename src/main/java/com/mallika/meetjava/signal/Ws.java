package com.mallika.meetjava.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small shared helpers for the two WebSocket endpoints (/signal and /agent). */
public final class Ws {

    private static final Logger log = LoggerFactory.getLogger(Ws.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private Ws() { }

    public static void send(WebSocketSession session, Map<String, Object> payload) {
        if (session == null || !session.isOpen()) return;
        try {
            String text = JSON.writeValueAsString(payload);
            // Spring's WebSocketSession is not safe for concurrent sends.
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.warn("send failed on session {}: {}", session.getId(), e.getMessage());
        }
    }

    public static void error(WebSocketSession session, String message) {
        Map<String, Object> err = SignalMessage.of(SignalMessage.ERROR);
        err.put("message", message);
        send(session, err);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String payload) throws IOException {
        return JSON.readValue(payload, Map.class);
    }

    public static Map<String, String> queryParams(URI uri) {
        Map<String, String> out = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) return out;
        for (String pair : uri.getRawQuery().split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}

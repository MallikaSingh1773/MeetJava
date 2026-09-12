package com.mallika.meetjava.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * WebSocket link from this desktop agent to the MeetJava server.
 *
 * Uses java.net.http.WebSocket from the JDK, so the agent needs no networking
 * library at all. Frames can arrive split, so text is accumulated until the
 * server marks the message complete.
 */
public class ControlClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final Consumer<Map<String, Object>> onMessage;
    private final Consumer<String> onStatus;

    private volatile WebSocket socket;
    private final StringBuilder buffer = new StringBuilder();

    public ControlClient(Consumer<Map<String, Object>> onMessage, Consumer<String> onStatus) {
        this.onMessage = onMessage;
        this.onStatus = onStatus;
    }

    /** serverBase looks like http://localhost:8080 */
    public void connect(String serverBase, String meetingCode, String displayName) {
        String ws = serverBase.trim()
                .replaceFirst("^http://", "ws://")
                .replaceFirst("^https://", "wss://")
                .replaceAll("/+$", "");
        URI uri = URI.create(ws + "/agent"
                + "?code=" + enc(meetingCode.trim().toLowerCase())
                + "&name=" + enc(displayName.trim()));

        onStatus.accept("Connecting to " + uri.getHost() + "…");

        http.newWebSocketBuilder()
            .buildAsync(uri, new Listener())
            .whenComplete((sock, err) -> {
                if (err != null) {
                    onStatus.accept("Could not connect: " + rootMessage(err));
                } else {
                    socket = sock;
                }
            });
    }

    public void send(Map<String, Object> payload) {
        WebSocket s = socket;
        if (s == null) return;
        try {
            s.sendText(JSON.writeValueAsString(payload), true);
        } catch (Exception e) {
            onStatus.accept("Send failed: " + e.getMessage());
        }
    }

    public void disconnect() {
        WebSocket s = socket;
        socket = null;
        if (s != null) s.sendClose(WebSocket.NORMAL_CLOSURE, "agent closed");
    }

    public boolean isConnected() { return socket != null; }

    public static Map<String, Object> message(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    private class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    onMessage.accept(JSON.readValue(text, Map.class));
                } catch (Exception e) {
                    onStatus.accept("Bad message from server: " + e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            onStatus.accept("Disconnected" + (reason == null || reason.isBlank() ? "" : ": " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            onStatus.accept("Connection error: " + rootMessage(error));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

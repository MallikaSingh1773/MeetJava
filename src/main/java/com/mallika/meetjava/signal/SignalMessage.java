package com.mallika.meetjava.signal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every frame on the signaling socket is one JSON object with a "type".
 * Kept as a loose map on purpose: SDP and ICE payloads are browser-defined
 * blobs that the server must relay untouched, never parse or rewrite.
 */
public final class SignalMessage {

    private SignalMessage() { }

    // client -> server
    public static final String OFFER            = "offer";
    public static final String ANSWER           = "answer";
    public static final String ICE              = "ice";
    public static final String CHAT             = "chat";
    public static final String MEDIA_STATE      = "media-state";
    public static final String CONTROL_REQUEST  = "control-request";
    public static final String CONTROL_RESPONSE = "control-response";
    public static final String CONTROL_INPUT    = "control-input";
    public static final String CONTROL_END      = "control-end";

    // server -> client
    public static final String WELCOME     = "welcome";
    public static final String PEER_JOINED = "peer-joined";
    public static final String PEER_LEFT   = "peer-left";
    public static final String ERROR        = "error";
    public static final String AGENT_STATUS = "agent-status";
    public static final String AGENT_READY  = "agent-ready";

    public static Map<String, Object> of(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }
}

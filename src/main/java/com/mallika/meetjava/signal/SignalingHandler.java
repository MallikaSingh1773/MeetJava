package com.mallika.meetjava.signal;

import com.mallika.meetjava.model.ChatMessage;
import com.mallika.meetjava.model.ControlSession;
import com.mallika.meetjava.model.Meeting;
import com.mallika.meetjava.model.Participant;
import com.mallika.meetjava.service.MeetingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;

/**
 * The signaling server.
 *
 * It never touches media. Audio, video and screen share flow browser-to-browser
 * over WebRTC; this class only carries the handshake (SDP offers/answers and ICE
 * candidates), plus chat, presence, and remote-control permission traffic.
 */
@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);

    private final RoomRegistry registry;
    private final AgentRegistry agents;
    private final MeetingService meetings;

    @Value("${meetjava.ice.stun:stun:stun.l.google.com:19302}")
    private String stunUrl;
    @Value("${meetjava.ice.turn.url:}")
    private String turnUrl;
    @Value("${meetjava.ice.turn.username:}")
    private String turnUsername;
    @Value("${meetjava.ice.turn.credential:}")
    private String turnCredential;

    public SignalingHandler(RoomRegistry registry, AgentRegistry agents, MeetingService meetings) {
        this.registry = registry;
        this.agents = agents;
        this.meetings = meetings;
    }

    // ------------------------------------------------------------------ connect

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> params = Ws.queryParams(session.getUri());
        String code = Optional.ofNullable(params.get("code")).orElse("").trim().toLowerCase();
        String name = Optional.ofNullable(params.get("name")).orElse("Guest").trim();

        Optional<Meeting> meeting = meetings.find(code);
        if (meeting.isEmpty()) {
            Ws.error(session, "Meeting " + code + " does not exist.");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("unknown meeting"));
            return;
        }

        String peerId = UUID.randomUUID().toString();
        Participant participant = meetings.join(code, peerId, name);

        RoomRegistry.PeerConnection self = new RoomRegistry.PeerConnection(
                peerId, participant.getDisplayName(), code, participant.isHost(), session);

        // Snapshot the room BEFORE adding self, so the newcomer is told exactly
        // who to call and the existing peers just wait for an offer. This is what
        // stops two peers from both offering at once (glare).
        List<Map<String, Object>> existing = registry.peersIn(code).stream()
                .map(p -> Map.<String, Object>of(
                        "peerId", p.peerId(),
                        "displayName", p.displayName(),
                        "host", p.host(),
                        "agent", agents.forPeer(p.peerId()).isPresent()))
                .toList();

        registry.add(self);

        Map<String, Object> welcome = SignalMessage.of(SignalMessage.WELCOME);
        welcome.put("peerId", peerId);
        welcome.put("displayName", participant.getDisplayName());
        welcome.put("host", participant.isHost());
        welcome.put("meetingCode", code);
        welcome.put("meetingTitle", meeting.get().getTitle());
        welcome.put("peers", existing);
        welcome.put("iceServers", iceServers());
        welcome.put("history", meetings.chatHistory(code).stream().map(this::chatDto).toList());
        Ws.send(session, welcome);

        Map<String, Object> joined = SignalMessage.of(SignalMessage.PEER_JOINED);
        joined.put("peerId", peerId);
        joined.put("displayName", participant.getDisplayName());
        joined.put("host", participant.isHost());
        joined.put("agent", false);
        broadcast(code, joined, peerId);

        log.debug("peer {} ({}) joined {} [{} in room]", peerId, name, code, registry.size(code));
    }

    // ------------------------------------------------------------------ messages

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RoomRegistry.PeerConnection self = registry.bySessionId(session.getId()).orElse(null);
        if (self == null) return;

        Map<String, Object> in;
        try {
            in = Ws.parse(message.getPayload());
        } catch (Exception e) {
            Ws.error(session, "Malformed message.");
            return;
        }

        String type = String.valueOf(in.get("type"));

        switch (type) {
            // Pure relay: the server must not inspect or rewrite SDP/ICE.
            case SignalMessage.OFFER, SignalMessage.ANSWER, SignalMessage.ICE ->
                    relay(self, in, type);

            case SignalMessage.CHAT -> {
                String body = String.valueOf(in.getOrDefault("body", "")).trim();
                if (body.isEmpty()) return;
                if (body.length() > 2000) body = body.substring(0, 2000);
                ChatMessage saved = meetings.saveChat(self.meetingCode(), self.displayName(), body);
                Map<String, Object> out = SignalMessage.of(SignalMessage.CHAT);
                out.putAll(chatDto(saved));
                out.put("from", self.peerId());
                broadcast(self.meetingCode(), out, null);
            }

            case SignalMessage.MEDIA_STATE -> {
                Map<String, Object> out = SignalMessage.of(SignalMessage.MEDIA_STATE);
                out.put("from", self.peerId());
                out.put("audio", in.get("audio"));
                out.put("video", in.get("video"));
                out.put("sharing", in.get("sharing"));
                broadcast(self.meetingCode(), out, self.peerId());
            }

            case SignalMessage.CONTROL_REQUEST  -> onControlRequest(self, in);
            case SignalMessage.CONTROL_INPUT    -> onControlInput(self, in);
            case SignalMessage.CONTROL_END      -> onControlEndFromBrowser(self, in);

            default -> Ws.error(session, "Unknown message type: " + type);
        }
    }

    // ------------------------------------------------------------------ remote control

    /**
     * Someone wants to drive another participant's desktop.
     *
     * The server does not decide this. All it does is check that the target has
     * an agent running and hand the request over. The accept or refuse comes from
     * a native dialog on the machine that would be controlled.
     */
    private void onControlRequest(RoomRegistry.PeerConnection self, Map<String, Object> in) {
        String targetPeerId = (String) in.get("to");
        if (targetPeerId == null) return;

        Optional<AgentRegistry.AgentConnection> agent = agents.forPeer(targetPeerId);
        if (agent.isEmpty()) {
            denyToController(self, targetPeerId,
                    "That person does not have the MeetJava desktop agent running.");
            return;
        }
        if (agents.controllerOf(targetPeerId).isPresent()) {
            denyToController(self, targetPeerId,
                    "Someone else is already controlling that desktop.");
            return;
        }

        Map<String, Object> out = SignalMessage.of(SignalMessage.CONTROL_REQUEST);
        out.put("from", self.peerId());
        out.put("fromName", self.displayName());
        Ws.send(agent.get().session(), out);

        log.debug("control requested: {} -> {}", self.displayName(), targetPeerId);
    }

    private void denyToController(RoomRegistry.PeerConnection controller, String hostPeerId, String reason) {
        Map<String, Object> out = SignalMessage.of(SignalMessage.CONTROL_RESPONSE);
        out.put("from", hostPeerId);
        out.put("accepted", false);
        out.put("reason", reason);
        Ws.send(controller.session(), out);
    }

    /**
     * A stream of mouse and key events from the controller.
     *
     * Every single one is checked against the live grant. A controller whose
     * grant was revoked a millisecond ago gets nothing through, and the server
     * never trusts the sender's own claim about who they are.
     */
    private void onControlInput(RoomRegistry.PeerConnection self, Map<String, Object> in) {
        String hostPeerId = (String) in.get("to");
        if (hostPeerId == null) return;
        if (!agents.isAllowed(hostPeerId, self.peerId())) return;

        agents.forPeer(hostPeerId).ifPresent(agent -> {
            Map<String, Object> out = SignalMessage.of(SignalMessage.CONTROL_INPUT);
            out.put("from", self.peerId());
            out.put("event", in.get("event"));
            Ws.send(agent.session(), out);
        });
    }

    /** Either side can end a control session from the browser. */
    private void onControlEndFromBrowser(RoomRegistry.PeerConnection self, Map<String, Object> in) {
        String hostPeerId = (String) in.getOrDefault("to", self.peerId());
        boolean isHost = hostPeerId.equals(self.peerId());
        boolean isController = agents.isAllowed(hostPeerId, self.peerId());
        if (!isHost && !isController) return;

        // Nothing to tear down. Without this the host's stop button reports an
        // ended session, and writes an audit row, every time it is pressed,
        // whether or not anyone was ever controlling the machine.
        if (agents.controllerOf(hostPeerId).isEmpty()) return;

        endControl(self.meetingCode(), hostPeerId,
                isHost ? "revoked by host" : "ended by controller");
    }

    /** Single place that tears a control session down, whoever triggered it. */
    void endControl(String meetingCode, String hostPeerId, String reason) {
        String controllerPeerId = agents.controllerOf(hostPeerId).orElse(null);
        agents.revoke(hostPeerId);
        meetings.closeControlSession(agents.takeAudit(hostPeerId), reason);

        Map<String, Object> out = SignalMessage.of(SignalMessage.CONTROL_END);
        out.put("from", hostPeerId);
        out.put("reason", reason);

        if (controllerPeerId != null) {
            registry.peer(meetingCode, controllerPeerId)
                    .ifPresent(p -> Ws.send(p.session(), out));
        }
        registry.peer(meetingCode, hostPeerId).ifPresent(p -> Ws.send(p.session(), out));
        agents.forPeer(hostPeerId).ifPresent(a -> Ws.send(a.session(), out));

        log.debug("control ended on {} ({})", hostPeerId, reason);
    }

    /** Called by AgentHandler once the machine's owner has answered the dialog. */
    void onAgentDecision(AgentRegistry.AgentConnection agent, String controllerPeerId, boolean accepted) {
        RoomRegistry.PeerConnection controller =
                registry.peer(agent.meetingCode(), controllerPeerId).orElse(null);

        if (accepted) {
            agents.grant(agent.peerId(), controllerPeerId);
            String controllerName = controller != null ? controller.displayName() : "Unknown";
            ControlSession audit = meetings.openControlSession(
                    agent.meetingCode(), agent.displayName(), controllerName);
            agents.rememberAudit(agent.peerId(), audit.getId());
        }

        Map<String, Object> out = SignalMessage.of(SignalMessage.CONTROL_RESPONSE);
        out.put("from", agent.peerId());
        out.put("accepted", accepted);
        out.put("reason", accepted ? "" : "The other person declined.");
        if (controller != null) Ws.send(controller.session(), out);

        // The host's own browser shows a banner too, so it needs to know.
        registry.peer(agent.meetingCode(), agent.peerId())
                .ifPresent(host -> Ws.send(host.session(), out));
    }

    /** Broadcast that a peer's desktop agent came online or went away. */
    void broadcastAgentStatus(String meetingCode, String peerId, boolean available) {
        Map<String, Object> out = SignalMessage.of(SignalMessage.AGENT_STATUS);
        out.put("peerId", peerId);
        out.put("available", available);
        broadcast(meetingCode, out, null);
    }

    // ------------------------------------------------------------------ disconnect

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.removeBySessionId(session.getId()).ifPresent(peer -> {
            // A participant vanishing must not leave a live grant behind.
            agents.controllerOf(peer.peerId())
                    .ifPresent(c -> endControl(peer.meetingCode(), peer.peerId(), "host left"));
            agents.revokeAllInvolving(peer.peerId());

            meetings.leave(peer.peerId());
            Map<String, Object> left = SignalMessage.of(SignalMessage.PEER_LEFT);
            left.put("peerId", peer.peerId());
            left.put("displayName", peer.displayName());
            broadcast(peer.meetingCode(), left, peer.peerId());
            log.debug("peer {} left {} [{} left]", peer.peerId(), peer.meetingCode(),
                    registry.size(peer.meetingCode()));
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Forward a directed message to one peer in the same meeting, tagged with the sender. */
    private void relay(RoomRegistry.PeerConnection self, Map<String, Object> in, String type) {
        String to = (String) in.get("to");
        if (to == null) return;
        registry.peer(self.meetingCode(), to).ifPresent(target -> {
            Map<String, Object> out = new LinkedHashMap<>(in);
            out.put("type", type);
            out.put("from", self.peerId());
            out.put("fromName", self.displayName());
            out.remove("to");
            Ws.send(target.session(), out);
        });
    }

    private void broadcast(String meetingCode, Map<String, Object> payload, String excludePeerId) {
        for (RoomRegistry.PeerConnection peer : registry.peersIn(meetingCode)) {
            if (excludePeerId != null && peer.peerId().equals(excludePeerId)) continue;
            Ws.send(peer.session(), payload);
        }
    }

    private Map<String, Object> chatDto(ChatMessage m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", m.getId());
        dto.put("senderName", m.getSenderName());
        dto.put("body", m.getBody());
        dto.put("sentAt", m.getSentAt().toString());
        return dto;
    }

    /**
     * ICE servers handed to the browser. STUN alone gets two peers connected on
     * most networks; TURN is required when both sides sit behind symmetric NAT,
     * which is why it is configurable rather than hardcoded.
     */
    private List<Map<String, Object>> iceServers() {
        List<Map<String, Object>> servers = new ArrayList<>();
        servers.add(Map.of("urls", stunUrl));
        if (turnUrl != null && !turnUrl.isBlank()) {
            // A comma separated list lets one TURN server be offered over UDP, TCP
            // and TCP/443, which is what gets a call through a restrictive network.
            List<String> urls = Arrays.stream(turnUrl.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            servers.add(Map.of(
                    "urls", urls,
                    "username", turnUsername == null ? "" : turnUsername,
                    "credential", turnCredential == null ? "" : turnCredential));
        }
        return servers;
    }
}

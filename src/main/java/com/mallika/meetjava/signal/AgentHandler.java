package com.mallika.meetjava.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoint for the MeetJava desktop agent.
 *
 * A browser tab cannot move an operating system's cursor, so the machine that
 * is going to be controlled runs a small Java tray app which connects here.
 * The agent pairs itself to a participant who is already in the meeting, then
 * waits. It is the agent, not this server, that asks its user for permission.
 */
@Component
public class AgentHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentHandler.class);

    private final RoomRegistry rooms;
    private final AgentRegistry agents;
    private final SignalingHandler signaling;

    public AgentHandler(RoomRegistry rooms, AgentRegistry agents, SignalingHandler signaling) {
        this.rooms = rooms;
        this.agents = agents;
        this.signaling = signaling;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> params = Ws.queryParams(session.getUri());
        String code = Optional.ofNullable(params.get("code")).orElse("").trim().toLowerCase();
        String name = Optional.ofNullable(params.get("name")).orElse("").trim();

        // Pair to the person already sitting in the meeting under this name. The
        // agent therefore cannot attach itself to a meeting nobody has joined,
        // and cannot impersonate a participant who is not present.
        Optional<RoomRegistry.PeerConnection> target = rooms.peersIn(code).stream()
                .filter(p -> p.displayName().equalsIgnoreCase(name))
                .filter(p -> agents.forPeer(p.peerId()).isEmpty())
                .min(Comparator.comparing(RoomRegistry.PeerConnection::peerId));

        if (target.isEmpty()) {
            Ws.error(session, "No one named \"" + name + "\" is in meeting " + code
                    + ". Join the meeting in your browser first, using exactly this name.");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("no matching participant"));
            return;
        }

        AgentRegistry.AgentConnection agent = new AgentRegistry.AgentConnection(
                target.get().peerId(), code, target.get().displayName(), session);
        agents.bind(agent);

        Map<String, Object> ready = SignalMessage.of(SignalMessage.AGENT_READY);
        ready.put("peerId", agent.peerId());
        ready.put("displayName", agent.displayName());
        ready.put("meetingCode", code);
        Ws.send(session, ready);

        signaling.broadcastAgentStatus(code, agent.peerId(), true);
        log.info("desktop agent bound to {} in meeting {}", agent.displayName(), code);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        AgentRegistry.AgentConnection agent = agents.bySessionId(session.getId()).orElse(null);
        if (agent == null) return;

        Map<String, Object> in;
        try {
            in = Ws.parse(message.getPayload());
        } catch (Exception e) {
            Ws.error(session, "Malformed message.");
            return;
        }

        switch (String.valueOf(in.get("type"))) {
            case SignalMessage.CONTROL_RESPONSE -> {
                String controllerPeerId = (String) in.get("to");
                boolean accepted = Boolean.TRUE.equals(in.get("accepted"));
                if (controllerPeerId == null) return;
                signaling.onAgentDecision(agent, controllerPeerId, accepted);
            }

            // The kill switch. The agent can end control on its own, without
            // asking the server for permission, which is the point.
            case SignalMessage.CONTROL_END ->
                    signaling.endControl(agent.meetingCode(), agent.peerId(), "stopped on the host machine");

            default -> { /* heartbeats and anything else are ignored */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        agents.removeBySessionId(session.getId()).ifPresent(agent -> {
            // Agent gone means the machine can no longer be driven, so any live
            // grant dies with it rather than lingering.
            signaling.endControl(agent.meetingCode(), agent.peerId(), "agent disconnected");
            signaling.broadcastAgentStatus(agent.meetingCode(), agent.peerId(), false);
            log.info("desktop agent for {} disconnected", agent.displayName());
        });
    }
}

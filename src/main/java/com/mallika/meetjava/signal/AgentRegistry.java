package com.mallika.meetjava.signal;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks desktop agents and, separately, who is currently allowed to drive whom.
 *
 * The split matters. An agent being connected means "this machine CAN be
 * controlled". A grant means "this specific person MAY control it, right now".
 * The server can create the first but never the second: a grant only ever comes
 * back from the agent, after the machine's owner clicked accept in a native
 * dialog. That is the whole security model in one sentence.
 */
@Component
public class AgentRegistry {

    /** browser peerId -> the desktop agent bound to that person */
    private final Map<String, AgentConnection> byPeerId = new ConcurrentHashMap<>();
    /** websocket session id -> agent, for fast disconnect handling */
    private final Map<String, AgentConnection> bySessionId = new ConcurrentHashMap<>();
    /** host peerId -> controller peerId. One controller at a time, by design. */
    private final Map<String, String> grants = new ConcurrentHashMap<>();
    /** host peerId -> id of the open ControlSession audit row */
    private final Map<String, Long> auditIds = new ConcurrentHashMap<>();

    public record AgentConnection(String peerId,
                                  String meetingCode,
                                  String displayName,
                                  WebSocketSession session) { }

    public void bind(AgentConnection agent) {
        byPeerId.put(agent.peerId(), agent);
        bySessionId.put(agent.session().getId(), agent);
    }

    public Optional<AgentConnection> forPeer(String peerId) {
        return Optional.ofNullable(byPeerId.get(peerId));
    }

    public Optional<AgentConnection> bySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    public Optional<AgentConnection> removeBySessionId(String sessionId) {
        AgentConnection agent = bySessionId.remove(sessionId);
        if (agent == null) return Optional.empty();
        byPeerId.remove(agent.peerId());
        grants.remove(agent.peerId());
        return Optional.of(agent);
    }

    /** Called only when an agent reports that its user accepted. */
    public void grant(String hostPeerId, String controllerPeerId) {
        grants.put(hostPeerId, controllerPeerId);
    }

    public void revoke(String hostPeerId) {
        grants.remove(hostPeerId);
    }

    public void rememberAudit(String hostPeerId, Long controlSessionId) {
        if (controlSessionId != null) auditIds.put(hostPeerId, controlSessionId);
    }

    /** Returns and clears the audit row id, so a session is only closed once. */
    public Long takeAudit(String hostPeerId) {
        return auditIds.remove(hostPeerId);
    }

    /** Drop any grant involving this peer, whichever side of it they were on. */
    public void revokeAllInvolving(String peerId) {
        grants.remove(peerId);
        grants.entrySet().removeIf(e -> e.getValue().equals(peerId));
    }

    public Optional<String> controllerOf(String hostPeerId) {
        return Optional.ofNullable(grants.get(hostPeerId));
    }

    public boolean isAllowed(String hostPeerId, String controllerPeerId) {
        return controllerPeerId.equals(grants.get(hostPeerId));
    }
}

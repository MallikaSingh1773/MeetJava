package com.mallika.meetjava.signal;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of who is currently connected to which meeting.
 *
 * This is deliberately separate from the JPA layer: the database is the durable
 * record (who joined, when, what was said), while this registry is the live
 * routing table used to fan messages out. When this app is scaled to more than
 * one instance, this class is what gets replaced by Redis pub/sub.
 */
@Component
public class RoomRegistry {

    /** meetingCode -> (peerId -> connection) */
    private final Map<String, Map<String, PeerConnection>> rooms = new ConcurrentHashMap<>();
    /** websocket session id -> peer, so a disconnect can be resolved quickly */
    private final Map<String, PeerConnection> bySessionId = new ConcurrentHashMap<>();

    public record PeerConnection(String peerId,
                                 String displayName,
                                 String meetingCode,
                                 boolean host,
                                 WebSocketSession session) { }

    public void add(PeerConnection peer) {
        rooms.computeIfAbsent(peer.meetingCode(), k -> new ConcurrentHashMap<>())
             .put(peer.peerId(), peer);
        bySessionId.put(peer.session().getId(), peer);
    }

    public Optional<PeerConnection> removeBySessionId(String sessionId) {
        PeerConnection peer = bySessionId.remove(sessionId);
        if (peer == null) return Optional.empty();
        Map<String, PeerConnection> room = rooms.get(peer.meetingCode());
        if (room != null) {
            room.remove(peer.peerId());
            if (room.isEmpty()) rooms.remove(peer.meetingCode());
        }
        return Optional.of(peer);
    }

    public Optional<PeerConnection> bySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    public Optional<PeerConnection> peer(String meetingCode, String peerId) {
        return Optional.ofNullable(rooms.getOrDefault(meetingCode, Map.of()).get(peerId));
    }

    public Collection<PeerConnection> peersIn(String meetingCode) {
        return List.copyOf(rooms.getOrDefault(meetingCode, Map.of()).values());
    }

    public int size(String meetingCode) {
        return rooms.getOrDefault(meetingCode, Map.of()).size();
    }
}

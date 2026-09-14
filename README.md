<div align="center">

# MeetJava — Real-Time Video Meetings with Remote Desktop Control

**Multi-party video and audio, screen sharing, live chat, and consent-based control of a remote desktop**

Spring Boot signaling server · Peer-to-peer WebRTC media · Native Java desktop agent

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![WebRTC](https://img.shields.io/badge/WebRTC-Mesh-333333?style=flat-square&logo=webrtc&logoColor=white)](https://webrtc.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=flat-square&logo=docker&logoColor=white)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-MIT-000000?style=flat-square)](LICENSE)

</div>

---

## About

MeetJava is a real-time video conferencing platform supporting multi-party video and audio,
screen sharing, persistent chat, and consent-based remote desktop control.

The application is written entirely in Java with no JavaScript build tooling. A single Maven
command starts the server, and the browser client is dependency-free JavaScript communicating
with Spring Boot over WebSockets.

## Features

**Meetings**
Human-readable meeting codes in the format `abc-defg-hjk`, generated from an alphabet
excluding visually ambiguous characters so codes can be dictated over a call. Join by code or
invite link, live participant list, automatic host assignment.

**Real-time media**
Multi-party video and audio over WebRTC. Screen sharing applied via
`RTCRtpSender.replaceTrack`, switching sources without renegotiation and without interrupting
existing connections. Microphone, camera and sharing state broadcast to all participants.

**Chat**
Messages persisted to PostgreSQL and replayed in full to participants who join late. Rendered
through `textContent` rather than `innerHTML`, treating all message bodies as untrusted input.

**Remote desktop control**
A participant may request control of another participant's desktop. The request is answered by
a native dialog on the target machine, never by the server. An always-on-top banner remains
visible for the duration of the session, termination is handled locally by the agent before the
server is notified, and every granted session is written to an audit table. See
[AGENT.md](AGENT.md).

**Reliability**
Signaling socket reconnects with exponential backoff without terminating the meeting. ICE
restart is triggered automatically when a peer connection fails. Participants without camera or
microphone access join in view-only mode.

---

## Architecture

```
   Browser A                        Spring Boot                       Browser B
  ───────────                      ─────────────                     ───────────
  Thymeleaf + JS                                                    Thymeleaf + JS
        │                                                                 │
        │◄──── WebSocket /signal ──►  SignalingHandler  ◄── /signal ─────►│
        │                              RoomRegistry                       │
        │                              AgentRegistry                      │
        │                              MeetingService                     │
        │                             JPA / PostgreSQL                    │
        │                                    ▲                            │
        │                                    │ WebSocket /agent           │
        │                             Desktop Agent                       │
        │                          (Swing + java.awt.Robot)               │
        │                                                                 │
        └════════════ WebRTC media, peer to peer, server bypassed ═══════►┘
                          audio · video · screen share
```

Media never traverses the server. Spring Boot functions purely as a signaling and authorization
service, relaying SDP offers, answers and ICE candidates as opaque payloads that it neither
parses nor rewrites. Server resource consumption therefore scales with the number of meetings
rather than the volume of media.

| Component | Responsibility |
|---|---|
| `SignalingHandler` | Participant WebSocket endpoint: presence, SDP and ICE relay, chat fan-out, control authorization |
| `AgentHandler` | Agent WebSocket endpoint: pairing, consent responses, input delivery |
| `RoomRegistry` | In-memory routing table of live participants per meeting |
| `AgentRegistry` | Connected agents and active control grants, tracked as independent concerns |
| `MeetingService` | Transactional logic: code generation, join and leave, chat persistence, control audit |

---

## Technical Decisions

### 1. Eliminating the negotiation race rather than recovering from it

When two peers generate an offer simultaneously, neither can apply the other's remote
description and the negotiation deadlocks. This is conventionally handled with perfect
negotiation, in which one side performs a rollback.

MeetJava removes the possibility instead. On join, the server transmits a snapshot of the room
captured before the new participant was registered. The newcomer offers to each existing peer;
existing peers only answer. Because offers originate from exactly one side of every pair,
simultaneous offers cannot occur and no rollback logic is required.

### 2. Resolution-independent input mapping

Screen-share video is rendered with `object-fit: contain`, so the image is letterboxed within
its element and raw pointer offsets do not correspond to screen positions.

The client computes the true rendered rectangle before transmitting input, then sends
coordinates normalized between 0 and 1. The agent scales them against its own display bounds.
The controlling side therefore requires no knowledge of the host's resolution, DPI scaling or
monitor configuration, and a 900-pixel-wide video accurately drives a 4K desktop.

### 3. Stateless keyboard replay

Each keystroke is replayed as a discrete sequence of press-modifiers, tap-key,
release-modifiers, rather than mirroring keydown and keyup events across the network. A lost
packet cannot leave the controlled machine with a modifier key held indefinitely. `Escape` is
never forwarded and serves as the controlling user's local exit.

### 4. Authorization the server cannot bypass

The server can relay a control request but is structurally incapable of issuing a grant.

| Constraint | Enforced by |
|---|---|
| Grants originate only from the controlled machine | Native dialog in the desktop agent |
| An unanswered request is denied | 30-second dialog timeout, declining by default |
| Every input event is re-authorized | Grant validation on each message in `SignalingHandler` |
| One controller per machine at a time | Grant map in `AgentRegistry` |
| Active control is always visible | Always-on-top banner window |
| Termination does not depend on the network | Input disabled locally before the server is notified |
| No key or button remains held after termination | Explicit release of all modifiers and buttons |
| Agents pair only with present participants | Live participant matching in `AgentHandler` |
| Disconnection invalidates any active grant | Connection close handlers on both endpoints |
| All sessions are recorded | `control_sessions` audit table |

---

## Tech Stack

| Layer | Technologies |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3, Spring WebSocket, Spring Data JPA |
| Persistence | PostgreSQL 16, Hibernate |
| Presentation | Thymeleaf, JavaScript, CSS |
| Real-time media | WebRTC, STUN, TURN |
| Desktop agent | Swing, `java.awt.Robot`, `java.net.http.WebSocket` |
| Build and CI | Maven, Docker, GitHub Actions |
| Testing | JUnit 5, Spring Boot Test |

---

## Getting Started

**Prerequisites:** JDK 21+, Maven 3.9+, PostgreSQL 14+

**1. Create the database and application user**

```bash
psql -U postgres -f setup-database.sql
```

**2. Start the server**

```bash
mvn spring-boot:run
```

The schema is created by Hibernate on first startup. The application is available at
<http://localhost:8080>.

**3. Start the desktop agent** (required only for remote control)

```bash
cd agent
mvn package
java -jar target/meetjava-agent.jar
```

**Running with Docker**

```bash
docker compose up --build
```

Starts the application and PostgreSQL together, with no local database installation required.

**Verifying a call locally**

Open the meeting link in one standard browser window and one incognito window. Separate
browsing contexts are required so that each receives its own camera and microphone permission.

> **HTTPS**
> Browsers grant camera and microphone access only on `localhost` or over TLS. A deployment
> without a valid certificate fails silently at `getUserMedia`. The client selects `wss://`
> automatically when served over HTTPS.

---

## Configuration

All configuration is supplied through environment variables, so a single build artifact runs
unchanged across environments.

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/meetjava` | JDBC connection URL |
| `DB_USER` | `meetjava` | Database user |
| `DB_PASSWORD` | `meetjava` | Database password |
| `DATABASE_URL` | _(empty)_ | Platform supplied `postgresql://` connection string, converted to the three values above at startup |
| `STUN_URL` | Google public STUN | STUN server |
| `TURN_URL` | _(empty)_ | TURN server, required for connections across the internet; accepts a comma separated list |
| `TURN_USERNAME` | _(empty)_ | TURN username |
| `TURN_CREDENTIAL` | _(empty)_ | TURN credential |
| `THYMELEAF_CACHE` | `false` | Template caching, enabled in production |
| `LOG_LEVEL` | `DEBUG` | Application log level |

PostgreSQL is the only database used by the application. H2 is declared at test scope
exclusively, allowing `mvn test` to execute without a database installed. It is never packaged
with the application.

---

## Deployment

The application is packaged as a container and deployed from `render.yaml`, which provisions the
web service and a managed PostgreSQL instance together.

1. **Create the services.** In the Render dashboard, choose **New → Blueprint** and select this
   repository. The blueprint reads `render.yaml` and creates `meetjava` and `meetjava-db`.
2. **Database wiring.** The blueprint injects the database connection string as `DATABASE_URL`.
   `DatabaseUrlEnvironmentPostProcessor` converts the `postgresql://` form into the JDBC URL,
   username and password Spring requires, before the application context starts. No platform
   specific values appear in `application.properties`.
3. **Schema.** Hibernate creates the tables on first boot; `setup-database.sql` is only needed for
   a self managed PostgreSQL instance.
4. **TLS and WebSockets.** Render terminates TLS ahead of the container.
   `server.forward-headers-strategy=framework` makes the application aware of the original scheme,
   so the client opens `wss://` rather than being blocked as mixed content. HTTPS is also a hard
   requirement for `getUserMedia`, which browsers refuse on insecure origins.
5. **Health check.** The platform polls `/actuator/health`; only the health endpoint is exposed.
6. **TURN.** `STUN_URL` alone connects two peers on most home networks. A peer on a mobile or
   corporate network sits behind symmetric NAT and needs a relay, so set `TURN_URL`,
   `TURN_USERNAME` and `TURN_CREDENTIAL` from a TURN provider and redeploy.

Moving to a different PostgreSQL host, or to another container platform, is a change to
`DATABASE_URL` and nothing else.

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/meetings` | Create a meeting |
| `GET` | `/api/meetings/{code}` | Meeting details and live participant count |
| `GET` | `/api/meetings/{code}/chat` | Chat history |
| `GET` | `/api/meetings/{code}/participants` | Join and leave records |
| `GET` | `/api/meetings/{code}/control-sessions` | Remote control audit trail |

---

## Project Structure

```
MeetJava/
├── src/main/java/com/mallika/meetjava/
│   ├── model/          JPA entities
│   ├── repo/           Spring Data repositories
│   ├── service/        Transactional business logic
│   ├── signal/         WebSocket endpoints and live-state registries
│   ├── web/            MVC controllers and REST API
│   └── config/         WebSocket configuration
├── src/main/resources/
│   ├── templates/      Thymeleaf views
│   └── static/         Stylesheet and WebRTC client
├── src/test/           JUnit test suite
├── agent/              Desktop control agent (independent Maven project)
├── .github/workflows/  Continuous integration
├── Dockerfile
├── docker-compose.yml
└── setup-database.sql
```

---

## Testing

```bash
mvn test
```

Covers meeting code format and uniqueness, case-insensitive lookup, host assignment to the
first participant, chat ordering and persistence, and participant leave tracking. Tests execute
against an in-memory database in PostgreSQL compatibility mode and require no external services.

Continuous integration builds both modules and publishes the resulting artifacts on every push.

---

## Roadmap

**Media scalability**
Replace the mesh topology with a selective forwarding unit, introduce simulcast so senders
publish multiple quality layers, and add audio-level-based active speaker detection.

**Platform hardening**
Spring Security with JWT authentication, waiting room and host moderation controls, Flyway
schema migrations, Redis pub/sub for multi-instance presence, and a self-hosted coturn
deployment.

**Agent capabilities**
Multi-monitor coordinate mapping, a system-wide termination hotkey via a native hook, and
short-lived server-issued pairing tokens replacing display-name matching.

Current limitations are documented in [AGENT.md](AGENT.md).

---

## Author

**Mallika Singh**
Java Backend Developer

[GitHub](https://github.com/MallikaSingh1773) · [Email](mailto:singhmallika1773@gmail.com)



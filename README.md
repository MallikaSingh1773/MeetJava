# MeetJava

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![WebRTC](https://img.shields.io/badge/WebRTC-mesh-lightgrey)
![License](https://img.shields.io/badge/license-MIT-black)

A video conferencing platform built as a Java full-stack application: Spring Boot on the
server, WebRTC in the browser. Video, audio, screen sharing, live chat and a roadmap to
consent-based remote desktop control.

No Node, no npm, no React build step. One Maven command runs the whole thing.

---

## What works today

- Create a meeting, get a readable code like `abc-defg-hjk`
- Join by code or invite link
- Multi-party video and audio over WebRTC
- Screen sharing, swapped in without renegotiating the connection
- Live chat, persisted to the database and replayed to anyone who joins late
- Live participant list with host detection
- Mute, camera toggle, and mute/sharing badges visible to everyone
- Automatic reconnection with exponential backoff if the signaling socket drops
- ICE restart when a peer connection fails, so a network change does not end the call
- **Remote desktop control**, consent-based, with a native permission dialog, an
  always-on-top banner, a local kill switch and a full audit trail. See [AGENT.md](AGENT.md)

---

## Running it

**Requirements:** JDK 21 or newer, Maven 3.9 or newer, PostgreSQL 14 or newer.

### First time only: set up the database

1. Install PostgreSQL from <https://www.postgresql.org/download/windows/>. During setup it
   asks for a password for the `postgres` superuser. Write it down. Leave the port at 5432.
2. Open a terminal and run, from the project folder:

   ```bash
   psql -U postgres -f setup-database.sql
   ```

   Enter the superuser password when asked. This creates a `meetjava` database and a
   `meetjava` user, which is what the app connects as.

   If `psql` is not recognised, add PostgreSQL's `bin` folder to your PATH, usually
   `C:\Program Files\PostgreSQL\16\bin`, then reopen the terminal.

3. That is it. Hibernate creates the tables on first start.

To use different credentials, set `DB_URL`, `DB_USER` and `DB_PASSWORD` as environment
variables. Nothing in the code needs editing.

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080>.

For remote desktop control, also run the agent (see [AGENT.md](AGENT.md)):

```bash
cd agent
mvn package
java -jar target/meetjava-agent.jar
```

On Windows you can also just double-click `run.bat`.

To test a real call, open the meeting link in two browser windows, or on two devices on
the same wifi. Use two different browsers (or one normal plus one incognito) so each gets
its own camera permission.

To look at the data:

```bash
psql -U meetjava -d meetjava
\dt                                   -- list tables
SELECT * FROM chat_messages;
SELECT * FROM control_sessions;        -- the remote-control audit trail
```

### One thing to know about HTTPS

Browsers only allow camera and microphone access on `localhost` or over HTTPS. Local
testing works out of the box. The moment you deploy this to a server, you need a real
TLS certificate or `getUserMedia` silently refuses.

---

## Architecture

```
Browser A                    Spring Boot                     Browser B
---------                    -----------                     ---------
  UI (Thymeleaf + JS)                                          UI
        |                                                       |
        |  WebSocket /signal  ->  SignalingHandler  <-  WebSocket
        |                          RoomRegistry                 |
        |                          MeetingService               |
        |                       JPA / PostgreSQL                |
        |                                                       |
        +========== WebRTC media, peer to peer =================+
                   (audio, video, screen share)
```

The key point: **media never touches the server.** Spring Boot carries only the handshake
(SDP offers and answers, ICE candidates), plus chat, presence and permissions. Audio and
video flow browser to browser. That is why a single small server can host many meetings.

### Package layout

| Package   | What lives there |
|-----------|------------------|
| `model`   | `Meeting`, `Participant`, `ChatMessage`, `ControlSession` JPA entities |
| `repo`    | Spring Data repositories |
| `service` | `MeetingService`: code generation, join/leave, chat persistence |
| `signal`  | `SignalingHandler` (the WebSocket endpoint), `RoomRegistry` (live routing table) |
| `web`     | `HomeController` (pages), `MeetingRestController` (JSON API) |
| `agent/`  | Separate Maven project: the desktop control agent (Swing + `java.awt.Robot`) |
| `config`  | WebSocket registration |

### Why a mesh, for now

Every participant holds one `RTCPeerConnection` to every other participant. With N people
each browser uploads N-1 streams. That is fine up to about 4 people and falls apart after.

Phase 2 replaces this with an **SFU** (selective forwarding unit, such as mediasoup or
Janus): each client uploads exactly one stream and the server forwards it to everyone.
The signaling layer here is already written so that swap touches only the client's
connection setup, not the server's message protocol.

### Glare: who calls whom

When two peers both send an offer at the same instant, the negotiation collides. The fix
here is a rule rather than rollback logic: **the newcomer always offers.** On join, the
server hands the newcomer the list of peers already in the room. The newcomer calls each
of them; existing peers simply wait for the offer to arrive.

### Signaling protocol

Every frame is one JSON object with a `type`.

| Type | Direction | Purpose |
|------|-----------|---------|
| `welcome` | server to client | your peer id, existing peers, ICE servers, chat history |
| `peer-joined` / `peer-left` | server to client | presence |
| `offer` / `answer` / `ice` | relayed | WebRTC handshake, forwarded untouched |
| `chat` | both | persisted, then fanned out to the room |
| `media-state` | both | mute, camera off, sharing flags |
| `control-request` / `control-response` / `control-input` / `control-end` | relayed | remote desktop control, gated on a live grant |
| `agent-status` / `agent-ready` | server to client | a participant's desktop agent came online or went away |

SDP and ICE payloads are relayed as opaque blobs. The server never parses or rewrites
them, which is what keeps it out of the media path entirely.

---

## Roadmap

**Phase 2, scale the media**
Replace the mesh with an SFU. Add simulcast so each sender publishes three quality layers
and the SFU picks per receiver based on their bandwidth. Add active speaker detection from
audio levels. Measure and publish the before/after CPU numbers.

**Phase 3, production**
Spring Security with JWT and refresh tokens, waiting room and host controls (mute someone,
remove, promote to co-host), meeting recording, Flyway migrations, Redis pub/sub so presence
and chat work across multiple server instances, and a TURN server (coturn) for peers behind
symmetric NAT.

---

## Configuration

Everything is environment-variable driven, so the same build runs locally and in
production with no code change.

| Variable | Default | What it is |
|----------|---------|------------|
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/meetjava` | JDBC URL |
| `DB_USER` | `meetjava` | database user |
| `DB_PASSWORD` | `meetjava` | database password |
| `STUN_URL` | Google public STUN | STUN server |
| `TURN_URL` | empty | TURN server, required for calls across the internet |
| `TURN_USERNAME` / `TURN_CREDENTIAL` | empty | TURN credentials |

On Railway or Render the managed Postgres gives a `postgres://user:pass@host/db` URL.
Spring needs the JDBC form, so set `DB_URL` to `jdbc:postgresql://host:5432/db` and put the
user and password in their own variables rather than pasting the URL as-is.

### Database notes

PostgreSQL is the only database the application uses. H2 appears in `pom.xml` at **test
scope only**, so `mvn test` runs on a machine with no database installed, which is what a
CI runner needs. It is never packaged with the app and the running app never touches it.

Schema is currently created by Hibernate's `ddl-auto=update`. The next step for this is
Flyway migrations, so schema changes are versioned and reviewable instead of inferred.


## Project structure

```
zoom java/
├── src/main/java/com/mallika/meetjava/
│   ├── model/        Meeting, Participant, ChatMessage, ControlSession
│   ├── repo/         Spring Data repositories
│   ├── service/      MeetingService: codes, join/leave, chat, control audit
│   ├── signal/       SignalingHandler, AgentHandler, RoomRegistry, AgentRegistry
│   ├── web/          HomeController (pages), MeetingRestController (JSON API)
│   └── config/       WebSocket registration
├── src/main/resources/
│   ├── templates/    Thymeleaf pages
│   └── static/       app.css, meeting.js (the WebRTC client)
├── src/test/         JUnit tests
├── agent/            Desktop control agent, its own Maven project
├── Dockerfile
├── docker-compose.yml
├── setup-database.sql
└── .github/workflows/ci.yml
```

## Deployment

The app is a single stateless jar plus a PostgreSQL database, so any platform that
runs a container or a jar will host it.

```bash
docker compose up --build      # app + database together
```

Or build the jar and run it anywhere:

```bash
mvn clean package
java -jar target/meetjava-1.0.0.jar
```

Three things are not optional in production:

1. **HTTPS.** Browsers only allow camera and microphone access on `localhost` or over
   TLS. Without a certificate `getUserMedia` refuses silently and no video ever appears.
   The client already picks `wss://` automatically when the page is served over HTTPS.
2. **A TURN server.** STUN alone cannot connect two peers behind symmetric NAT, which
   is common on mobile data and corporate networks. Set `TURN_URL` and its credentials.
3. **One instance, for now.** `RoomRegistry` and `AgentRegistry` hold live state in
   memory, so two instances would split a meeting across servers. Redis pub/sub is the
   fix and is the next item on the roadmap.

## Tests

```bash
mvn test
```

Covers meeting code format and uniqueness, case-insensitive join, host assignment to the
first participant, chat ordering and persistence, and leave marking.

---

## Tech stack

Java 21, Spring Boot 3.3, Spring WebSocket, Spring Data JPA, Hibernate, PostgreSQL,
Thymeleaf, Maven, WebRTC, vanilla JavaScript, Swing and `java.awt.Robot` for the desktop
agent. Docker and GitHub Actions for build and CI.

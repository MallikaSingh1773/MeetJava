# MeetJava Desktop Agent

Remote desktop control for MeetJava.

A browser tab cannot move an operating system's cursor. So the person who wants to **give**
control runs this small Java tray app. The person **taking** control needs nothing but a
browser tab. Zoom and TeamViewer are built exactly the same way.

---

## Running it

The server must already be running (`mvn spring-boot:run` in the project root).

```bash
cd agent
mvn package
java -jar target/meetjava-agent.jar
```

On Windows, `run-agent.bat` does both steps.

---

## Using it

1. Join the meeting **in your browser first**, and remember the name you used
2. Open the agent, fill in:
   - **Server**: `http://localhost:8080`
   - **Meeting code**: the code from the meeting, like `abc-defg-hjk`
   - **Your name**: exactly the name you joined with
3. Click **Connect**. Status should read `Ready. Paired with "<your name>"`
4. Start sharing your screen in the browser
5. The other person now sees a **Request control** button on your tile
6. When they click it, a permission dialog appears on your machine. Accept it
7. A red banner appears at the top of your screen for as long as control lasts

To stop: click **Stop control** on the banner, or in the agent window, or leave the
meeting. The controller can also press **Escape**.

---

## Testing on one laptop

This works and is the easiest way to demo it.

1. Normal browser window: join as **Mallika**, start screen share
2. Run the agent, connect as **Mallika**
3. Incognito window: join the same meeting as **Bob**
4. As Bob, click **Request control** on Mallika's tile
5. Accept the dialog

Bob's mouse movements now move the real cursor. Because it is one machine you are watching
your own screen inside your own screen, so expect the nested-video effect. It is easier to
see what is happening if you keep a Notepad window open and type into it.

---

## How it works

```
Controller's browser              Spring Boot                 Host machine
--------------------              -----------                 ------------
 click "Request control"  ---->  control-request   ---->  agent shows native dialog
                                                                 |
                                                           user clicks Allow
                                                                 |
 control granted          <----  control-response  <-------------+
                                 (grant recorded,
                                  audit row opened)
 mouse / key events       ---->  control-input     ---->  java.awt.Robot replays them
                                 (checked against
                                  the live grant)
```

### Coordinates

The browser sends **normalised** coordinates, `0.0` to `1.0`, worked out from the actual
drawn video rectangle (screen share is letterboxed by `object-fit: contain`, so the raw
offset would be wrong). The agent multiplies by its own screen size.

This means the controller never needs to know the host's resolution, DPI scaling or monitor
layout, and a 900px wide video can drive a 4K desktop accurately.

### Keyboard

Each keystroke is sent with its modifier flags and replayed as press-modifiers, tap-key,
release-modifiers. Doing it per keystroke rather than tracking keydown/keyup state means a
dropped packet can never leave the remote machine stuck with Ctrl held down.

Escape is never forwarded. It is the controller's own exit key.

---

## Security design

This is software that takes over someone's computer, so the constraints are the design,
not an afterthought.

| Rule | Where it is enforced |
|------|----------------------|
| The server can relay a request but never grant control | `SignalingHandler.onControlRequest` only forwards |
| A grant exists only after a native dialog is accepted | `ConsentDialog`, on the host machine |
| An unanswered dialog declines itself after 30 seconds | `ConsentDialog` timeout |
| Every single input event is re-checked against the live grant | `SignalingHandler.onControlInput` |
| Only one controller per machine at a time | `AgentRegistry` grants map |
| Control is always visible while active | `ControlBanner`, always on top |
| Stop works locally, with no server round trip | `AgentApp.stopControl` cuts input first |
| Stopping releases every held key and mouse button | `InputReplayer.releaseEverything` |
| The agent pairs only with someone already in the meeting | `AgentHandler` matches a live participant |
| A disconnect or a leave kills any live grant | `afterConnectionClosed` on both handlers |
| Every grant is written to an audit trail | `ControlSession` entity |

Audit trail: `GET /api/meetings/{code}/control-sessions`

### Known limitations, stated honestly

- **Stop is not a global hotkey.** The banner button and the agent window both work, but
  Escape only registers when one of them has focus. A true system-wide hotkey needs a
  native hook library such as JNativeHook.
- **Primary monitor only.** Multi-monitor setups need per-display coordinate mapping.
- **No agent authentication yet.** Pairing is by meeting code plus display name. Production
  would issue a short-lived pairing token from the server instead.
- **Screen share must be running** for the controller to see what they are doing.

/*
 * MeetJava client.
 *
 * Topology: full mesh. Every participant holds one RTCPeerConnection to every
 * other participant. That is fine up to about 4 people; beyond that each browser
 * is encoding N-1 separate uploads and the CPU melts. Phase 2 of this project
 * replaces the mesh with an SFU (mediasoup / Janus), at which point each client
 * uploads exactly one stream and the server forwards it.
 *
 * Glare control: the newcomer always sends the offer. The server tells a joining
 * peer who is already in the room; those existing peers simply wait. That single
 * rule removes the need for perfect-negotiation rollback logic.
 */

(() => {
    'use strict';

    const body = document.body;
    const MEETING_CODE = body.dataset.code;
    const DISPLAY_NAME = body.dataset.name || 'Guest';

    // --------------------------------------------------------------- state

    let socket = null;
    let myPeerId = null;
    let iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];

    /** peerId -> { pc, displayName, host, stream } */
    const peers = new Map();

    let localStream = null;     // camera + mic
    let screenStream = null;    // set while sharing
    let micOn = true;
    let camOn = true;
    let sharing = false;

    let reconnectAttempts = 0;
    let deliberateLeave = false;

    // Remote desktop control state.
    let controlling = null;     // peerId of the desktop I am currently driving
    let controlledBy = null;    // name of the person currently driving my desktop
    let lastMoveSent = 0;

    // --------------------------------------------------------------- dom

    const el = {
        grid: document.getElementById('videoGrid'),
        messages: document.getElementById('messages'),
        chatForm: document.getElementById('chatForm'),
        chatInput: document.getElementById('chatInput'),
        people: document.getElementById('peopleList'),
        peopleCount: document.getElementById('peopleCount'),
        status: document.getElementById('connStatus'),
        stage: document.querySelector('.stage'),
        toasts: document.getElementById('toasts'),
        btnMic: document.getElementById('btnMic'),
        btnCam: document.getElementById('btnCam'),
        btnShare: document.getElementById('btnShare'),
        btnPanel: document.getElementById('btnPanel'),
        btnLeave: document.getElementById('btnLeave'),
        copyCode: document.getElementById('copyCode'),
        banner: document.getElementById('controlBanner'),
        bannerText: document.getElementById('controlBannerText'),
        bannerStop: document.getElementById('controlBannerStop')
    };

    // --------------------------------------------------------------- boot

    start();

    async function start() {
        try {
            localStream = await navigator.mediaDevices.getUserMedia({
                video: { width: { ideal: 1280 }, height: { ideal: 720 } },
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
        } catch (err) {
            // A meeting is still useful without a camera, so degrade instead of dying.
            console.warn('getUserMedia failed', err);
            toast('Camera or mic unavailable. Joining in view-only mode.', true);
            localStream = new MediaStream();
            micOn = camOn = false;
            paintMediaButtons();
        }

        addTile(myTileId(), DISPLAY_NAME + ' (you)', localStream, true);
        connect();
        wireControls();
    }

    function myTileId() { return 'self'; }

    // --------------------------------------------------------------- signaling socket

    function connect() {
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        const url = `${scheme}://${location.host}/signal`
            + `?code=${encodeURIComponent(MEETING_CODE)}`
            + `&name=${encodeURIComponent(DISPLAY_NAME)}`;

        setStatus('connecting…', '');
        socket = new WebSocket(url);

        socket.onopen = () => { reconnectAttempts = 0; };
        socket.onmessage = (e) => onSignal(JSON.parse(e.data));
        socket.onclose = () => {
            setStatus('disconnected', 'down');
            if (deliberateLeave) return;
            // Exponential backoff, capped. A dropped socket must not end the meeting.
            reconnectAttempts += 1;
            const delay = Math.min(1000 * 2 ** (reconnectAttempts - 1), 15000);
            toast(`Connection lost. Retrying in ${Math.round(delay / 1000)}s…`, true);
            setTimeout(connect, delay);
        };
        socket.onerror = () => setStatus('connection error', 'down');
    }

    function send(obj) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify(obj));
        }
    }

    async function onSignal(msg) {
        switch (msg.type) {
            case 'welcome':      return onWelcome(msg);
            case 'peer-joined':  return onPeerJoined(msg);
            case 'peer-left':    return onPeerLeft(msg);
            case 'offer':        return onOffer(msg);
            case 'answer':       return onAnswer(msg);
            case 'ice':          return onIce(msg);
            case 'chat':         return onChat(msg);
            case 'media-state':  return onMediaState(msg);
            case 'agent-status': return onAgentStatus(msg);
            case 'control-response': return onControlResponse(msg);
            case 'control-end':  return onControlEnd(msg);
            case 'error':        return toast(msg.message, true);
            default:             console.warn('unhandled signal', msg);
        }
    }

    async function onWelcome(msg) {
        myPeerId = msg.peerId;
        if (Array.isArray(msg.iceServers) && msg.iceServers.length) {
            iceServers = msg.iceServers;
        }
        setStatus('connected', 'live');

        el.messages.innerHTML = '';
        (msg.history || []).forEach(m => renderChat(m.senderName, m.body, m.sentAt, false));

        // We are the newcomer, so we call everyone already here.
        for (const p of msg.peers || []) {
            registerPeer(p.peerId, p.displayName, p.host, p.agent);
            await callPeer(p.peerId);
        }
        refreshPeople();
        systemLine(msg.peers && msg.peers.length
            ? 'You joined the meeting.'
            : 'You are the first one here. Share the meeting code to invite others.');
    }

    function onPeerJoined(msg) {
        registerPeer(msg.peerId, msg.displayName, msg.host, msg.agent);
        refreshPeople();
        systemLine(`${msg.displayName} joined`);
        // Do not offer. They will call us, which avoids both sides offering at once.
    }

    function onPeerLeft(msg) {
        if (controlling === msg.peerId) exitControlMode('they left');
        const peer = peers.get(msg.peerId);
        if (peer) {
            if (peer.pc) peer.pc.close();
            peers.delete(msg.peerId);
        }
        removeTile(msg.peerId);
        refreshPeople();
        systemLine(`${msg.displayName} left`);
    }

    // --------------------------------------------------------------- webrtc

    function registerPeer(peerId, displayName, host, agent) {
        if (!peers.has(peerId)) {
            peers.set(peerId, { pc: null, displayName, host, stream: null, agent: !!agent });
        } else if (agent !== undefined) {
            peers.get(peerId).agent = !!agent;
        }
        addTile(peerId, displayName, null, false);
        setControlButton(peerId, peers.get(peerId).agent);
    }

    function peerConnection(peerId) {
        const peer = peers.get(peerId);
        if (peer.pc) return peer.pc;

        const pc = new RTCPeerConnection({ iceServers });
        peer.pc = pc;

        // Push whatever we are currently sending: camera, or the screen if sharing.
        const outbound = sharing && screenStream ? screenStream : localStream;
        outbound.getTracks().forEach(track => pc.addTrack(track, outbound));
        // Make sure audio always goes out even while screen sharing.
        if (sharing && localStream) {
            localStream.getAudioTracks().forEach(t => pc.addTrack(t, localStream));
        }

        pc.onicecandidate = (e) => {
            if (e.candidate) send({ type: 'ice', to: peerId, candidate: e.candidate });
        };

        pc.ontrack = (e) => {
            const stream = e.streams[0];
            peer.stream = stream;
            attachStream(peerId, stream);
        };

        pc.onconnectionstatechange = () => {
            if (pc.connectionState === 'failed') {
                // ICE restart: usually a network change, not a dead peer.
                console.warn('connection to', peerId, 'failed, restarting ICE');
                callPeer(peerId, true);
            }
        };

        return pc;
    }

    async function callPeer(peerId, iceRestart = false) {
        const pc = peerConnection(peerId);
        const offer = await pc.createOffer({ iceRestart });
        await pc.setLocalDescription(offer);
        send({ type: 'offer', to: peerId, sdp: pc.localDescription });
    }

    async function onOffer(msg) {
        registerPeer(msg.from, msg.fromName || 'Guest', false, undefined);
        const pc = peerConnection(msg.from);
        await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        send({ type: 'answer', to: msg.from, sdp: pc.localDescription });
        refreshPeople();
    }

    async function onAnswer(msg) {
        const peer = peers.get(msg.from);
        if (!peer || !peer.pc) return;
        await peer.pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
    }

    async function onIce(msg) {
        const peer = peers.get(msg.from);
        if (!peer || !peer.pc || !msg.candidate) return;
        try {
            await peer.pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
        } catch (err) {
            // Candidates can arrive before the remote description is set; harmless.
            console.debug('ice candidate ignored', err.message);
        }
    }

    // --------------------------------------------------------------- tiles

    function addTile(id, label, stream, muted) {
        if (document.getElementById('tile-' + id)) {
            if (stream) attachStream(id, stream);
            return;
        }
        const tile = document.createElement('div');
        tile.className = 'tile';
        tile.id = 'tile-' + id;
        tile.innerHTML = `
            <div class="placeholder">${initials(label)}</div>
            <video autoplay playsinline ${muted ? 'muted' : ''}></video>
            <div class="label"></div>
            <div class="badge" hidden></div>
            <button class="ctl-btn" hidden type="button">Request control</button>`;
        tile.querySelector('.label').textContent = label;
        el.grid.appendChild(tile);
        if (stream) attachStream(id, stream);
    }

    function attachStream(id, stream) {
        const tile = document.getElementById('tile-' + id);
        if (!tile) return;
        const video = tile.querySelector('video');
        if (video.srcObject !== stream) video.srcObject = stream;
        const hasVideo = stream.getVideoTracks().some(t => t.enabled && t.readyState === 'live');
        tile.classList.toggle('has-video', hasVideo);
    }

    function removeTile(id) {
        const tile = document.getElementById('tile-' + id);
        if (tile) tile.remove();
    }

    function setTileBadge(id, text) {
        const tile = document.getElementById('tile-' + id);
        if (!tile) return;
        const badge = tile.querySelector('.badge');
        badge.textContent = text || '';
        badge.hidden = !text;
        tile.classList.toggle('screen', text === 'screen');
    }

    function onMediaState(msg) {
        const peer = peers.get(msg.from);
        if (!peer) return;
        const tile = document.getElementById('tile-' + msg.from);
        if (tile) tile.classList.toggle('has-video', msg.video !== false);
        setTileBadge(msg.from, msg.sharing ? 'screen' : (msg.audio === false ? 'muted' : ''));
    }

    // --------------------------------------------------------------- controls

    function wireControls() {
        el.btnMic.onclick = () => {
            micOn = !micOn;
            localStream.getAudioTracks().forEach(t => (t.enabled = micOn));
            paintMediaButtons();
            announceMediaState();
        };

        el.btnCam.onclick = () => {
            camOn = !camOn;
            localStream.getVideoTracks().forEach(t => (t.enabled = camOn));
            const tile = document.getElementById('tile-' + myTileId());
            if (tile) tile.classList.toggle('has-video', camOn);
            paintMediaButtons();
            announceMediaState();
        };

        el.btnShare.onclick = () => (sharing ? stopSharing() : startSharing());

        el.btnPanel.onclick = () => el.stage.classList.toggle('panel-hidden');

        el.btnLeave.onclick = () => leave();

        el.copyCode.onclick = async () => {
            try {
                await navigator.clipboard.writeText(location.origin + '/meeting/' + MEETING_CODE);
                toast('Invite link copied');
            } catch {
                toast('Meeting code: ' + MEETING_CODE);
            }
        };

        el.chatForm.onsubmit = (e) => {
            e.preventDefault();
            const body = el.chatInput.value.trim();
            if (!body) return;
            send({ type: 'chat', body });
            el.chatInput.value = '';
        };

        document.querySelectorAll('.tab').forEach(tab => {
            tab.onclick = () => {
                document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.tabpane').forEach(p => p.classList.remove('active'));
                tab.classList.add('active');
                document.getElementById('pane-' + tab.dataset.tab).classList.add('active');
            };
        });

        el.bannerStop.onclick = () => {
            // Host-side kill switch. The desktop agent has its own, which works
            // even if this tab is frozen, but this one is the obvious button.
            send({ type: 'control-end', to: myPeerId });
            hideBanner();
        };

        window.addEventListener('beforeunload', () => { deliberateLeave = true; });
        paintMediaButtons();
    }

    function paintMediaButtons() {
        el.btnMic.textContent = micOn ? 'Mic on' : 'Mic off';
        el.btnMic.classList.toggle('off', !micOn);
        el.btnCam.textContent = camOn ? 'Camera on' : 'Camera off';
        el.btnCam.classList.toggle('off', !camOn);
        el.btnShare.textContent = sharing ? 'Stop sharing' : 'Share screen';
        el.btnShare.classList.toggle('active', sharing);
    }

    function announceMediaState() {
        send({ type: 'media-state', audio: micOn, video: camOn, sharing });
    }

    // --------------------------------------------------------------- screen share

    async function startSharing() {
        try {
            screenStream = await navigator.mediaDevices.getDisplayMedia({
                video: { frameRate: { ideal: 15, max: 30 } },
                audio: false
            });
        } catch {
            return; // user cancelled the picker
        }

        const screenTrack = screenStream.getVideoTracks()[0];

        // replaceTrack swaps the outgoing video without renegotiating SDP,
        // so the switch is instant and nobody's connection drops.
        for (const [, peer] of peers) {
            if (!peer.pc) continue;
            const sender = peer.pc.getSenders().find(s => s.track && s.track.kind === 'video');
            if (sender) await sender.replaceTrack(screenTrack);
        }

        const tile = document.getElementById('tile-' + myTileId());
        if (tile) {
            tile.querySelector('video').srcObject = screenStream;
            tile.classList.add('has-video');
        }
        setTileBadge(myTileId(), 'screen');

        // Fires when the user hits the browser's own "Stop sharing" bar.
        screenTrack.onended = () => stopSharing();

        sharing = true;
        paintMediaButtons();
        announceMediaState();
        systemLine('You started sharing your screen.');
    }

    async function stopSharing() {
        if (screenStream) {
            screenStream.getTracks().forEach(t => t.stop());
            screenStream = null;
        }
        const camTrack = localStream.getVideoTracks()[0] || null;
        for (const [, peer] of peers) {
            if (!peer.pc) continue;
            const sender = peer.pc.getSenders().find(s => s.track && s.track.kind === 'video');
            if (sender && camTrack) await sender.replaceTrack(camTrack);
        }
        const tile = document.getElementById('tile-' + myTileId());
        if (tile) {
            tile.querySelector('video').srcObject = localStream;
            tile.classList.toggle('has-video', camOn);
        }
        setTileBadge(myTileId(), '');
        sharing = false;
        paintMediaButtons();
        announceMediaState();
    }

    // --------------------------------------------------------------- chat + people

    function onChat(msg) {
        const mine = msg.from === myPeerId;
        renderChat(msg.senderName, msg.body, msg.sentAt, mine);
    }

    function renderChat(who, body, sentAt, mine) {
        const wrap = document.createElement('div');
        wrap.className = 'msg' + (mine ? ' self' : '');
        const time = sentAt ? new Date(sentAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
        const head = document.createElement('div');
        head.className = 'who';
        head.textContent = `${who} · ${time}`;
        const text = document.createElement('div');
        text.className = 'body';
        text.textContent = body;              // textContent, never innerHTML: chat is untrusted input
        wrap.append(head, text);
        el.messages.appendChild(wrap);
        el.messages.scrollTop = el.messages.scrollHeight;
    }

    function systemLine(text) {
        const div = document.createElement('div');
        div.className = 'msg system';
        div.textContent = text;
        el.messages.appendChild(div);
        el.messages.scrollTop = el.messages.scrollHeight;
    }

    function refreshPeople() {
        el.people.innerHTML = '';
        const rows = [{ displayName: DISPLAY_NAME + ' (you)', host: false }]
            .concat([...peers.values()].map(p => ({ displayName: p.displayName, host: p.host })));
        for (const r of rows) {
            const li = document.createElement('li');
            const av = document.createElement('span');
            av.className = 'avatar';
            av.textContent = initials(r.displayName);
            const nm = document.createElement('span');
            nm.textContent = r.displayName;
            li.append(av, nm);
            if (r.host) {
                const tag = document.createElement('span');
                tag.className = 'tag';
                tag.textContent = 'host';
                li.append(tag);
            }
            el.people.appendChild(li);
        }
        el.peopleCount.textContent = String(rows.length);
    }


    // --------------------------------------------------------------- remote desktop control

    /*
     * A browser tab cannot move another machine's cursor, so the person GIVING
     * control runs the MeetJava desktop agent. This side only captures pointer
     * and key events over their shared screen and ships them as NORMALISED
     * coordinates (0..1). The agent multiplies by its own screen size, which is
     * what makes a 2560x1440 desktop drivable from a 900px wide video without
     * either side knowing the other's resolution or DPI.
     */

    function setControlButton(peerId, available) {
        const tile = document.getElementById('tile-' + peerId);
        if (!tile) return;
        const btn = tile.querySelector('.ctl-btn');
        if (!btn) return;
        btn.hidden = !available;
        btn.onclick = () => {
            if (controlling === peerId) {
                send({ type: 'control-end', to: peerId });
                exitControlMode('you stopped');
            } else {
                send({ type: 'control-request', to: peerId });
                btn.textContent = 'Waiting…';
                btn.disabled = true;
                toast('Control requested. Waiting for them to accept.');
            }
        };
    }

    function onAgentStatus(msg) {
        const peer = peers.get(msg.peerId);
        if (peer) peer.agent = msg.available;
        setControlButton(msg.peerId, msg.available);
        if (!msg.available && controlling === msg.peerId) exitControlMode('their agent went offline');
    }

    function onControlResponse(msg) {
        // Arrives at the controller, and also at the host so their own tab can
        // show the banner.
        if (msg.from === myPeerId) {
            if (msg.accepted) showBanner('Someone is controlling your desktop.');
            return;
        }
        const tile = document.getElementById('tile-' + msg.from);
        const btn = tile && tile.querySelector('.ctl-btn');
        if (btn) { btn.disabled = false; btn.textContent = 'Request control'; }

        if (msg.accepted) {
            enterControlMode(msg.from);
        } else {
            toast(msg.reason || 'Control request declined.', true);
        }
    }

    function onControlEnd(msg) {
        if (msg.from === myPeerId) {
            hideBanner();
            systemLine('Remote control ended (' + (msg.reason || 'ended') + ').');
            return;
        }
        if (controlling === msg.from) exitControlMode(msg.reason || 'ended');
    }

    function enterControlMode(peerId) {
        controlling = peerId;
        const tile = document.getElementById('tile-' + peerId);
        if (!tile) return;
        tile.classList.add('controlling');
        const btn = tile.querySelector('.ctl-btn');
        if (btn) btn.textContent = 'Stop controlling';

        const video = tile.querySelector('video');
        video.addEventListener('mousemove', onCtlMove);
        video.addEventListener('mousedown', onCtlDown);
        video.addEventListener('mouseup', onCtlUp);
        video.addEventListener('wheel', onCtlWheel, { passive: false });
        video.addEventListener('contextmenu', preventDefault);
        window.addEventListener('keydown', onCtlKey, true);

        const peer = peers.get(peerId);
        systemLine('You are now controlling ' + (peer ? peer.displayName : 'their') + ' desktop. Press Escape to stop.');
        toast('Control granted. Click on the video to drive their desktop.');
    }

    function exitControlMode(reason) {
        const peerId = controlling;
        controlling = null;
        if (!peerId) return;
        const tile = document.getElementById('tile-' + peerId);
        if (tile) {
            tile.classList.remove('controlling');
            const btn = tile.querySelector('.ctl-btn');
            if (btn) { btn.textContent = 'Request control'; btn.disabled = false; }
            const video = tile.querySelector('video');
            video.removeEventListener('mousemove', onCtlMove);
            video.removeEventListener('mousedown', onCtlDown);
            video.removeEventListener('mouseup', onCtlUp);
            video.removeEventListener('wheel', onCtlWheel);
            video.removeEventListener('contextmenu', preventDefault);
        }
        window.removeEventListener('keydown', onCtlKey, true);
        systemLine('Remote control stopped (' + reason + ').');
    }

    function preventDefault(e) { e.preventDefault(); }

    /*
     * Screen-share video is rendered with object-fit: contain, so the picture is
     * letterboxed inside the element. Mapping raw offsetX/offsetY would land the
     * cursor in the wrong place; this works out the real drawn rectangle first.
     */
    function normalisedPoint(video, e) {
        const rect = video.getBoundingClientRect();
        const vw = video.videoWidth || rect.width;
        const vh = video.videoHeight || rect.height;
        const scale = Math.min(rect.width / vw, rect.height / vh);
        const drawnW = vw * scale;
        const drawnH = vh * scale;
        const offsetX = (rect.width - drawnW) / 2;
        const offsetY = (rect.height - drawnH) / 2;

        const x = (e.clientX - rect.left - offsetX) / drawnW;
        const y = (e.clientY - rect.top - offsetY) / drawnH;
        if (x < 0 || x > 1 || y < 0 || y > 1) return null;   // in the letterbox, ignore
        return { x: +x.toFixed(4), y: +y.toFixed(4) };
    }

    function sendInput(event) {
        if (!controlling) return;
        send({ type: 'control-input', to: controlling, event });
    }

    function onCtlMove(e) {
        // ~30 moves a second is smooth and keeps the socket quiet.
        const now = performance.now();
        if (now - lastMoveSent < 33) return;
        lastMoveSent = now;
        const p = normalisedPoint(e.currentTarget, e);
        if (p) sendInput({ kind: 'move', x: p.x, y: p.y });
    }

    function onCtlDown(e) {
        e.preventDefault();
        const p = normalisedPoint(e.currentTarget, e);
        if (p) sendInput({ kind: 'down', x: p.x, y: p.y, button: e.button });
    }

    function onCtlUp(e) {
        e.preventDefault();
        const p = normalisedPoint(e.currentTarget, e);
        if (p) sendInput({ kind: 'up', x: p.x, y: p.y, button: e.button });
    }

    function onCtlWheel(e) {
        e.preventDefault();
        const p = normalisedPoint(e.currentTarget, e);
        if (p) sendInput({ kind: 'wheel', x: p.x, y: p.y, dy: Math.sign(e.deltaY) });
    }

    function onCtlKey(e) {
        if (!controlling) return;
        // Escape is the controller-side exit hatch and is never forwarded.
        if (e.key === 'Escape') {
            e.preventDefault();
            send({ type: 'control-end', to: controlling });
            exitControlMode('you pressed Escape');
            return;
        }
        // Do not hijack typing in the chat box.
        if (document.activeElement === el.chatInput) return;
        e.preventDefault();
        sendInput({
            kind: 'key',
            key: e.key,
            code: e.code,
            ctrl: e.ctrlKey, alt: e.altKey, shift: e.shiftKey, meta: e.metaKey
        });
    }

    function showBanner(text) {
        controlledBy = text;
        el.banner.hidden = false;
        el.bannerText.textContent = text;
    }

    function hideBanner() {
        controlledBy = null;
        el.banner.hidden = true;
    }

    // --------------------------------------------------------------- leave + misc

    function leave() {
        deliberateLeave = true;
        for (const [, peer] of peers) if (peer.pc) peer.pc.close();
        if (localStream) localStream.getTracks().forEach(t => t.stop());
        if (screenStream) screenStream.getTracks().forEach(t => t.stop());
        if (socket) socket.close();
        location.href = '/';
    }

    function setStatus(text, cls) {
        el.status.textContent = text;
        el.status.className = 'status ' + cls;
    }

    function toast(text, isError) {
        const t = document.createElement('div');
        t.className = 'toast' + (isError ? ' err' : '');
        t.textContent = text;
        el.toasts.appendChild(t);
        setTimeout(() => t.remove(), 5000);
    }

    function initials(name) {
        return (name || '?')
            .replace(/\(you\)/i, '')
            .trim()
            .split(/\s+/)
            .slice(0, 2)
            .map(w => w[0])
            .join('')
            .toUpperCase();
    }
})();

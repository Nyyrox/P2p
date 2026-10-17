import { useEffect, useRef, useState } from "react";

const SIGNAL = import.meta.env.VITE_SIGNAL as string;
const ICE: RTCIceServer[] = import.meta.env.VITE_ICE
  ? JSON.parse(import.meta.env.VITE_ICE)
  : [{ urls: "stun:stun.l.google.com:19302" }];
const CHUNK = 16 * 1024;
const uid = () => Math.random().toString(36).slice(2);

export type Msg = {
  id: string; me: boolean; text?: string;
  file?: { name: string; type: string; size: number; url: string };
  pct?: number; // receive progress, undefined when done
};
export type Call = { state: "idle" | "ringing" | "incoming" | "active"; video: boolean };
export type Status = "waiting" | "connecting" | "connected" | "full";
const IDLE: Call = { state: "idle", video: false };

export function useRoom(code: string, name: string) {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [status, setStatus] = useState<Status>("waiting");
  const [peer, setPeer] = useState("");
  const [call, setCall] = useState<Call>(IDLE);
  const [local, setLocal] = useState<MediaStream | null>(null);
  const [remote, setRemote] = useState<MediaStream | null>(null);
  const api = useRef({
    send: (_t: string) => {}, file: (_f: File) => {},
    ring: (_video: boolean) => {}, accept: () => {}, end: () => {},
  });

  useEffect(() => {
    let ws: WebSocket, pc: RTCPeerConnection | undefined, dc: RTCDataChannel | undefined;
    let polite = true, making = false, ignore = false, opened = false, closed = false;
    let stream: MediaStream | undefined, cs = IDLE;
    let rx: { id: string; size: number; type: string; parts: ArrayBuffer[]; got: number; pct: number } | undefined;
    let queue: Promise<unknown> = Promise.resolve();

    const wsend = (o: object) => ws.readyState === 1 && ws.send(JSON.stringify(o));
    const dsend = (o: object) => dc?.readyState === "open" && dc.send(JSON.stringify(o));
    const add = (m: Msg) => setMsgs(x => [...x, m]);
    const patch = (id: string, p: Partial<Msg>) => setMsgs(x => x.map(m => (m.id === id ? { ...m, ...p } : m)));
    const setC = (c: Call) => { cs = c; setCall(c); };

    const stopMedia = () => {
      if (pc && pc.signalingState !== "closed") pc.getSenders().forEach(s => s.track && pc!.removeTrack(s));
      stream?.getTracks().forEach(t => t.stop());
      stream = undefined; setLocal(null);
    };
    const endCall = () => { stopMedia(); setRemote(null); setC(IDLE); };
    const media = async (video: boolean) => {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: video && { facingMode: "user" } });
      stream.getTracks().forEach(t => pc!.addTrack(t, stream!));
      setLocal(stream);
    };

    const newPeer = () => {
      endCall(); pc?.close(); making = ignore = false;
      const p = (pc = new RTCPeerConnection({ iceServers: ICE }));
      p.onicecandidate = e => e.candidate && wsend({ c: e.candidate });
      p.onnegotiationneeded = async () => {
        try { making = true; await p.setLocalDescription(); wsend({ d: p.localDescription }); } finally { making = false; }
      };
      p.ontrack = e => setRemote(e.streams[0]);
      p.ondatachannel = e => setupDc(e.channel);
      p.onconnectionstatechange = () => p.connectionState === "failed" && p.restartIce();
    };

    const setupDc = (c: RTCDataChannel) => {
      dc = c; c.binaryType = "arraybuffer"; c.bufferedAmountLowThreshold = 1 << 20;
      c.onopen = () => { dsend({ t: "hi", name }); setStatus("connected"); };
      c.onmessage = e => (typeof e.data === "string" ? ctl(JSON.parse(e.data)) : chunk(e.data));
    };

    const ctl = (m: any) => {
      switch (m.t) {
        case "hi": setPeer(m.name); break;
        case "msg": add({ id: uid(), me: false, text: m.text }); break;
        case "file":
          add({ id: m.id, me: false, file: { name: m.name, type: m.type, size: m.size, url: "" }, pct: 0 });
          rx = { id: m.id, size: m.size, type: m.type, parts: [], got: 0, pct: 0 };
          if (!m.size) chunk(new ArrayBuffer(0));
          break;
        case "ring": cs.state === "idle" ? setC({ state: "incoming", video: m.video }) : dsend({ t: "end" }); break;
        case "accept": media(cs.video).then(() => setC({ ...cs, state: "active" })).catch(end); break;
        case "end": endCall(); break;
      }
    };

    const chunk = (b: ArrayBuffer) => {
      if (!rx) return;
      rx.parts.push(b); rx.got += b.byteLength;
      if (rx.got >= rx.size) {
        const f = rx; rx = undefined;
        setMsgs(x => x.map(m => m.id === f.id
          ? { ...m, pct: undefined, file: { ...m.file!, url: URL.createObjectURL(new Blob(f.parts, { type: f.type })) } } : m));
      } else {
        const pct = Math.floor((rx.got / rx.size) * 100);
        if (pct !== rx.pct) { rx.pct = pct; patch(rx.id, { pct }); }
      }
    };

    const end = () => { dsend({ t: "end" }); endCall(); };

    api.current = {
      send: text => { add({ id: uid(), me: true, text }); dsend({ t: "msg", text }); },
      file: f => {
        queue = queue.then(async () => {
          if (dc?.readyState !== "open") return;
          const id = uid();
          add({ id, me: true, file: { name: f.name, type: f.type, size: f.size, url: URL.createObjectURL(f) } });
          dsend({ t: "file", id, name: f.name, type: f.type, size: f.size });
          for (let o = 0; o < f.size; o += CHUNK) {
            if (dc.bufferedAmount > 1 << 20) await new Promise(r => dc!.addEventListener("bufferedamountlow", r, { once: true }));
            dc.send(await f.slice(o, o + CHUNK).arrayBuffer());
          }
        });
      },
      ring: video => { setC({ state: "ringing", video }); dsend({ t: "ring", video }); },
      accept: () => media(cs.video).then(() => { setC({ ...cs, state: "active" }); dsend({ t: "accept" }); }).catch(end),
      end,
    };

    ws = new WebSocket(`${SIGNAL}/${code}`);
    ws.onopen = () => (opened = true);
    ws.onclose = () => { if (!opened && !closed) setStatus("full"); };
    ws.onmessage = async ({ data }) => {
      const m = JSON.parse(data);
      if (m.t === "ready") {
        polite = false; newPeer(); setupDc(pc!.createDataChannel("d")); setStatus("connecting");
      } else if (m.t === "left") {
        polite = true; newPeer(); setPeer(""); setStatus("waiting");
      } else if (m.d) {
        if (!pc) newPeer();
        const p = pc!;
        ignore = !polite && m.d.type === "offer" && (making || p.signalingState !== "stable");
        if (ignore) return;
        setStatus(s => (s === "connected" ? s : "connecting"));
        await p.setRemoteDescription(m.d);
        if (m.d.type === "offer") { await p.setLocalDescription(); wsend({ d: p.localDescription }); }
      } else if (m.c) {
        try { await pc?.addIceCandidate(m.c); } catch (e) { if (!ignore) throw e; }
      }
    };

    return () => { closed = true; ws.close(); stream?.getTracks().forEach(t => t.stop()); pc?.close(); };
  }, [code, name]);

  return { msgs, status, peer, call, local, remote, api };
}

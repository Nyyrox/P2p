import { useEffect, useRef, useState, type VideoHTMLAttributes } from "react";
import { useRoom, type Msg } from "./rtc";

const ALPHA = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const newCode = () => Array.from(crypto.getRandomValues(new Uint8Array(6)), b => ALPHA[b % 31]).join("");

const P = {
  send: "M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z",
  phone: "M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z",
  video: "m22 8-6 4 6 4V8zM2 8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z",
  clip: "m21.4 11.1-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5",
  back: "m15 18-6-6 6-6",
  mic: "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3zM19 10v2a7 7 0 0 1-14 0v-2M12 19v3",
  off: "m2 2 20 20",
  file: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6",
  share: "M4 12v8h16v-8M16 6l-4-4-4 4M12 2v13",
};
const Ic = ({ d, off, rot, size = 22 }: { d: keyof typeof P; off?: boolean; rot?: number; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
    strokeLinecap="round" strokeLinejoin="round" style={rot ? { transform: `rotate(${rot}deg)` } : undefined}>
    <path d={P[d]} />{off && <path d={P.off} />}
  </svg>
);

function Vid({ s, ...p }: { s: MediaStream | null } & VideoHTMLAttributes<HTMLVideoElement>) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => { if (ref.current) { ref.current.srcObject = s; ref.current.play().catch(() => {}); } }, [s]);
  return <video ref={ref} autoPlay playsInline {...p} />;
}

export default function App() {
  const [name, setName] = useState(localStorage.getItem("name") || "");
  const [code, setCode] = useState("");
  const [mine, setMine] = useState(false);
  const [join, setJoin] = useState("");
  const n = name.trim();
  const go = (c: string, creator: boolean) => { localStorage.setItem("name", n); setMine(creator); setCode(c); };

  if (code) return <Room code={code} name={n} mine={mine} leave={() => setCode("")} />;
  return (
    <main className="home">
      <div className="logo">P</div>
      <h1>PeerTalk</h1>
      <p className="sub">Private chat, calls and files. Direct between you and a friend.</p>
      <div className="card">
        <label>Your name</label>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Alex" maxLength={24} />
        <button className="pri" disabled={!n} onClick={() => go(newCode(), true)}>Create room</button>
        <div className="or">or join with a code</div>
        <div className="row">
          <input className="code" value={join} maxLength={6} placeholder="ABC123"
            onChange={e => setJoin(e.target.value.toUpperCase())} />
          <button className="sec" disabled={!n || join.length < 6} onClick={() => go(join, false)}>Join</button>
        </div>
      </div>
    </main>
  );
}

function Room({ code, name, mine, leave }: { code: string; name: string; mine: boolean; leave: () => void }) {
  const r = useRoom(code, name);
  const A = r.api;
  const [text, setText] = useState("");
  const bottom = useRef<HTMLDivElement>(null);
  const pick = useRef<HTMLInputElement>(null);
  useEffect(() => bottom.current?.scrollIntoView(), [r.msgs]);

  const on = r.status === "connected";
  const sub = { waiting: mine ? "Waiting for your friend…" : "Waiting…", connecting: "Connecting…",
    connected: "Online", full: "Room is full" }[r.status];
  const share = () => navigator.share
    ? navigator.share({ text: `Join me on PeerTalk with code ${code}` }).catch(() => {})
    : navigator.clipboard.writeText(code);
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (text.trim() && on) { A.current.send(text.trim()); setText(""); }
  };

  return (
    <div className="room">
      <header>
        <button className="ic" onClick={leave}><Ic d="back" /></button>
        <div className="av">{(r.peer || "?")[0].toUpperCase()}</div>
        <div className="who"><b>{r.peer || "PeerTalk"}</b><span className={on ? "ok" : ""}>{sub} · {code}</span></div>
        <button className="ic" disabled={!on} onClick={() => A.current.ring(false)}><Ic d="phone" /></button>
        <button className="ic" disabled={!on} onClick={() => A.current.ring(true)}><Ic d="video" /></button>
      </header>

      <div className="msgs">
        {r.status === "full" && <div className="hint">This room already has two people.</div>}
        {!on && r.status !== "full" && mine && (
          <div className="hint big">
            Share this code with your friend
            <div className="bigcode">{code}</div>
            <button className="sec" onClick={share}><Ic d="share" size={16} /> Share</button>
          </div>
        )}
        {r.msgs.map(m => <Bubble key={m.id} m={m} />)}
        <div ref={bottom} />
      </div>

      <form className="bar" onSubmit={submit}>
        <input ref={pick} type="file" hidden multiple onChange={e => {
          [...(e.target.files || [])].forEach(f => A.current.file(f)); e.target.value = "";
        }} />
        <button type="button" className="ic" disabled={!on} onClick={() => pick.current?.click()}><Ic d="clip" /></button>
        <input value={text} onChange={e => setText(e.target.value)} placeholder={on ? "Message" : "Waiting for connection…"} />
        <button className="send" disabled={!on || !text.trim()}><Ic d="send" size={20} /></button>
      </form>

      {r.call.state !== "idle" && <CallView r={r} />}
    </div>
  );
}

function Bubble({ m }: { m: Msg }) {
  const f = m.file;
  const kind = f?.type.split("/")[0];
  return (
    <div className={"b " + (m.me ? "me" : "them")}>
      {m.text}
      {f && (
        f.url && kind === "image" ? <img src={f.url} /> :
        f.url && kind === "video" ? <video src={f.url} controls playsInline /> :
        f.url && kind === "audio" ? <audio src={f.url} controls /> :
        <a className="file" href={f.url || undefined} download={f.name}>
          <Ic d="file" size={20} /><span>{f.name}<small>{(f.size / 1024).toFixed(0)} KB</small></span>
        </a>
      )}
      {m.pct !== undefined && <div className="prog"><i style={{ width: m.pct + "%" }} /></div>}
    </div>
  );
}

function CallView({ r }: { r: ReturnType<typeof useRoom> }) {
  const { call, local, remote, peer, api: A } = r;
  const [mute, setMute] = useState(false);
  const [cam, setCam] = useState(false);
  const flip = (kind: string, off: boolean) =>
    local?.getTracks().filter(t => t.kind === kind).forEach(t => (t.enabled = !off));
  const title = { ringing: "Calling…", incoming: `${peer || "Friend"} is calling`, active: peer, idle: "" }[call.state];

  return (
    <div className="call">
      <Vid s={remote} className="remote" style={{ opacity: call.state === "active" && call.video ? 1 : 0 }} />
      {!(call.state === "active" && call.video) && <div className="av xl">{(peer || "?")[0].toUpperCase()}</div>}
      <div className="ct"><b>{title}</b><span>{call.video ? "Video call" : "Voice call"}</span></div>
      {call.video && local && <Vid s={local} muted className="pip" />}
      <div className="ctl">
        {call.state === "active" && <>
          <button className={"rb" + (mute ? " on" : "")} onClick={() => { flip("audio", !mute); setMute(!mute); }}><Ic d="mic" off={mute} /></button>
          {call.video && <button className={"rb" + (cam ? " on" : "")} onClick={() => { flip("video", !cam); setCam(!cam); }}><Ic d="video" off={cam} /></button>}
        </>}
        {call.state === "incoming" && <button className="rb go" onClick={() => A.current.accept()}><Ic d="phone" /></button>}
        <button className="rb stop" onClick={() => A.current.end()}><Ic d="phone" rot={135} /></button>
      </div>
    </div>
  );
}

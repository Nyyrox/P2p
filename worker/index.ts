// Tiny relay: one Durable Object per room code, max 2 sockets, forwards messages to the other side.
export class Room {
  constructor(private s: any) {}
  async fetch() {
    const peers = this.s.getWebSockets();
    if (peers.length >= 2) return new Response("full", { status: 409 });
    const [client, server] = Object.values(new (globalThis as any).WebSocketPair()) as any[];
    this.s.acceptWebSocket(server);
    if (peers.length) peers[0].send('{"t":"ready"}'); // whoever was waiting makes the offer
    return new Response(null, { status: 101, webSocket: client } as any);
  }
  webSocketMessage(ws: any, m: string) { this.#others(ws, m); }
  webSocketClose(ws: any) { this.#others(ws, '{"t":"left"}'); }
  #others(ws: any, m: string) {
    for (const p of this.s.getWebSockets()) if (p !== ws) try { p.send(m); } catch {}
  }
}
export default {
  fetch(req: Request, env: any) {
    if (req.headers.get("Upgrade") !== "websocket") return new Response("PeerTalk signal");
    const code = new URL(req.url).pathname.slice(1).toUpperCase();
    return env.ROOM.get(env.ROOM.idFromName(code)).fetch(req);
  },
};

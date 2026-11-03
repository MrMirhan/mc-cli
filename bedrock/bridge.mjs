// mccli bedrock bridge: shows a Bedrock server (RakNet: Geyser or a dedicated server) to the Bedrock
// clients of this network as a LAN world, and relays each client's game to it.
//
// The Bedrock client for Linux (mcpelauncher, 1.26) cannot join a server by address without a
// Microsoft sign-in, and its network layer fails on direct connections (mcpelauncher-manifest#2074).
// It does join a LAN world over NetherNet. So the bridge advertises one on UDP 7551, accepts the
// client over NetherNet (WebRTC), and relays to the server over RakNet.
//
// The client's side is always offline. The server's side is signed in with a Microsoft (Xbox) account
// when the token folder holds a sign-in (`mccli bedrock login`), so the client reaches online servers as
// that account; otherwise, or with --offline, it is offline (the server must allow offline players:
// online-mode=false, or Geyser's auth-type offline / Floodgate). --auth offline|microsoft chooses explicitly.
//
// The bridge must run on another host or container than the client, on the network of the client's
// default route: the client broadcasts its LAN discovery there only, and a bridge on the client's
// own address gets no game traffic.
//
// It also serves what it sees of each client's game (position, health, inventory, chat, forms, the tab
// list) as JSON on a control port, loopback by default; another address needs a token in
// MCCLI_BEDROCK_BRIDGE_TOKEN.
//
// Usage: node bridge.mjs --target host:port [--name "Test server"] [--version 1.26.60] [--offline]
//          [--auth auto|offline|microsoft] [--auth-dir DIR] [--account NAME] [--control host:port|off]
//        node bridge.mjs --login [--auth-dir DIR] [--account NAME]   signs in once (a code for microsoft.com/link)
import { createRequire } from 'node:module';
import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { AUTH_OPTIONS, cacheFor, defaultDir, login, status } from './auth.mjs';
import { BridgeState } from './state.mjs';

const require = createRequire(import.meta.url);

const print = (o) => console.log(JSON.stringify(o));

export function parseArgs(argv) {
  const arg = (name, fallback) => {
    const i = argv.indexOf(`--${name}`);
    return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
  };
  const target = arg('target');
  if (!target || !/^[^:\s]+:\d+$/.test(target)) throw new Error('--target host:port is required');
  const auth = argv.includes('--offline') ? 'offline' : arg('auth', 'auto');
  if (!['auto', 'offline', 'microsoft'].includes(auth)) throw new Error('--auth is auto, offline or microsoft');
  const control = arg('control', '127.0.0.1:19150');
  if (control !== 'off' && !/^[^:\s]+:\d+$/.test(control)) throw new Error('--control is host:port or off');
  const [host, port] = target.split(':');
  return {
    host,
    port: Number(port),
    name: arg('name', 'mccli test server'),
    version: arg('version', '1.26.60'),
    auth,
    authDir: arg('auth-dir', defaultDir()),
    account: arg('account', 'default'),
    control: control === 'off' ? null : { host: control.split(':')[0], port: Number(control.split(':')[1]) }
  };
}

function isLoopback(host) {
  return host === 'localhost' || host === '::1' || /^127\./.test(host);
}

/** The control API: GET /status, /state, /chat, /forms; POST /form answers a form for the client. */
export function controlServer(bridge, token) {
  const send = (res, code, body) => {
    res.writeHead(code, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  const authorized = (req) => {
    if (!token) return true;
    const given = Buffer.from(String(req.headers.authorization ?? '').replace(/^Bearer\s+/i, ''));
    const want = Buffer.from(token);
    return given.length === want.length && timingSafeEqual(given, want);
  };
  return http.createServer((req, res) => {
    if (!authorized(req)) return send(res, 401, { error: 'unauthorized' });
    const url = new URL(req.url ?? '/', 'http://bridge');
    const playerName = url.searchParams.get('player') || undefined;
    try {
      if (req.method === 'GET' && url.pathname === '/status') return send(res, 200, bridge.status());
      if (req.method === 'GET' && url.pathname === '/state') {
        const p = bridge.state.pick(playerName);
        return p ? send(res, 200, p.summary()) : send(res, 404, { error: 'no client has joined through the bridge yet' });
      }
      if (req.method === 'GET' && url.pathname === '/chat') {
        const p = bridge.state.pick(playerName);
        if (!p) return send(res, 404, { error: 'no client has joined through the bridge yet' });
        return send(res, 200, { player: p.name, chat: p.chatSince(Number(url.searchParams.get('since') ?? 0), Number(url.searchParams.get('limit') ?? 50)) });
      }
      if (req.method === 'GET' && url.pathname === '/forms') {
        const p = bridge.state.pick(playerName);
        return send(res, 200, { player: p?.name ?? null, forms: p ? [...p.forms.values()] : [] });
      }
      if (req.method === 'POST' && url.pathname === '/form') {
        let body = '';
        req.on('data', (c) => { body += c; if (body.length > 65536) req.destroy(); });
        req.on('end', () => {
          try {
            const { player, id, response } = JSON.parse(body || '{}');
            send(res, 200, bridge.answerForm(player, Number(id), response));
          } catch (err) {
            send(res, 400, { error: err.message });
          }
        });
        return undefined;
      }
      return send(res, 404, { error: 'not found' });
    } catch (err) {
      return send(res, 500, { error: err.message });
    }
  });
}

export class Bridge {
  constructor(opts, bedrock = require('bedrock-protocol'), auth = { status, login }) {
    this.opts = opts;
    this.bedrock = bedrock;
    this.auth = auth;
    this.state = new BridgeState();
    this.clients = new Map();
    this.signIn = null;
    this.signedIn = opts.auth === 'offline';
    this.listening = false;
  }

  /** auto: signed in when the token folder holds a usable sign-in, offline otherwise. */
  async resolveAuth() {
    if (this.opts.auth !== 'auto') return this.opts.auth;
    const { signedIn } = await this.auth.status(this.opts.authDir, this.opts.account);
    this.opts.auth = signedIn ? 'microsoft' : 'offline';
    this.signedIn = signedIn;
    return this.opts.auth;
  }

  status() {
    return {
      bridge: this.listening ? 'listening' : 'starting',
      lan: this.opts.name,
      target: `${this.opts.host}:${this.opts.port}`,
      version: this.opts.version,
      auth: this.opts.auth,
      account: this.opts.auth === 'microsoft' ? this.opts.account : undefined,
      signedIn: this.signedIn,
      signIn: this.signIn ?? undefined,
      players: [...this.state.players.values()].map((p) => ({ name: p.name, online: p.online }))
    };
  }

  /**
   * With --auth microsoft: signs in first, so no client waits on it. Without a usable sign-in it shows a device
   * code, and meanwhile watches the token folder: a sign-in made elsewhere into it (minecraft-mcp-server's
   * bedrock-login, or `mccli bedrock login`) counts too.
   */
  async signInIfNeeded(pollMs = 5000) {
    if (this.opts.auth !== 'microsoft') return;
    const check = () => this.auth.status(this.opts.authDir, this.opts.account);
    if (!(await check()).signedIn) {
      let stop = false;
      const own = this.auth.login(this.opts.authDir, this.opts.account, (code) => {
        this.signIn = code;
        print({ bridge: 'sign-in', account: this.opts.account, ...code });
      }).then((done) => ({ by: 'code', xuid: done.xuid }));
      const elsewhere = (async () => {
        while (!stop) {
          await new Promise((r) => setTimeout(r, pollMs));
          if (!stop && (await check()).signedIn) return { by: 'token folder' };
        }
        return new Promise(() => {});
      })();
      // The losing flow keeps polling Microsoft until its code expires; its end is not news.
      own.catch(() => {});
      const done = await Promise.race([own, elsewhere]);
      stop = true;
      this.signIn = null;
      print({ bridge: 'signed-in', account: this.opts.account, ...done });
    }
    this.signedIn = true;
  }

  relayOptions() {
    const online = this.opts.auth === 'microsoft';
    return {
      version: this.opts.version,
      host: '0.0.0.0',
      port: 19132,
      transport: 'nethernet',
      offline: true,
      motd: { motd: this.opts.name, levelName: this.opts.name },
      destination: { host: this.opts.host, port: this.opts.port, offline: !online, transport: 'raknet' },
      ...(online ? {
        ...AUTH_OPTIONS,
        // Every client goes out as the signed-in account, whatever name it joined with.
        profilesFolder: cacheFor(this.opts.authDir, this.opts.account),
        onMsaCode: (code, player) => {
          // Only when the cached sign-in expired and could not be renewed: the code goes to the log, not the player.
          this.signIn = { code: code.user_code, url: code.verification_uri, expiresIn: code.expires_in, message: code.message };
          this.signedIn = false;
          print({ bridge: 'sign-in', account: this.opts.account, ...this.signIn });
          try { player?.disconnect('The bridge needs an Xbox sign-in: see its log'); } catch { /* gone */ }
        }
      } : {})
    };
  }

  watch(player) {
    // A connection's state starts once its player has logged in (its name is known), fresh for each connection.
    let ps = null;
    const current = () => {
      if (ps) return ps;
      const name = player.profile?.name;
      if (!name) return null;
      ps = this.state.connect(name);
      this.clients.set(name, player);
      return ps;
    };
    player.on('clientbound', (data) => { try { current()?.clientbound(data.name, data.params); } catch { /* never break the relay */ } });
    player.on('serverbound', (data) => { try { current()?.serverbound(data.name, data.params); } catch { /* never break the relay */ } });
    player.on('join', () => {
      const s = current();
      print({ client: 'joined', player: s?.name, target: `${this.opts.host}:${this.opts.port}`, auth: this.opts.auth });
    });
    player.on('close', () => {
      if (!ps) return;
      ps.online = false;
      if (this.clients.get(ps.name) === player) this.clients.delete(ps.name);
      print({ client: 'left', player: ps.name });
    });
  }

  answerForm(playerName, id, response) {
    const p = this.state.pick(playerName);
    if (!p) throw new Error('no client has joined through the bridge yet');
    if (!p.forms.has(id)) throw new Error(`no open form ${id} for ${p.name}`);
    const player = this.clients.get(p.name);
    if (!player?.upstream) throw new Error(`${p.name} is not connected to the server`);
    player.upstream.queue('modal_form_response', response === undefined || response === null
      ? { form_id: id, has_response_data: false, has_cancel_reason: true, cancel_reason: 'closed' }
      : { form_id: id, has_response_data: true, data: JSON.stringify(response), has_cancel_reason: false });
    // The client still shows the form; ask it to close it (packet from 1.21.2 on).
    try { player.queue('client_bound_close_form', {}); } catch { /* older versions: closed with Escape */ }
    p.forms.delete(id);
    return { player: p.name, answered: id };
  }

  async start() {
    await this.resolveAuth();
    await this.signInIfNeeded();
    this.relay = new this.bedrock.Relay(this.relayOptions());
    this.relay.on('connect', (player) => {
      print({ client: 'connected' });
      this.watch(player);
    });
    await this.relay.listen();
    this.listening = true;
    print({ bridge: 'listening', lan: this.opts.name, target: `${this.opts.host}:${this.opts.port}`, version: this.opts.version, auth: this.opts.auth });
  }

  close() {
    try { this.relay?.close(); } catch { /* closed */ }
  }
}

async function main() {
  if (process.argv.includes('--login')) {
    // The sign-in on its own, as `mccli bedrock login` runs it.
    const argv = process.argv.slice(2);
    const get = (name, fallback) => { const i = argv.indexOf(`--${name}`); return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback; };
    try {
      const done = await login(get('auth-dir', defaultDir()), get('account', 'default'), (code) => print({ login: 'needed', ...code }));
      print({ login: 'done', ...done });
      process.exit(0);
    } catch (err) {
      print({ login: 'failed', error: err instanceof Error ? err.message : String(err) });
      process.exit(1);
    }
  }
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(`${err.message}\nUsage: node bridge.mjs --target host:port [--name "Test server"] [--version 1.26.60] [--offline] [--auth auto|offline|microsoft] [--auth-dir DIR] [--account NAME] [--control host:port|off]`);
    process.exit(2);
  }
  const bridge = new Bridge(opts);
  let control = null;
  if (opts.control) {
    const token = process.env.MCCLI_BEDROCK_BRIDGE_TOKEN || '';
    if (!isLoopback(opts.control.host) && token.length < 16) {
      console.error('A control port on a non-loopback address needs MCCLI_BEDROCK_BRIDGE_TOKEN (16+ characters)');
      process.exit(2);
    }
    control = controlServer(bridge, token);
    control.listen(opts.control.port, opts.control.host, () => print({ control: `http://${opts.control.host}:${opts.control.port}` }));
  }
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { bridge.close(); control?.close(); process.exit(0); });
  try {
    await bridge.start();
  } catch (err) {
    print({ bridge: 'failed', error: err instanceof Error ? err.message : String(err) });
    process.exit(1);
  }
}

if (import.meta.url === `file://${process.argv[1]}`) main();

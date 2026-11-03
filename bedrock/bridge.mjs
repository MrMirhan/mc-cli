// mccli bedrock bridge: shows a Bedrock server (RakNet: Geyser or a dedicated server) to the Bedrock
// clients of this network as a LAN world, and relays each client's game to it.
//
// The Bedrock client for Linux (mcpelauncher, 1.26) cannot join a server by address without a
// Microsoft sign-in, and its network layer fails on direct connections (mcpelauncher-manifest#2074).
// It does join a LAN world over NetherNet. So the bridge advertises one on UDP 7551, accepts the
// client over NetherNet (WebRTC), and relays to the server over RakNet. Both sides are offline: the
// server must allow offline players (online-mode=false, or Geyser's auth-type offline / Floodgate).
//
// The bridge must run on another host or container than the client, on the network of the client's
// default route: the client broadcasts its LAN discovery there only, and a bridge on the client's
// own address gets no game traffic.
//
// Usage: node bridge.mjs --target host:port [--name "Test server"] [--version 1.26.60]
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { Relay } = require('bedrock-protocol');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const target = arg('target');
if (!target || !/^[^:\s]+:\d+$/.test(target)) {
  console.error('Usage: node bridge.mjs --target host:port [--name "Test server"] [--version 1.26.60]');
  process.exit(2);
}
const [host, port] = target.split(':');
const name = arg('name', 'mccli test server');
const version = arg('version', '1.26.60');

const relay = new Relay({
  version,
  host: '0.0.0.0',
  port: 19132,
  transport: 'nethernet',
  offline: true,
  motd: { motd: name, levelName: name },
  destination: { host, port: Number(port), offline: true, transport: 'raknet' },
});
relay.listen();
console.log(JSON.stringify({ bridge: 'listening', lan: name, target, version }));
relay.on('connect', (player) => {
  console.log(JSON.stringify({ client: 'connected' }));
  player.on('join', () => console.log(JSON.stringify({ client: 'joined', target })));
  player.on('close', () => console.log(JSON.stringify({ client: 'left' })));
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { relay.close(); process.exit(0); });

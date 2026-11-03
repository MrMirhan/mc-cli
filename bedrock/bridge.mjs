// mccli bedrock bridge: shows a Bedrock server (RakNet: Geyser or a dedicated server) to the Bedrock
// clients of this network as a LAN world, and relays each client's game to it.
//
// The Bedrock client for Linux (mcpelauncher, 1.26) cannot join a server by address without a
// Microsoft sign-in, and its network layer fails on direct connections (mcpelauncher-manifest#2074).
// It does join a LAN world over NetherNet. So the bridge advertises one on UDP 7551, accepts the
// client over NetherNet (WebRTC), and relays to the server over RakNet.
//
// The bridge must run on another host or container than the client, on the network of the client's
// default route: the client broadcasts its LAN discovery there only, and a bridge on the client's
// own address gets no game traffic.
//
// Online: with MCCLI_BEDROCK_AUTH (a folder that keeps an Xbox sign-in; see --login), the bridge joins
// the server as that Xbox account, so it also joins servers that ask for one (online-mode servers,
// featured servers). The local client still joins the bridge offline, over the LAN. Without the
// folder, or with --offline, the bridge joins as an offline player.
//
// Usage: node bridge.mjs --target host:port [--name "Test server"] [--version 1.26.60] [--offline]
//        node bridge.mjs --login        signs in once: prints a code to enter at microsoft.com/link
import { createRequire } from 'node:module';
import { generateKeyPairSync } from 'node:crypto';
import { existsSync, readdirSync } from 'node:fs';

const require = createRequire(import.meta.url);
const { Relay } = require('bedrock-protocol');
const { Authflow, Titles } = createRequire(require.resolve('bedrock-protocol'))('prismarine-auth');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const say = (o) => console.log(JSON.stringify(o));
// The sign-in is kept under this name in the folder, whoever plays through the bridge.
const AUTH_NAME = 'mccli-bridge';
const AUTH = { authTitle: Titles.MinecraftNintendoSwitch, deviceType: 'Nintendo', flow: 'live' };
const authDir = process.env.MCCLI_BEDROCK_AUTH;
const signedIn = () => !!authDir && existsSync(authDir) && readdirSync(authDir).some((f) => f.endsWith('.json'));

if (process.argv.includes('--login')) {
  if (!authDir) {
    console.error('Set MCCLI_BEDROCK_AUTH to the folder that keeps the sign-in');
    process.exit(2);
  }
  const flow = new Authflow(AUTH_NAME, authDir, AUTH, (code) => say({ login: 'needed', url: code.verification_uri, code: code.user_code, expires_in: code.expires_in }));
  const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'secp384r1' });
  const token = await flow.getMinecraftBedrockToken(publicKey.export({ format: 'der', type: 'spki' }).toString('base64'));
  const profile = JSON.parse(Buffer.from(token.chain[1].split('.')[1], 'base64').toString());
  say({ login: 'done', gamertag: profile?.extraData?.displayName ?? null });
  process.exit(0);
}

const target = arg('target');
if (!target || !/^[^:\s]+:\d+$/.test(target)) {
  console.error('Usage: node bridge.mjs --target host:port [--name "Test server"] [--version 1.26.60] [--offline]');
  process.exit(2);
}
const [host, port] = target.split(':');
const name = arg('name', 'mccli test server');
const version = arg('version', '1.26.60');
const online = !process.argv.includes('--offline') && signedIn();

const relay = new Relay({
  version,
  host: '0.0.0.0',
  port: 19132,
  transport: 'nethernet',
  // The local client joins offline, over the LAN; the server sees the bridge's player.
  offline: true,
  motd: { motd: name, levelName: name },
  destination: { host, port: Number(port), offline: !online, transport: 'raknet' },
  ...(online ? { ...AUTH, profilesFolder: authDir } : {}),
  // A sign-in that is gone asks for a new one: the code goes to the log, not to the player's screen.
  onMsaCode: (code, player) => {
    say({ login: 'needed', url: code.verification_uri, code: code.user_code, expires_in: code.expires_in });
    player.disconnect('The bridge needs an Xbox sign-in: see its log');
  },
});
if (online) {
  // The Relay keys the sign-in by the local player's name; the bridge keeps one sign-in for all.
  const open = relay.openUpstreamConnection.bind(relay);
  relay.openUpstreamConnection = (player, address) => {
    const name = player.profile.name;
    player.profile.name = AUTH_NAME;
    try {
      return open(player, address);
    } finally {
      player.profile.name = name;
    }
  };
}
relay.listen();
say({ bridge: 'listening', lan: name, target, version, online });
relay.on('connect', (player) => {
  say({ client: 'connected' });
  player.on('join', () => say({ client: 'joined', target }));
  player.on('close', () => say({ client: 'left' }));
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { relay.close(); process.exit(0); });

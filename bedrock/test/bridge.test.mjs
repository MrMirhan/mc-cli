import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Bridge, controlServer, parseArgs } from '../bridge.mjs';

const base = ['--target', 'geyser.test:19132'];

test('arguments: auto by default, --offline or --auth to choose, a control port or off', () => {
  const o = parseArgs(base);
  assert.equal(o.auth, 'auto');
  assert.equal(parseArgs([...base, '--offline']).auth, 'offline');
  assert.deepEqual(o.control, { host: '127.0.0.1', port: 19150 });
  assert.equal(parseArgs([...base, '--auth', 'microsoft', '--account', 'work']).account, 'work');
  assert.equal(parseArgs([...base, '--control', 'off']).control, null);
  assert.throws(() => parseArgs([...base, '--auth', 'xbox']), /auto, offline or microsoft/);
  assert.throws(() => parseArgs([]), /--target/);
});

test('the client side stays offline; only the server side signs in, with one cached account', () => {
  const offline = new Bridge(parseArgs([...base, '--offline']), {}).relayOptions();
  assert.equal(offline.offline, true);
  assert.equal(offline.destination.offline, true);
  assert.equal(offline.profilesFolder, undefined);
  const online = new Bridge(parseArgs([...base, '--auth', 'microsoft', '--auth-dir', '/tmp/mccli-test-auth']), {}).relayOptions();
  assert.equal(online.offline, true);
  assert.equal(online.destination.offline, false);
  assert.equal(online.flow, 'live');
  assert.equal(typeof online.profilesFolder, 'function');
});

class FakePlayer extends EventEmitter {
  constructor(name) { super(); this.profile = { name }; this.sent = []; this.upstream = { queue: (n, p) => this.sent.push(['up', n, p]) }; }
  queue(n, p) { this.sent.push(['down', n, p]); }
}

async function call(server, path, body) {
  const { port } = server.address();
  const res = await fetch(`http://127.0.0.1:${port}${path}`, body ? { method: 'POST', body: JSON.stringify(body), headers: { authorization: 'Bearer secret-token-123456' } } : { headers: { authorization: 'Bearer secret-token-123456' } });
  return { code: res.status, body: await res.json() };
}

test('the control API serves the state and answers a form for the client', async () => {
  const bridge = new Bridge(parseArgs(base), {});
  const player = new FakePlayer('Steve');
  bridge.watch(player);
  player.emit('clientbound', { name: 'start_game', params: { runtime_entity_id: 1n, player_position: { x: 0, y: 64, z: 0 }, dimension: 'overworld', player_gamemode: 'creative' } });
  player.emit('clientbound', { name: 'modal_form_request', params: { form_id: 9, data: '{"type":"modal","title":"Sure?"}' } });
  const server = controlServer(bridge, 'secret-token-123456').listen(0, '127.0.0.1');
  await new Promise((r) => server.once('listening', r));
  try {
    const { port } = server.address();
    assert.equal((await fetch(`http://127.0.0.1:${port}/status`)).status, 401);
    assert.equal((await call(server, '/state')).body.gamemode, 'creative');
    assert.equal((await call(server, '/forms')).body.forms[0].id, 9);
    assert.deepEqual((await call(server, '/form', { id: 9, response: true })).body, { player: 'Steve', answered: 9 });
    assert.deepEqual(player.sent[0], ['up', 'modal_form_response', { form_id: 9, has_response_data: true, data: 'true', has_cancel_reason: false }]);
    assert.deepEqual(player.sent[1], ['down', 'client_bound_close_form', {}]);
    assert.equal((await call(server, '/form', { id: 9, response: 0 })).code, 400);
    player.emit('close');
    assert.equal((await call(server, '/status')).body.players[0].online, false);
  } finally {
    server.close();
  }
});

test('with microsoft, the bridge shows its code, and a sign-in made elsewhere into the token folder lets it go on', async () => {
  let signedIn = false;
  const shown = [];
  const auth = {
    status: async () => ({ signedIn }),
    login: (_dir, _account, onCode) => { onCode({ code: 'XYZ', url: 'https://www.microsoft.com/link' }); return new Promise(() => {}); }
  };
  const bridge = new Bridge(parseArgs([...base, '--auth', 'microsoft']), {}, auth);
  const log = console.log;
  console.log = (line) => shown.push(JSON.parse(line));
  try {
    const waiting = bridge.signInIfNeeded(10);
    await new Promise((r) => setTimeout(r, 30));
    assert.equal(bridge.status().signIn.code, 'XYZ');
    assert.equal(bridge.status().signedIn, false);
    signedIn = true;
    await waiting;
  } finally {
    console.log = log;
  }
  assert.equal(bridge.signedIn, true);
  assert.equal(bridge.status().signIn, undefined);
  assert.deepEqual(shown.map((l) => l.bridge), ['sign-in', 'signed-in']);
  assert.equal(shown[1].by, 'token folder');
});

test('auto is online when the folder holds a sign-in, offline when not', async () => {
  for (const signedIn of [true, false]) {
    const bridge = new Bridge(parseArgs(base), {}, { status: async () => ({ signedIn }), login: async () => { throw new Error('not called'); } });
    assert.equal(await bridge.resolveAuth(), signedIn ? 'microsoft' : 'offline');
    assert.equal(bridge.relayOptions().destination.offline, !signedIn);
  }
});

test('offline needs no sign-in at all', async () => {
  const bridge = new Bridge(parseArgs([...base, '--offline']), {}, { status: async () => { throw new Error('not called'); }, login: async () => { throw new Error('not called'); } });
  await bridge.signInIfNeeded(10);
  assert.equal(bridge.signedIn, true);
});

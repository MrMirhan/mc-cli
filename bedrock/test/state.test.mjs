import test from 'node:test';
import assert from 'node:assert/strict';
import { BridgeState, PlayerState } from '../state.mjs';

test('a client\'s game from the packets the bridge relays', () => {
  const p = new PlayerState('Steve');
  p.clientbound('start_game', { runtime_entity_id: 7n, player_position: { x: 1.04, y: 64, z: -3.5 }, dimension: 'overworld', player_gamemode: 'fallback', world_gamemode: 'survival', world_name: 'Lobby' });
  p.clientbound('item_registry', { itemstates: [{ name: 'minecraft:diamond', runtime_id: 300 }] });
  p.clientbound('inventory_content', { window_id: 'inventory', input: [{ network_id: 300, count: 3 }, { network_id: 0 }] });
  p.clientbound('inventory_slot', { window_id: 'inventory', slot: 4, item: { network_id: 999, count: 1 } });
  p.clientbound('update_attributes', { runtime_entity_id: 7n, attributes: [{ name: 'minecraft:health', current: 14.5 }] });
  p.clientbound('move_player', { runtime_id: 8n, position: { x: 50, y: 70, z: 50 } });
  p.serverbound('player_auth_input', { position: { x: 2, y: 65.62, z: -3 } });
  p.clientbound('text', { type: 'chat', source_name: 'Alex', message: 'hi', parameters: [] });
  p.clientbound('player_list', { records: { type: 'add', records: [{ uuid: 'a', username: 'Alex' }, { uuid: 's', username: 'Steve' }] } });
  p.clientbound('player_list', { records: { type: 'remove', records: [{ uuid: 'a' }] } });
  p.clientbound('modal_form_request', { form_id: 3, data: '{"type":"form","title":"Menu","buttons":[{"text":"Play"}]}' });
  const s = p.summary();
  assert.equal(s.gamemode, 'survival');
  assert.equal(s.world, 'Lobby');
  assert.deepEqual(s.position, { x: 2, y: 65.6, z: -3 });
  assert.equal(s.health, 14.5);
  assert.deepEqual(s.inventory, [{ slot: 0, name: 'minecraft:diamond', count: 3 }, { slot: 4, name: 'item#999', count: 1 }]);
  assert.deepEqual(s.players, ['Steve']);
  assert.equal(s.forms[0].form.title, 'Menu');
  assert.deepEqual(p.chatSince(0).map((c) => [c.source, c.message]), [['Alex', 'hi']]);
  p.serverbound('modal_form_response', { form_id: 3 });
  assert.equal(p.summary().forms.length, 0);
  p.clientbound('disconnect', { message: 'Server closed' });
  assert.equal(p.summary().online, false);
});

test('a reconnect starts fresh; the default pick is the newest client online', () => {
  const b = new BridgeState();
  const a = b.connect('Alex');
  a.clientbound('set_health', { health: 3 });
  const steve = b.connect('Steve');
  steve.joinedAt = a.joinedAt + 1;
  assert.equal(b.pick().name, 'Steve');
  steve.online = false;
  assert.equal(b.pick().name, 'Alex');
  assert.equal(b.connect('Alex').health, undefined);
  assert.equal(b.pick('Nobody'), null);
});

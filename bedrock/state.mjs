// What the bridge sees of each client's game, from the packets it relays: the real Bedrock client cannot
// load a mod, so this is how a tool reads its position, health, inventory, chat, forms and the tab list.

const MAX_CHAT = 200;

function vec(v) {
  return v && typeof v.x === 'number'
    ? { x: Math.round(v.x * 10) / 10, y: Math.round((v.y ?? 0) * 10) / 10, z: Math.round((v.z ?? 0) * 10) / 10 }
    : undefined;
}

function big(v) {
  try { return BigInt(v); } catch { return null; }
}

function textOf(p) {
  const params = Array.isArray(p.parameters) && p.parameters.length ? ` [${p.parameters.join(', ')}]` : '';
  return `${String(p.message ?? '')}${params}`;
}

function parseForm(data) {
  try { return JSON.parse(String(data)); } catch { return data; }
}

/** One client's game as the bridge sees it. */
export class PlayerState {
  constructor(name) {
    this.name = name;
    this.joinedAt = Date.now();
    this.online = true;
    this.entityId = null;
    this.position = undefined;
    this.dimension = undefined;
    this.gamemode = undefined;
    this.health = undefined;
    this.world = undefined;
    this.lastDisconnect = undefined;
    this.chat = [];
    this.chatSeq = 0;
    this.forms = new Map();
    this.players = new Map();
    this.entities = new Map();
    this.items = new Map();
    this.inventory = new Map();
    this.title = undefined;
  }

  /** A packet the server sent the client. */
  clientbound(name, p) {
    switch (name) {
      case 'start_game':
        this.entityId = big(p.runtime_entity_id);
        this.position = vec(p.player_position);
        this.dimension = String(p.dimension);
        this.gamemode = String(p.player_gamemode === 'fallback' ? p.world_gamemode : p.player_gamemode);
        this.world = p.world_name ?? p.level_id;
        for (const s of p.itemstates ?? []) this.items.set(Number(s.runtime_id), String(s.name));
        break;
      case 'item_registry':
        for (const s of p.itemstates ?? []) this.items.set(Number(s.runtime_id), String(s.name));
        break;
      case 'respawn':
        this.position = vec(p.position) ?? this.position;
        break;
      case 'move_player':
        if (big(p.runtime_id) === this.entityId) this.position = vec(p.position);
        break;
      case 'set_health':
        this.health = p.health;
        break;
      case 'update_attributes':
        if (big(p.runtime_entity_id) === this.entityId) {
          const h = (p.attributes ?? []).find((a) => a.name === 'minecraft:health');
          if (h) this.health = Math.round(h.current * 10) / 10;
        }
        break;
      case 'set_player_game_type':
        this.gamemode = String(p.gamemode);
        break;
      case 'change_dimension':
        this.dimension = String(p.dimension);
        this.position = vec(p.position);
        break;
      case 'text':
        this.note({ type: String(p.type), source: String(p.source_name ?? ''), message: textOf(p) });
        break;
      case 'set_title':
        this.title = { type: String(p.type), text: String(p.text ?? ''), at: Date.now() };
        break;
      case 'modal_form_request': {
        const id = Number(p.form_id);
        this.forms.set(id, { id, at: Date.now(), form: parseForm(p.data) });
        break;
      }
      case 'player_list':
        for (const r of p.records?.records ?? p.records ?? []) {
          if (p.records?.type === 'remove' || r.type === 'remove') this.players.delete(r.uuid);
          else this.players.set(r.uuid, r.username ?? r.uuid);
        }
        break;
      case 'add_player':
        this.entities.set(String(p.runtime_id), { kind: 'player', name: String(p.username ?? '') , position: vec(p.position) });
        break;
      case 'add_entity':
        this.entities.set(String(p.runtime_id), { kind: String(p.entity_type ?? 'entity'), position: vec(p.position) });
        break;
      case 'remove_entity':
        this.entities.delete(String(p.entity_id_self));
        break;
      case 'inventory_content':
        if (isInventory(p.window_id)) {
          this.inventory.clear();
          (p.input ?? []).forEach((item, slot) => this.setSlot(slot, item));
        }
        break;
      case 'inventory_slot':
        if (isInventory(p.window_id)) this.setSlot(Number(p.slot), p.item);
        break;
      case 'disconnect':
        this.online = false;
        this.lastDisconnect = String(p.message || p.reason || 'disconnected');
        break;
      default:
        break;
    }
  }

  /** A packet the client sent the server. */
  serverbound(name, p) {
    if (name === 'player_auth_input') this.position = vec(p.position) ?? this.position;
    if (name === 'modal_form_response') this.forms.delete(Number(p.form_id));
  }

  setSlot(slot, item) {
    const id = Number(item?.network_id ?? 0);
    if (!id) this.inventory.delete(slot);
    else this.inventory.set(slot, { slot, name: this.items.get(id) ?? `item#${id}`, count: Number(item.count ?? 1) });
  }

  note(line) {
    this.chat.push({ id: ++this.chatSeq, at: Date.now(), ...line });
    if (this.chat.length > MAX_CHAT) this.chat.splice(0, this.chat.length - MAX_CHAT);
  }

  summary() {
    return {
      name: this.name,
      online: this.online,
      world: this.world,
      position: this.position,
      dimension: this.dimension,
      gamemode: this.gamemode,
      health: this.health,
      title: this.title,
      players: [...this.players.values()].sort(),
      nearbyPlayers: [...this.entities.values()].filter((e) => e.kind === 'player').map((e) => e.name),
      entities: this.entities.size,
      inventory: [...this.inventory.values()].sort((a, b) => a.slot - b.slot),
      forms: [...this.forms.values()],
      chat: this.chat.length,
      lastDisconnect: this.lastDisconnect
    };
  }

  chatSince(since = 0, limit = 50) {
    return this.chat.filter((c) => c.id > since).slice(-limit);
  }
}

function isInventory(windowId) {
  return windowId === 'inventory' || windowId === 0 || windowId === '0';
}

/** Every client the bridge has carried, by player name. */
export class BridgeState {
  constructor() {
    this.players = new Map();
  }

  /** A new connection of `name`: a fresh state in place of an earlier connection's. */
  connect(name) {
    const state = new PlayerState(name);
    this.players.set(name, state);
    return state;
  }

  player(name) {
    return this.players.get(name) ?? this.connect(name);
  }

  /** The named player, or else the one online that joined last (or the last one at all). */
  pick(name) {
    if (name) return this.players.get(name) ?? null;
    let latest = null;
    for (const p of this.players.values()) {
      const better = !latest || (p.online && !latest.online) || (p.online === latest.online && p.joinedAt >= latest.joinedAt);
      if (better) latest = p;
    }
    return latest;
  }
}

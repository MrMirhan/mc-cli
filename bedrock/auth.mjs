// Microsoft (Xbox Live) sign-in for the bridge's connection to an online server.
//
// Tokens are cached in one folder under fixed file names, one set per account name, so a single sign-in
// serves every connection: bedrock-protocol keys its cache by the joining player's name, and the bridge
// replaces that with the account given here. minecraft-mcp-server's Bedrock bot writes the same layout,
// so both can share one folder.
//
//   node auth.mjs login  [--dir D] [--account A]   device code sign-in; prints {"code": ...} then {"signedIn": ...}
//   node auth.mjs status [--dir D] [--account A]   {"signedIn": true|false}; never prompts
//   node auth.mjs logout [--dir D] [--account A]   removes the account's cached tokens
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { Authflow, Titles } = require('prismarine-auth');
const FileCache = require('prismarine-auth/src/common/cache/FileCache');

/** The cache names prismarine-auth uses (msa or live token, Xbox, Bedrock, PlayFab, ...). */
export const CACHE_IDS = ['msal', 'live', 'sisu', 'xbl', 'bed', 'mca', 'mcs', 'pfb'];

/** The same sign-in bedrock-protocol uses by default: the Nintendo Switch title over the live flow. */
export const AUTH_OPTIONS = Object.freeze({ authTitle: Titles.MinecraftNintendoSwitch, deviceType: 'Nintendo', flow: 'live' });

export function defaultDir() {
  return process.env.MCCLI_BEDROCK_AUTH || path.join(os.homedir(), '.cache', 'mccli-bedrock', 'auth');
}

export function checkAccount(account) {
  if (!/^[A-Za-z0-9_.-]{1,64}$/.test(account)) throw new Error('An account name is 1-64 letters, digits, dots, dashes or underscores');
  return account;
}

/** prismarine-auth cache factory: the account's files, whatever player name the connection asks for. */
export function cacheFor(dir, account = 'default') {
  checkAccount(account);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  return ({ cacheName }) => {
    if (!CACHE_IDS.includes(cacheName)) throw new Error(`Unknown token cache '${cacheName}'`);
    const file = path.join(dir, `${account}_${cacheName}-cache.json`);
    const cache = new FileCache(file);
    // Tokens are secrets: owner-only files, whichever way the cache writes them.
    for (const method of ['setCached', 'reset']) {
      const original = cache[method].bind(cache);
      cache[method] = async (...args) => { const out = await original(...args); fs.chmodSync(file, 0o600); return out; };
    }
    return cache;
  };
}

export function cacheFiles(dir, account = 'default') {
  checkAccount(account);
  return CACHE_IDS.map((id) => path.join(dir, `${account}_${id}-cache.json`)).filter((f) => fs.existsSync(f));
}

/** A device code, as the caller shows it to the person. */
export function describeCode(code) {
  return {
    code: code.user_code,
    url: code.verification_uri,
    link: `https://microsoft.com/link?otc=${code.user_code}`,
    expiresIn: code.expires_in,
    message: code.message
  };
}

function authflow(dir, account, onCode) {
  return new Authflow('', cacheFor(dir, account), { ...AUTH_OPTIONS }, onCode);
}

/** Whether the account has a usable sign-in; never asks for a device code. */
export async function status(dir, account = 'default') {
  const flow = authflow(dir, account, () => { throw new Error('not signed in'); });
  try {
    return { account, signedIn: Boolean(await flow.msa.verifyTokens()) };
  } catch {
    return { account, signedIn: false };
  }
}

/**
 * Signs in with a device code when the account has no usable sign-in. `onCode` gets the code to show;
 * resolves once Xbox Live accepts the account.
 */
export async function login(dir, account = 'default', onCode = () => {}) {
  const flow = authflow(dir, account, (code) => onCode(describeCode(code)));
  const xbox = await flow.getXboxToken();
  return { account, signedIn: true, xuid: xbox.userXUID ?? null };
}

export function logout(dir, account = 'default') {
  const removed = cacheFiles(dir, account);
  for (const f of removed) fs.rmSync(f, { force: true });
  return { account, signedIn: false, removed: removed.length };
}

function arg(argv, name, fallback) {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
}

async function main(argv) {
  const action = argv[0];
  const dir = arg(argv, 'dir', defaultDir());
  const account = arg(argv, 'account', 'default');
  const print = (o) => console.log(JSON.stringify(o));
  if (action === 'status') return print(await status(dir, account));
  if (action === 'logout') return print(logout(dir, account));
  if (action === 'login') return print(await login(dir, account, (code) => print({ code })));
  console.error('Usage: node auth.mjs login|status|logout [--dir D] [--account A]');
  process.exitCode = 2;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((err) => {
    console.log(JSON.stringify({ error: err instanceof Error ? err.message : String(err) }));
    process.exitCode = 1;
  });
}

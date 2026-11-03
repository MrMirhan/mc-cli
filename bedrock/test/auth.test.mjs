import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { cacheFor, cacheFiles, checkAccount, describeCode, logout, prefixFor, status } from '../auth.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'mccli-auth-'));

test('one account\'s token files, whatever player name a connection asks with', async () => {
  const dir = tmp();
  const cache = cacheFor(dir, 'work');
  const a = cache({ cacheName: 'live', username: 'Steve' });
  await a.setCached({ token: 'x' });
  const b = cache({ cacheName: 'live', username: '2535400000000' });
  assert.deepEqual(await b.getCached(), { token: 'x' });
  const file = path.join(dir, 'work_live-cache.json');
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  await cache({ cacheName: 'xbl' }).reset();
  assert.equal(fs.statSync(path.join(dir, 'work_xbl-cache.json')).mode & 0o777, 0o600);
  assert.equal(cacheFiles(dir, 'work').length, 2);
  assert.throws(() => cache({ cacheName: 'nope' }), /Unknown token cache/);
  assert.deepEqual(logout(dir, 'work'), { account: 'work', signedIn: false, removed: 2 });
  assert.deepEqual(cacheFiles(dir, 'work'), []);
});

test('an account name cannot leave the folder; no tokens means not signed in, without asking', async () => {
  assert.throws(() => checkAccount('../x'), /account name/);
  assert.deepEqual(await status(tmp(), 'default'), { account: 'default', signedIn: false });
  assert.deepEqual(describeCode({ user_code: 'ABC', verification_uri: 'https://www.microsoft.com/link', expires_in: 900, message: 'm' }),
    { code: 'ABC', url: 'https://www.microsoft.com/link', link: 'https://microsoft.com/link?otc=ABC', expiresIn: 900, message: 'm' });
});

test('the default account keeps the file names of the first `mccli bedrock login`', async () => {
  const dir = tmp();
  // prismarine-auth's own name for the cache of the user "mccli-bridge"
  assert.equal(prefixFor('default'), '1b83eb');
  await cacheFor(dir)({ cacheName: 'live' }).setCached({ t: 1 });
  assert.deepEqual(fs.readdirSync(dir), [`${prefixFor('default')}_live-cache.json`]);
  assert.equal(prefixFor('work'), 'work');
});

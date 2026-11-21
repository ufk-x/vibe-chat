import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createVibeServer } from '../src/main.mjs';

test('accounts, friend consent, QR revocation, group ACL, voice persistence and retry', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'vibe-api-'));
  const options = { dbPath: join(dir, 'vibe.sqlite'), publicOrigin: 'https://vibe.example', testMode: true };
  let server = createVibeServer(options); let base;
  const listen = async () => { await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); base = `http://127.0.0.1:${server.address().port}`; };
  await listen();
  t.after(async () => { await new Promise(resolve => server.close(resolve)); rmSync(dir, { recursive: true }); });
  async function api(path, data, who, method = data === undefined ? 'GET' : 'POST', status = 200) {
    const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(who ? { Authorization: `Bearer ${who.token}` } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }) });
    const result = await response.json(); assert.equal(response.status, status, `${method} ${path}: ${JSON.stringify(result)}`); return result;
  }
  const password = 'vibe-test-password-2026';
  const alice = await api('/v1/register', { username: 'alice', name: '阿梨', password }, null, 'POST', 201);
  const bob = await api('/v1/register', { username: 'bob', name: '小波', password }, null, 'POST', 201);
  const eve = await api('/v1/register', { username: 'eve', name: '路人', password }, null, 'POST', 201);
  await t.test('authentication is required and credentials are validated', async () => {
    await api('/v1/state', undefined, null, 'GET', 401);
    await api('/v1/login', { username: 'alice', password: 'wrong-password' }, null, 'POST', 401);
    const signed = await api('/v1/login', { username: 'ALICE', password }); assert.equal(signed.user.id, alice.user.id);
    await api('/v1/register', { username: 'alice', name: '重复', password }, null, 'POST', 409);
    await api('/v1/register', { username: 'bad', name: '短密码', password: '123' }, null, 'POST', 400);
  });
  let direct, group, oldInvite, voice;
  await t.test('friend QR sends an application; only recipient may accept', async () => {
    await api('/v1/chats', { kind: 'direct', friend_id: bob.user.id }, alice, 'POST', 403);
    const invite = await api('/v1/invites', {}, alice, 'POST', 201);
    assert.match(invite.code, /^vibe2\|https:\/\/vibe.example\|/);
    assert.equal((await api('/v1/invites/preview', { token: invite.token }, bob)).user.name, '阿梨');
    await api('/v1/invites/accept', { token: invite.token }, bob);
    let state = await api('/v1/state', undefined, alice); assert.equal(state.friends.length, 0); assert.equal(state.requests.length, 1);
    await api(`/v1/requests/${state.requests[0].id}`, { accept: true }, eve, 'POST', 404);
    await api(`/v1/requests/${state.requests[0].id}`, { accept: true }, alice);
    assert.equal((await api('/v1/state', undefined, bob)).friends[0].id, alice.user.id);
    direct = (await api('/v1/chats', { kind: 'direct', friend_id: bob.user.id }, alice, 'POST', 201)).chat_id;
    assert.equal((await api('/v1/chats', { kind: 'direct', friend_id: alice.user.id }, bob, 'POST', 201)).chat_id, direct);
  });
  await t.test('direct messages are private, persist offline and deduplicate retries', async () => {
    const body = { client_id: randomUUID(), kind: 'voice', audio: Buffer.alloc(32000, 1).toString('base64') };
    voice = (await api(`/v1/chats/${direct}/messages`, body, alice, 'POST', 201)).message;
    assert.equal(voice.seconds, 1);
    assert.equal((await api(`/v1/chats/${direct}/messages`, body, alice, 'POST', 201)).message.id, voice.id);
    await api(`/v1/chats/${direct}/messages`, undefined, eve, 'GET', 403);
    await api(`/v1/audio/${voice.id}`, undefined, eve, 'GET', 404);
    const response = await fetch(`${base}/v1/audio/${voice.id}`, { headers: { Authorization: `Bearer ${bob.token}` } });
    assert.equal(response.status, 200); assert.deepEqual(Buffer.from(await response.arrayBuffer()), Buffer.alloc(32000, 1));
    const messages = (await api(`/v1/chats/${direct}/messages`, undefined, bob)).messages; assert.equal(messages.length, 1);
    assert.equal((await api('/v1/state', undefined, bob)).chats[0].unread, 1);
    assert.equal((await api('/v1/state', undefined, bob)).chats[0].last_kind, 'voice');
    await api(`/v1/chats/${direct}/read`, { message_id: voice.id }, bob);
    assert.equal((await api('/v1/state', undefined, bob)).chats[0].unread, 0);
    await api(`/v1/chats/${direct}/messages`, { client_id: randomUUID(), kind: 'voice', audio: 'AA==' }, alice, 'POST', 400);
  });
  await t.test('maximum 30-second Android voice fits even when every Base64 slash is escaped', async () => {
    const pcm = Buffer.alloc(960000, 255);
    const raw = JSON.stringify({ client_id: randomUUID(), kind: 'voice', audio: pcm.toString('base64') }).replaceAll('/', '\\/');
    assert(raw.length > 2500000);
    const response = await fetch(`${base}/v1/chats/${direct}/messages`, { method: 'POST', headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' }, body: raw });
    assert.equal(response.status, 201); const result = await response.json(); assert.equal(result.message.seconds, 30);
    const audio = await fetch(`${base}/v1/audio/${result.message.id}`, { headers: { Authorization: `Bearer ${bob.token}` } });
    assert.deepEqual(Buffer.from(await audio.arrayBuffer()), pcm);
  });
  await t.test('group invite reset revokes old QR and membership controls history', async () => {
    group = (await api('/v1/chats', { kind: 'group', name: '周末散步' }, alice, 'POST', 201)).chat_id;
    oldInvite = await api('/v1/invites', { chat_id: group }, alice, 'POST', 201);
    const invite = await api('/v1/invites', { chat_id: group }, alice, 'POST', 201);
    await api('/v1/invites/accept', { token: oldInvite.token }, bob, 'POST', 404);
    await api(`/v1/chats/${group}/messages`, { client_id: randomUUID(), kind: 'text', text: '加入前的消息' }, alice, 'POST', 201);
    await new Promise(resolve => setTimeout(resolve, 5));
    await api('/v1/invites/accept', { token: invite.token }, bob);
    const joinedPreview = (await api('/v1/state', undefined, bob)).chats.find(c => c.id === group);
    assert.equal(joinedPreview.last_preview, null, 'list previews must not expose messages from before admission');
    assert.equal(joinedPreview.last_kind, null);
    await api('/v1/invites', { chat_id: group }, bob, 'POST', 403);
    assert.equal((await api(`/v1/chats/${group}/messages`, undefined, bob)).messages.length, 0);
    const groupMessage = (await api(`/v1/chats/${group}/messages`, { client_id: randomUUID(), kind: 'text', text: '周六见' }, alice, 'POST', 201)).message;
    assert.equal((await api(`/v1/chats/${group}/messages`, undefined, bob)).messages[0].text, '周六见');
    await api(`/v1/chats/${direct}/read`, { message_id: groupMessage.id }, bob, 'POST', 400);
    await api(`/v1/chats/${group}/messages`, { client_id: voice.client_id, kind: 'text', text: 'wrong target retry' }, alice, 'POST', 409);
    await api(`/v1/chats/${group}/leave`, {}, bob);
    await api(`/v1/chats/${group}/messages`, undefined, bob, 'GET', 403);
    await api(`/v1/chats/${group}`, undefined, eve, 'DELETE', 403);
    const events = (await api('/v1/events?after=0', undefined, bob)).events;
    assert(!events.some(e => e.message?.chat_id === group), 'left group must not leak messages through events');
  });
  const avatar = readFileSync(new URL('./fixtures/avatar.jpg', import.meta.url));
  await t.test('friend remarks are private, bounded and can be cleared', async () => {
    await api(`/v1/friends/${bob.user.id}/remark`, { remark: '林间 · 同事' }, alice, 'PUT');
    const own = await api('/v1/state', undefined, alice), peer = await api('/v1/state', undefined, bob);
    assert.equal(own.friends[0].remark, '林间 · 同事'); assert.equal(own.chats.find(c => c.id === direct).name, '林间 · 同事');
    assert.equal(own.friends[0].name, '小波'); assert.equal(peer.friends[0].remark, '');
    assert(!JSON.stringify(peer).includes('林间 · 同事'));
    await api(`/v1/friends/${bob.user.id}/remark`, { remark: 'unauthorized' }, eve, 'PUT', 403);
    await api(`/v1/friends/${bob.user.id}/remark`, { remark: '字'.repeat(41) }, alice, 'PUT', 400);
    await api(`/v1/friends/${bob.user.id}/remark`, { remark: '' }, alice, 'PUT');
    assert.equal((await api('/v1/state', undefined, alice)).chats.find(c => c.id === direct).name, '小波');
    await api(`/v1/friends/${bob.user.id}/remark`, { remark: '我的朋友' }, alice, 'PUT');
  });
  await t.test('avatar upload is bounded, private to contacts, replaceable and removable', async () => {
    const uploaded = await api('/v1/profile/avatar', { image: avatar.toString('base64') }, alice, 'PUT');
    assert.match(uploaded.user.avatar_version, /^[a-f0-9]{24}$/);
    const avatarPath = `/v1/users/${alice.user.id}/avatar?v=${uploaded.user.avatar_version}`;
    const response = await fetch(base + avatarPath, { headers: { Authorization: `Bearer ${bob.token}` } });
    assert.equal(response.status, 200); assert.equal(response.headers.get('content-type'), 'image/jpeg'); assert.deepEqual(Buffer.from(await response.arrayBuffer()), avatar);
    await api(avatarPath, undefined, eve, 'GET', 404); await api(avatarPath, undefined, null, 'GET', 401);
    await api('/v1/profile/avatar', { image: Buffer.from('<script>alert(1)</script>').toString('base64') }, alice, 'PUT', 400);
    await api('/v1/profile/avatar', { image: Buffer.alloc(200001).toString('base64') }, alice, 'PUT', 400);
    const huge = Buffer.from(avatar); const sof = huge.indexOf(Buffer.from([0xff, 0xc0])); assert(sof > 0); huge.writeUInt16BE(10000, sof + 7);
    await api('/v1/profile/avatar', { image: huge.toString('base64') }, alice, 'PUT', 400);
    assert.equal((await api('/v1/state', undefined, bob)).friends[0].avatar_version, uploaded.user.avatar_version);
    await api('/v1/profile/avatar', undefined, alice, 'DELETE'); await api(avatarPath, undefined, bob, 'GET', 404);
    assert.equal((await api('/v1/state', undefined, alice)).user.avatar_version, '');
    await api('/v1/profile/avatar', { image: avatar.toString('base64') }, alice, 'PUT');
  });
  await t.test('restart retains accounts, friendships, remarks, avatars and messages', async () => {
    await new Promise(resolve => server.close(resolve)); server = createVibeServer(options); await listen();
    assert.equal((await api('/v1/state', undefined, bob)).friends[0].id, alice.user.id);
    assert.equal((await api('/v1/state', undefined, alice)).friends[0].remark, '我的朋友');
    assert.match((await api('/v1/state', undefined, alice)).user.avatar_version, /^[a-f0-9]{24}$/);
    assert.equal((await api(`/v1/chats/${direct}/messages`, undefined, bob)).messages[0].id, voice.id);
    await api(`/v1/chats/${group}`, undefined, alice, 'DELETE');
    assert.equal((await api('/v1/state', undefined, alice)).chats.length, 1);
  });
  await t.test('password rotation revokes old sessions; logout revokes the new one', async () => {
    const changed = await api('/v1/password', { old_password: password, password: 'new-vibe-password-2026' }, alice);
    await api('/v1/state', undefined, alice, 'GET', 401);
    await api('/v1/login', { username: 'alice', password }, null, 'POST', 401);
    await api('/v1/logout', {}, changed);
    await api('/v1/state', undefined, changed, 'GET', 401);
  });
});

test('twenty users behind one Wi-Fi can register and synchronize without a shared-IP lockout', async t => {
  const server = createVibeServer({ dbPath: ':memory:', publicOrigin: 'https://vibe.example' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const tokens = [];
  for (let i = 0; i < 20; i++) {
    const r = await fetch(base + '/v1/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: `wifi_user_${i}`, name: `User ${i}`, password: 'shared-wifi-test-password' }) });
    assert.equal(r.status, 201); tokens.push((await r.json()).token);
  }
  for (const token of tokens) for (let i = 0; i < 10; i++) {
    const r = await fetch(base + '/v1/state', { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(r.status, 200); await r.arrayBuffer();
  }
});

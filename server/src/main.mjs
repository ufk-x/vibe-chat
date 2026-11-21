import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { randomBytes, randomUUID, scrypt as scryptCallback, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createDashScopeTranscriber, createTranscriptionQueue } from './transcription.mjs';

const serverVersion = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;
const scrypt = promisify(scryptCallback);
const token = () => randomBytes(32).toString('base64url');
const hash = v => createHash('sha256').update(v).digest('hex');
const now = () => Date.now();
const DAY = 86400000;
class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
function requireValue(test, status = 400, message = '请求参数不正确') { if (!test) throw new HttpError(status, message); }
function string(value, min, max, name = '内容') {
  requireValue(typeof value === 'string', 400, `${name}不能为空`);
  const s = value.trim(); requireValue(s.length >= min && s.length <= max, 400, `${name}长度应为 ${min}–${max} 个字符`); return s;
}
function integer(v, fallback = 0) {
  if (v == null) return fallback;
  requireValue(/^\d{1,16}$/.test(String(v)) && Number.isSafeInteger(Number(v))); return Number(v);
}
async function jsonBody(req, max = 16384) {
  requireValue((req.headers['content-type'] || '').split(';')[0] === 'application/json', 415, '请发送 JSON');
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; requireValue(size <= max, 413, '请求过大'); chunks.push(chunk); }
  try { const value = JSON.parse(Buffer.concat(chunks)); requireValue(value && typeof value === 'object' && !Array.isArray(value)); return value; }
  catch (e) { if (e instanceof HttpError) throw e; throw new HttpError(400, 'JSON 格式不正确'); }
}

// Bound stored images before any mobile decoder sees them. Original EXIF is stripped by the app.
function validAvatar(b) {
  if (b.length < 100 || b.length > 200000 || b[0] !== 0xff || b[1] !== 0xd8 || b[b.length - 2] !== 0xff || b[b.length - 1] !== 0xd9) return false;
  let i = 2, dimensions = false;
  while (i + 4 < b.length) {
    if (b[i++] !== 0xff) return false;
    while (b[i] === 0xff) i++;
    const marker = b[i++];
    if (marker === 0xda) return dimensions;
    if (marker === 0xd9) return false;
    if (i + 2 > b.length) return false;
    const length = b.readUInt16BE(i); if (length < 2 || i + length > b.length) return false;
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      if (length < 8) return false;
      const h = b.readUInt16BE(i + 3), w = b.readUInt16BE(i + 5);
      if (w < 1 || h < 1 || w > 512 || h > 512) return false;
      dimensions = true;
    }
    i += length;
  }
  return false;
}

export function createVibeServer({ dbPath = './data/vibe.sqlite', publicOrigin = 'https://vibe.invalid', trustProxy = false, testMode = false, maxUsers = 100, transcribe = null, asrDailySeconds = 3600, asrMonthlySeconds = 108000, asrPollMs = 1000, asrRetryDelayMs = 10000, asrProviderPauseMs = 60000 } = {}) {
  const origin = new URL(publicOrigin);
  requireValue(origin.protocol === 'https:' || (testMode && origin.hostname === '127.0.0.1'), 500, '必须配置 HTTPS 公网地址');
  requireValue(origin.pathname === '/' && !origin.search && !origin.hash && !origin.username && !origin.password, 500, '公网地址必须是 HTTPS 域名');
  if (dbPath !== ':memory:') mkdirSync(dirname(resolve(dbPath)), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(dbPath);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, salt TEXT NOT NULL, password BLOB NOT NULL, created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS friends(a TEXT NOT NULL REFERENCES users(id), b TEXT NOT NULL REFERENCES users(id), PRIMARY KEY(a,b), CHECK(a<b));
    CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY, sender TEXT NOT NULL REFERENCES users(id), recipient TEXT NOT NULL REFERENCES users(id), status TEXT NOT NULL, created INTEGER NOT NULL, UNIQUE(sender,recipient));
    CREATE TABLE IF NOT EXISTS chats(id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, owner TEXT NOT NULL REFERENCES users(id), direct_key TEXT UNIQUE, created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS members(chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id), joined INTEGER NOT NULL, read_id INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat_id,user_id));
    CREATE TABLE IF NOT EXISTS invites(hash TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), chat_id TEXT REFERENCES chats(id) ON DELETE CASCADE, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, client_id TEXT NOT NULL, chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE, sender TEXT NOT NULL REFERENCES users(id), kind TEXT NOT NULL, text TEXT, audio BLOB, seconds INTEGER, created INTEGER NOT NULL, UNIQUE(sender,client_id));
    CREATE INDEX IF NOT EXISTS messages_chat ON messages(chat_id,id);
    CREATE INDEX IF NOT EXISTS messages_sender_created ON messages(sender,created);
    CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id), kind TEXT NOT NULL, chat_id TEXT, message_id INTEGER, created INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS events_user ON events(user_id,id);
    CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires);
    CREATE TABLE IF NOT EXISTS friend_notes(owner TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, friend_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, remark TEXT NOT NULL, PRIMARY KEY(owner,friend_id));
    CREATE TABLE IF NOT EXISTS avatars(user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, image BLOB NOT NULL, version TEXT NOT NULL, updated INTEGER NOT NULL);
    PRAGMA max_page_count=524288;
    PRAGMA user_version=3;`);
  const one = (sql, ...args) => db.prepare(sql).get(...args);
  const all = (sql, ...args) => db.prepare(sql).all(...args);
  const run = (sql, ...args) => db.prepare(sql).run(...args);
  const tx = fn => { db.exec('BEGIN IMMEDIATE'); try { const result = fn(); db.exec('COMMIT'); return result; } catch (e) { db.exec('ROLLBACK'); throw e; } };
  const limits = new Map(); let passwordJobs = 0;
  async function derive(password, salt) {
    requireValue(passwordJobs < 8, 503, '登录服务繁忙，请稍后重试'); passwordJobs++;
    try { return await scrypt(password, salt, 64, { N: 32768, maxmem: 64 * 1024 * 1024 }); } finally { passwordJobs--; }
  }
  // joined stores the last global message ID at admission, avoiding millisecond boundary leaks.
  const admission = () => one('SELECT COALESCE(MAX(id),0) AS n FROM messages').n;
  function limit(key, count, window = 60000) {
    let v = limits.get(key); if (!v || v.until <= now()) { v = { n: 0, until: now() + window }; limits.set(key, v); }
    requireValue(++v.n <= count, 429, '操作太频繁，请稍后再试');
  }
  const avatarColumn = "COALESCE((SELECT version FROM avatars WHERE user_id=u.id),'') AS avatar_version";
  const publicUser = id => one(`SELECT id,username,name,${avatarColumn} FROM users u WHERE id=?`, id);
  const pair = (a, b) => [a, b].sort();
  const friends = (a, b) => !!one('SELECT 1 FROM friends WHERE a=? AND b=?', ...pair(a, b));
  function member(uid, cid) { const c = one('SELECT c.*,m.joined,m.read_id FROM chats c JOIN members m ON m.chat_id=c.id WHERE c.id=? AND m.user_id=?', cid, uid); requireValue(c, 403, '你不是这个会话的成员'); return c; }
  function event(uid, kind, cid = null, mid = null) { run('INSERT INTO events(user_id,kind,chat_id,message_id,created) VALUES(?,?,?,?,?)', uid, kind, cid, mid, now()); }
  function chatEvent(cid, kind, mid = null) { for (const m of all('SELECT user_id FROM members WHERE chat_id=?', cid)) event(m.user_id, kind, cid, mid); }
  const transcription = createTranscriptionQueue({ db, transcribe, dailySeconds: asrDailySeconds, monthlySeconds: asrMonthlySeconds, pollMs: asrPollMs, retryDelayMs: asrRetryDelayMs, providerPauseMs: asrProviderPauseMs,
    onUpdate(mid) { const m = one('SELECT chat_id FROM messages WHERE id=?', mid); if (m) chatEvent(m.chat_id, 'transcript', mid); } });
  function profileChanged(uid) {
    const recipients = all('SELECT ? AS id UNION SELECT CASE WHEN a=? THEN b ELSE a END FROM friends WHERE a=? OR b=? UNION SELECT m2.user_id FROM members m JOIN members m2 ON m.chat_id=m2.chat_id WHERE m.user_id=?', uid, uid, uid, uid, uid);
    for (const r of recipients) event(r.id, 'state');
  }
  function auth(req) {
    const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '');
    const user = match && one('SELECT u.id,u.username,u.name FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.hash=? AND s.expires>?', hash(match[1]), now());
    requireValue(user, 401, '登录已失效，请重新登录'); return user;
  }
  function session(uid) { const t = token(); run('INSERT INTO sessions VALUES(?,?,?)', hash(t), uid, now() + 30 * DAY); return { token: t, user: publicUser(uid), expires: now() + 30 * DAY }; }
  function previewInvite(raw) {
    requireValue(typeof raw === 'string' && /^[A-Za-z0-9_-]{43}$/.test(raw), 400, '二维码不完整');
    const inv = one('SELECT * FROM invites WHERE hash=? AND expires>?', hash(raw), now()); requireValue(inv, 404, '二维码已过期或已被重置');
    if (inv.chat_id) { const c = one('SELECT id,kind,name FROM chats WHERE id=?', inv.chat_id); requireValue(c, 404, '会话已解散'); return { kind: c.kind, chat: c, expires: inv.expires, owner: inv.owner }; }
    return { kind: 'friend', user: publicUser(inv.owner), expires: inv.expires, owner: inv.owner };
  }
  const messageSelect = `SELECT m.id,m.client_id,m.chat_id,m.sender,m.kind,m.text,m.seconds,m.created,u.name AS sender_name,${avatarColumn},COALESCE(t.status,'unavailable') AS transcript_status,COALESCE(t.transcript,'') AS transcript,COALESCE(t.attempts,0) AS transcript_attempts FROM messages m JOIN users u ON u.id=m.sender LEFT JOIN transcripts t ON t.message_id=m.id`;
  function visibleMessage(uid, id) {
    const m = one(`${messageSelect} JOIN members cm ON cm.chat_id=m.chat_id WHERE m.id=? AND cm.user_id=? AND m.id>cm.joined`, id, uid);
    requireValue(m, 404, '消息不存在或你无权访问'); return m;
  }
  function state(uid) {
    return {
      user: publicUser(uid),
      friends: all(`SELECT u.id,u.username,u.name,${avatarColumn},COALESCE((SELECT remark FROM friend_notes WHERE owner=? AND friend_id=u.id),'') AS remark FROM friends f JOIN users u ON u.id=CASE WHEN f.a=? THEN f.b ELSE f.a END WHERE f.a=? OR f.b=? ORDER BY u.name`, uid, uid, uid, uid),
      requests: all("SELECT r.id,r.sender,r.recipient,r.created,u.name,u.username FROM requests r JOIN users u ON u.id=r.sender WHERE r.recipient=? AND r.status='pending' ORDER BY r.created DESC", uid),
      chats: all(`SELECT c.id,c.kind,CASE WHEN c.kind='direct' THEN (SELECT u.name FROM members m2 JOIN users u ON u.id=m2.user_id WHERE m2.chat_id=c.id AND m2.user_id<>? LIMIT 1) ELSE c.name END AS name,c.owner,
        (SELECT user_id FROM members WHERE chat_id=c.id AND user_id<>? LIMIT 1) AS peer_id,
        (SELECT COUNT(*) FROM members WHERE chat_id=c.id) AS member_count,
        (SELECT COUNT(*) FROM messages WHERE chat_id=c.id AND id>cm.read_id AND sender<>? AND id>cm.joined) AS unread,
        (SELECT substr(CASE WHEN m3.kind='voice' THEN t3.transcript ELSE m3.text END,1,80) FROM messages m3 LEFT JOIN transcripts t3 ON t3.message_id=m3.id WHERE m3.chat_id=c.id AND m3.id>cm.joined ORDER BY m3.id DESC LIMIT 1) AS last_preview,
        (SELECT kind FROM messages WHERE chat_id=c.id AND id>cm.joined ORDER BY id DESC LIMIT 1) AS last_kind,
        COALESCE((SELECT MAX(created) FROM messages WHERE chat_id=c.id),c.created) AS updated
        FROM chats c JOIN members cm ON cm.chat_id=c.id WHERE cm.user_id=? ORDER BY updated DESC`, uid, uid, uid, uid).map(c => {
          if (c.kind === 'direct') { c.original_name = c.name; c.name = one('SELECT remark FROM friend_notes WHERE owner=? AND friend_id=?', uid, c.peer_id)?.remark || c.name; c.avatar_version = publicUser(c.peer_id)?.avatar_version || ''; }
          else c.peer_id = null;
          return c;
        }),
      cursor: one('SELECT COALESCE(MAX(id),0) AS n FROM events WHERE user_id=?', uid).n
    };
  }
  const server = createServer(async (req, res) => {
    const send = (status, value, type = 'application/json; charset=utf-8') => {
      if (res.destroyed) return;
      res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
      res.end(Buffer.isBuffer(value) || typeof value === 'string' ? value : JSON.stringify(value));
    };
    try {
      const url = new URL(req.url, 'http://localhost'); const path = url.pathname; const method = req.method;
      const ip = trustProxy ? (req.headers['x-real-ip'] || req.socket.remoteAddress) : req.socket.remoteAddress;
      limit(`ip:${ip}`, testMode ? 10000 : 6000);
      if (req.headers.origin) requireValue(req.headers.origin === origin.origin, 403, '不允许跨站请求');
      if (path === '/health' && method === 'GET') return send(200, { ok: true, version: serverVersion, transcription: transcription.enabled ? 'enabled' : 'not_configured' });
      if (path === '/' && method === 'GET') return send(200, '<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Vibe Chat</title><body style="font:18px system-ui;background:#f2f2f7;color:#1c1c1e;padding:12vw"><h1>Vibe Chat</h1><p>耳机里听见朋友，按住就能回应。</p><p><a style="display:inline-block;padding:16px 24px;background:#e8f2ff;color:#1c1c1e;border-radius:16px;text-decoration:none" href="/downloads/">下载 Android App</a></p><p>Android 8.0 及以上。安装后创建自己的账号，扫一扫添加好友或加入群聊。</p><p>开启对讲后，连接耳机即可自动播放新语音。悬浮按钮支持按住回复。</p></body></html>', 'text/html; charset=utf-8');
      if ((path === '/v1/register' || path === '/v1/login') && method === 'POST') {
        limit(`auth:${ip}`, testMode ? 10000 : 120, 15 * 60000);
        const b = await jsonBody(req); const username = string(b.username, 3, 32, '账号').toLowerCase();
        limit(`auth-user:${username}`, testMode ? 10000 : 10, 15 * 60000);
        requireValue(/^[a-z0-9_]{3,32}$/.test(username), 400, '账号使用 3–32 位字母、数字或下划线');
        requireValue(typeof b.password === 'string' && b.password.length >= 10 && b.password.length <= 128, 400, '密码使用 10–128 个字符');
        if (path === '/v1/register') {
          const name = string(b.name, 1, 20, '昵称');
          requireValue(one('SELECT COUNT(*) AS n FROM users').n < maxUsers, 409, '试用名额已满');
          requireValue(!one('SELECT id FROM users WHERE username=?', username), 409, '账号已被使用');
          const salt = token(); const password = await derive(b.password, salt);
          const id = randomUUID();
          tx(() => { requireValue(one('SELECT COUNT(*) AS n FROM users').n < maxUsers, 409, '试用名额已满'); requireValue(!one('SELECT id FROM users WHERE username=?', username), 409, '账号已被使用'); run('INSERT INTO users VALUES(?,?,?,?,?,?)', id, username, name, salt, password, now()); });
          return send(201, session(id));
        }
        const u = one('SELECT * FROM users WHERE username=?', username);
        const actual = await derive(b.password, u?.salt || 'vibe-nonexistent-user');
        requireValue(u && timingSafeEqual(actual, Buffer.from(u.password)), 401, '账号或密码不正确');
        return send(200, session(u.id));
      }
      const user = auth(req); const uid = user.id;
      limit(`user:${uid}`, testMode ? 10000 : 180);
      if (path === '/v1/logout' && method === 'POST') { run('DELETE FROM sessions WHERE hash=?', hash(req.headers.authorization.slice(7))); return send(200, { ok: true }); }
      if (path === '/v1/state' && method === 'GET') return send(200, state(uid));
      if (path === '/v1/profile' && method === 'PATCH') { const b = await jsonBody(req); run('UPDATE users SET name=? WHERE id=?', string(b.name, 1, 20, '昵称'), uid); profileChanged(uid); return send(200, { user: publicUser(uid) }); }
      if (path === '/v1/profile/avatar' && method === 'PUT') {
        limit(`avatar:${uid}`, 10); const b = await jsonBody(req, 550000);
        requireValue(typeof b.image === 'string' && /^[A-Za-z0-9+/]+={0,2}$/.test(b.image), 400, '请选择 JPEG 图片');
        const image = Buffer.from(b.image, 'base64');
        requireValue(validAvatar(image), 400, '头像需为不超过 200 KB、512×512 像素以内的 JPEG 图片');
        const version = hash(image).slice(0, 24);
        run('INSERT INTO avatars VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET image=excluded.image,version=excluded.version,updated=excluded.updated', uid, image, version, now());
        profileChanged(uid); return send(200, { user: publicUser(uid) });
      }
      if (path === '/v1/profile/avatar' && method === 'DELETE') { run('DELETE FROM avatars WHERE user_id=?', uid); profileChanged(uid); return send(200, { user: publicUser(uid) }); }
      const avatarMatch = /^\/v1\/users\/([a-f0-9-]+)\/avatar$/.exec(path);
      if (avatarMatch && method === 'GET') {
        const target = avatarMatch[1];
        const visible = uid === target || friends(uid, target) || one('SELECT 1 FROM members a JOIN members b ON a.chat_id=b.chat_id WHERE a.user_id=? AND b.user_id=?', uid, target) || one("SELECT 1 FROM requests WHERE ((sender=? AND recipient=?) OR (sender=? AND recipient=?)) AND status='pending'", uid, target, target, uid);
        requireValue(visible, 404, '头像不可用'); const avatar = one('SELECT image,version FROM avatars WHERE user_id=?', target); requireValue(avatar && (!url.searchParams.has('v') || url.searchParams.get('v') === avatar.version), 404, '头像已更新或不可用');
        return send(200, Buffer.from(avatar.image), 'image/jpeg');
      }
      const noteMatch = /^\/v1\/friends\/([a-f0-9-]+)\/remark$/.exec(path);
      if (noteMatch && method === 'PUT') {
        const target = noteMatch[1]; requireValue(friends(uid, target), 403, '只能为好友设置备注'); const b = await jsonBody(req); requireValue(typeof b.remark === 'string' && [...b.remark.trim()].length <= 40, 400, '备注最多 40 字'); const remark = b.remark.trim();
        if (remark) run('INSERT INTO friend_notes VALUES(?,?,?) ON CONFLICT(owner,friend_id) DO UPDATE SET remark=excluded.remark', uid, target, remark);
        else run('DELETE FROM friend_notes WHERE owner=? AND friend_id=?', uid, target);
        event(uid, 'state'); return send(200, { ok: true, remark });
      }
      if (path === '/v1/password' && method === 'POST') {
        limit(`password:${uid}`, 5, 15 * 60000); const b = await jsonBody(req); const u = one('SELECT * FROM users WHERE id=?', uid);
        requireValue(typeof b.old_password === 'string' && b.old_password.length <= 128 && typeof b.password === 'string' && b.password.length >= 10 && b.password.length <= 128);
        const old = await derive(b.old_password, u.salt); requireValue(timingSafeEqual(old, Buffer.from(u.password)), 403, '原密码不正确');
        const salt = token(); const password = await derive(b.password, salt);
        tx(() => { run('UPDATE users SET salt=?,password=? WHERE id=?', salt, password, uid); run('DELETE FROM sessions WHERE user_id=?', uid); }); return send(200, session(uid));
      }
      if (path === '/v1/invites' && method === 'POST') {
        limit(`invite:${uid}`, 20); const b = await jsonBody(req); const cid = b.chat_id || null;
        if (cid) { const c = member(uid, cid); requireValue(c.kind !== 'direct' && c.owner === uid, 403, '只有群主或房主能生成邀请'); }
        const t = token(); const expires = now() + (cid ? 7 : 30) * DAY;
        tx(() => { run('DELETE FROM invites WHERE owner=? AND chat_id IS ?', uid, cid); run('INSERT INTO invites VALUES(?,?,?,?)', hash(t), uid, cid, expires); });
        return send(201, { code: `vibe2|${origin.origin}|${t}`, token: t, expires });
      }
      if (path === '/v1/invites/preview' && method === 'POST') { const b = await jsonBody(req); const p = previewInvite(b.token); delete p.owner; return send(200, p); }
      if (path === '/v1/invites/accept' && method === 'POST') {
        limit(`join:${uid}`, 20); const b = await jsonBody(req); const p = previewInvite(b.token);
        if (p.kind === 'friend') {
          requireValue(p.owner !== uid, 400, '这是你自己的二维码');
          if (friends(uid, p.owner)) return send(200, { status: 'friends' });
          tx(() => { run("INSERT INTO requests VALUES(?,?,?,'pending',?) ON CONFLICT(sender,recipient) DO UPDATE SET status='pending',created=excluded.created", randomUUID(), uid, p.owner, now()); event(p.owner, 'state'); });
          return send(200, { status: 'pending' });
        }
        tx(() => { const exists = one('SELECT 1 FROM members WHERE chat_id=? AND user_id=?', p.chat.id, uid); requireValue(exists || one('SELECT COUNT(*) AS n FROM members WHERE chat_id=?', p.chat.id).n < 50, 409, '成员已满'); run('INSERT OR IGNORE INTO members(chat_id,user_id,joined) VALUES(?,?,?)', p.chat.id, uid, admission()); chatEvent(p.chat.id, 'state'); });
        return send(200, { chat_id: p.chat.id });
      }
      let match = /^\/v1\/requests\/([a-f0-9-]+)$/.exec(path);
      if (match && method === 'POST') {
        const b = await jsonBody(req); requireValue(b.accept === true || b.accept === false);
        tx(() => { const r = one("SELECT * FROM requests WHERE id=? AND recipient=? AND status='pending'", match[1], uid); requireValue(r, 404, '申请已处理或不存在'); run('UPDATE requests SET status=? WHERE id=?', b.accept ? 'accepted' : 'declined', r.id); if (b.accept) { run('INSERT OR IGNORE INTO friends VALUES(?,?)', ...pair(uid, r.sender)); run("UPDATE requests SET status='accepted' WHERE sender=? AND recipient=? AND status='pending'", uid, r.sender); } event(uid, 'state'); event(r.sender, 'state'); });
        return send(200, { ok: true });
      }
      if (path === '/v1/chats' && method === 'POST') {
        limit(`chat:${uid}`, 20); const b = await jsonBody(req); requireValue(['direct', 'group', 'room'].includes(b.kind));
        const cid = tx(() => {
          let key = null; let name = '';
          if (b.kind === 'direct') { requireValue(typeof b.friend_id === 'string' && friends(uid, b.friend_id), 403, '请先互加好友'); key = pair(uid, b.friend_id).join(':'); const c = one('SELECT id FROM chats WHERE direct_key=?', key); if (c) return c.id; }
          else name = string(b.name, 1, 40, '名称');
          requireValue(one('SELECT COUNT(*) AS n FROM members WHERE user_id=?', uid).n < 100, 409, '会话数量已达上限');
          const id = randomUUID(); const created = now(); run('INSERT INTO chats VALUES(?,?,?,?,?,?)', id, b.kind, name, uid, key, created);
          const joined = admission(); run('INSERT INTO members(chat_id,user_id,joined) VALUES(?,?,?)', id, uid, joined);
          if (b.kind === 'direct') run('INSERT INTO members(chat_id,user_id,joined) VALUES(?,?,?)', id, b.friend_id, joined);
          chatEvent(id, 'state');
          return id;
        });
        return send(201, { chat_id: cid });
      }
      match = /^\/v1\/chats\/([a-f0-9-]+)(?:\/(messages|read|members|leave))?$/.exec(path);
      if (match) {
        const cid = match[1]; const action = match[2]; const c = member(uid, cid);
        if (!action && method === 'GET') return send(200, { chat: c, members: all('SELECT u.id,u.username,u.name FROM members m JOIN users u ON u.id=m.user_id WHERE m.chat_id=? ORDER BY m.joined', cid) });
        if (action === 'messages' && method === 'GET') {
          const before = integer(url.searchParams.get('before'), Number.MAX_SAFE_INTEGER);
          const query = `${messageSelect} WHERE m.chat_id=? AND m.id<? AND m.id>? ORDER BY m.id DESC LIMIT 50`;
          // Backfill only voice messages actually opened, bounded by the same queue and spending cap.
          for (const m of all(query, cid, before, c.joined)) if (m.kind === 'voice') transcription.ensure(m.id);
          return send(200, { messages: all(query, cid, before, c.joined).reverse() });
        }
        if (action === 'messages' && method === 'POST') {
          // Android JSONObject escapes '/' in Base64; 30 s can occupy 2.56 MB on the wire.
          limit(`send:${uid}`, testMode ? 10000 : 60); const b = await jsonBody(req, 3000000); requireValue(typeof b.client_id === 'string' && /^[a-f0-9-]{36}$/.test(b.client_id));
          requireValue(['text', 'voice'].includes(b.kind)); let audio = null, body = null, seconds = null;
          if (b.kind === 'text') body = string(b.text, 1, 2000, '消息');
          else { requireValue(typeof b.audio === 'string' && /^[A-Za-z0-9+/]*={0,2}$/.test(b.audio)); audio = Buffer.from(b.audio, 'base64'); requireValue(audio.length >= 16000 && audio.length <= 960000 && audio.length % 2 === 0, 400, '语音应为 0.5–30 秒的 16kHz 单声道 PCM'); seconds = Math.ceil(audio.length / 32000); }
          const result = tx(() => {
            member(uid, cid);
            const old = one('SELECT id,chat_id FROM messages WHERE sender=? AND client_id=?', uid, b.client_id); if (old) { requireValue(old.chat_id === cid, 409, '该消息编号已用于其他会话'); return visibleMessage(uid, old.id); }
            requireValue(one('SELECT COUNT(*) AS n FROM messages WHERE sender=? AND created>?', uid, now() - DAY).n < 2000, 429, '今日发送数量已达上限');
            const r = run('INSERT INTO messages(client_id,chat_id,sender,kind,text,audio,seconds,created) VALUES(?,?,?,?,?,?,?,?)', b.client_id, cid, uid, b.kind, body, audio, seconds, now()); const mid = Number(r.lastInsertRowid); if (b.kind === 'voice') transcription.ensure(mid); chatEvent(cid, 'message', mid); return visibleMessage(uid, mid);
          }); return send(201, { message: result });
        }
        if (action === 'read' && method === 'POST') { const b = await jsonBody(req); const mid = integer(b.message_id); const m = visibleMessage(uid, mid); requireValue(m.chat_id === cid); run('UPDATE members SET read_id=MAX(read_id,?) WHERE chat_id=? AND user_id=?', mid, cid, uid); return send(200, { ok: true }); }
        if (action === 'members' && method === 'POST') {
          const b = await jsonBody(req); requireValue(c.kind !== 'direct' && c.owner === uid, 403, '只有群主或房主可以拉人');
          requireValue(typeof b.friend_id === 'string' && friends(uid, b.friend_id), 403, '只能邀请你的好友');
          tx(() => { member(uid, cid); requireValue(one('SELECT COUNT(*) AS n FROM members WHERE chat_id=?', cid).n < 50, 409, '成员已满'); run('INSERT OR IGNORE INTO members(chat_id,user_id,joined) VALUES(?,?,?)', cid, b.friend_id, admission()); chatEvent(cid, 'state'); });
          return send(200, { ok: true });
        }
        if (action === 'leave' && method === 'POST') { requireValue(c.kind !== 'direct' && c.owner !== uid, 400, '群主请先解散会话'); tx(() => { run('DELETE FROM members WHERE chat_id=? AND user_id=?', cid, uid); event(uid, 'state'); chatEvent(cid, 'state'); }); return send(200, { ok: true }); }
        if (!action && method === 'DELETE') { requireValue(c.owner === uid && c.kind !== 'direct', 403, '只有群主或房主能解散'); tx(() => { chatEvent(cid, 'state'); run('DELETE FROM chats WHERE id=?', cid); }); return send(200, { ok: true }); }
      }
      match = /^\/v1\/messages\/(\d+)\/transcription$/.exec(path);
      if (match && method === 'POST') {
        const m = visibleMessage(uid, integer(match[1])); requireValue(m.kind === 'voice', 400, '只有语音消息可以转文字');
        limit(`asr-retry:${uid}`, 10);
        requireValue(transcription.enabled, 503, '语音转文字服务暂不可用，仍可正常收听');
        transcription.retry(m.id);
        return send(200, { message: visibleMessage(uid, m.id) });
      }
      match = /^\/v1\/audio\/(\d+)$/.exec(path);
      if (match && method === 'GET') { const m = visibleMessage(uid, integer(match[1])); requireValue(m.kind === 'voice', 404, '不是语音消息'); return send(200, Buffer.from(one('SELECT audio FROM messages WHERE id=?', m.id).audio), 'application/octet-stream'); }
      if (path === '/v1/events' && method === 'GET') {
        const after = integer(url.searchParams.get('after'));
        const rows = all('SELECT id,kind,chat_id,message_id FROM events WHERE user_id=? AND id>? ORDER BY id LIMIT 100', uid, after);
        const events = rows.map(e => {
          if (e.kind !== 'message' && e.kind !== 'transcript') return e;
          try { const m = visibleMessage(uid, e.message_id); return e.kind === 'message' ? { ...e, message: m } : { ...e, transcript: { message_id: m.id, status: m.transcript_status } }; }
          catch { return { id: e.id, kind: 'state' }; }
        });
        return send(200, { events, cursor: rows.length ? rows.at(-1).id : after, more: rows.length === 100 });
      }
      throw new HttpError(404, '接口不存在');
    } catch (e) { if (!(e instanceof HttpError)) console.error('Request failed:', e.code || e.name); send(e.status || 500, { error: e.status ? e.message : '服务暂时不可用，请稍后重试' }); }
  });
  server.requestTimeout = 30000; server.headersTimeout = 10000; server.maxHeadersCount = 50;
  const cleanup = setInterval(() => { for (const [k, v] of limits) if (v.until < now()) limits.delete(k); try { run('DELETE FROM sessions WHERE expires<?', now()); run('DELETE FROM invites WHERE expires<?', now()); run('DELETE FROM messages WHERE created<?', now() - 30 * DAY); run('DELETE FROM events WHERE created<?', now() - 30 * DAY); } catch (e) { console.error('Cleanup failed:', e.code || e.name); } }, 60000); cleanup.unref();
  server.on('close', () => { clearInterval(cleanup); transcription.stop(); db.close(); });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (!process.env.PUBLIC_ORIGIN) throw new Error('Set PUBLIC_ORIGIN to the public HTTPS origin before starting.');
  const transcribe = process.env.ASR_API_KEY ? createDashScopeTranscriber({ apiKey: process.env.ASR_API_KEY, endpoint: process.env.ASR_ENDPOINT, model: process.env.ASR_MODEL || 'qwen3-asr-flash' }) : null;
  const server = createVibeServer({ dbPath: process.env.DB_PATH || './data/vibe.sqlite', publicOrigin: process.env.PUBLIC_ORIGIN, trustProxy: process.env.TRUST_PROXY === '1', maxUsers: Number(process.env.MAX_USERS || 100), transcribe,
    asrDailySeconds: Math.max(0, Number(process.env.ASR_DAILY_SECONDS || 3600)), asrMonthlySeconds: Math.max(0, Number(process.env.ASR_MONTHLY_SECONDS || 108000)) });
  server.listen(Number(process.env.PORT || 8080), process.env.HOST || '127.0.0.1', () => console.log('Vibe Chat server listening.'));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { server.close(); setTimeout(() => process.exit(0), 10000).unref(); });
}

/** One server-side transcription per voice message; audio delivery never waits for ASR. */
export class AsrError extends Error {
  constructor(code, retryable = false) { super(code); this.code = code; this.retryable = retryable; }
}
export function pcmToWav(pcm) {
  if (!Buffer.isBuffer(pcm) || pcm.length < 16000 || pcm.length > 960000 || pcm.length % 2) throw new AsrError('INVALID_AUDIO');
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(16000, 24); h.writeUInt32LE(32000, 28);
  h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}
export function createDashScopeTranscriber({ apiKey, endpoint, model = 'qwen3-asr-flash', fetchImpl = fetch, timeoutMs = 45000 }) {
  const url = new URL(endpoint || 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions');
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.port ||
      !/^(dashscope\.aliyuncs\.com|[a-zA-Z0-9-]+\.cn-beijing\.maas\.aliyuncs\.com)$/.test(url.hostname) ||
      url.pathname !== '/compatible-mode/v1/chat/completions' || !apiKey || /[\r\n]/.test(apiKey)) throw new AsrError('INVALID_CONFIG');
  const recognize = async (pcm, { signal } = {}) => {
    const body = { model, messages: [{ role: 'user', content: [{ type: 'input_audio', input_audio: { data: 'data:audio/wav;base64,' + pcmToWav(pcm).toString('base64') } }] }], stream: false, asr_options: { enable_itn: true } };
    try {
      const response = await fetchImpl(url.href, { method: 'POST', redirect: 'error', headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs) });
      if (!response.ok) {
        if (response.status === 400) {
          const chunks = []; let size = 0;
          for await (const c of response.body) { size += c.length; if (size > 16384) break; chunks.push(c); }
          const error = Buffer.concat(chunks).toString('utf8');
          if (size <= 16384 && error.includes('ASR_RESPONSE_HAVE_NO_WORDS')) return '';
        } else await response.body?.cancel();
        throw new AsrError(response.status === 401 || response.status === 403 ? 'AUTH' : response.status === 402 ? 'BALANCE' : `HTTP_${response.status}`, response.status === 429 || response.status >= 500);
      }
      const chunks = []; let bytes = 0;
      for await (const chunk of response.body) { bytes += chunk.length; if (bytes > 131072) throw new AsrError('INVALID_RESPONSE'); chunks.push(chunk); }
      const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      const text = result?.choices?.[0]?.message?.content;
      if (typeof text !== 'string' || text.length > 6000) throw new AsrError('INVALID_RESPONSE');
      return text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').trim();
    } catch (e) { if (e instanceof AsrError) throw e; throw new AsrError('NETWORK', true); }
  };
  recognize.model = model;
  return recognize;
}

export function createTranscriptionQueue({ db, transcribe = null, onUpdate = () => {}, dailySeconds = 3600, monthlySeconds = 108000, clock = Date.now, pollMs = 1000, retryDelayMs = 10000, providerPauseMs = 60000 }) {
  if (![dailySeconds, monthlySeconds].every(n => Number.isFinite(n) && n >= 0)) throw new AsrError('INVALID_BUDGET');
  const run = (sql, ...args) => db.prepare(sql).run(...args);
  const one = (sql, ...args) => db.prepare(sql).get(...args);
  db.exec(`CREATE TABLE IF NOT EXISTS transcripts(
    message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    status TEXT NOT NULL, transcript TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, error_code TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS transcripts_work ON transcripts(status,next_attempt);
    CREATE TABLE IF NOT EXISTS asr_usage(day TEXT PRIMARY KEY, seconds INTEGER NOT NULL, requests INTEGER NOT NULL);`);
  // A stopped process may have completed a provider request without saving its reply.
  // Keep the attempt/budget reservation; never restart an unbounded number of calls.
  run("UPDATE transcripts SET status=CASE WHEN attempts<3 THEN 'pending' ELSE 'failed' END WHERE status='processing'");
  if (transcribe) run("UPDATE transcripts SET status='pending',next_attempt=0 WHERE status='unavailable' OR (status='failed' AND error_code IN ('AUTH','BALANCE') AND attempts<3)");
  let busy = false, stopped = false, wakeTimer = null, current = null, blockedUntil = 0;
  const enabled = !!transcribe;
  const dayKey = () => new Date(clock() + 8 * 3600000).toISOString().slice(0, 10);
  function wake() { if (!stopped && !wakeTimer) wakeTimer = setTimeout(() => { wakeTimer = null; void pump(); }, 0); }
  function ensure(id) {
    const r = run("INSERT OR IGNORE INTO transcripts(message_id,status,updated) SELECT id,?,? FROM messages WHERE id=? AND kind='voice'", enabled ? 'pending' : 'unavailable', clock(), id);
    if (r.changes) wake();
    return one('SELECT status,transcript,attempts FROM transcripts WHERE message_id=?', id);
  }
  function retry(id) {
    const t = ensure(id);
    if (!t || !enabled || t.status === 'ready' || t.status === 'empty' || t.status === 'pending' || t.status === 'processing' || t.status === 'budget' || t.attempts >= 3) return t;
    run("UPDATE transcripts SET status='pending',next_attempt=0,error_code='',updated=? WHERE message_id=?", clock(), id);
    onUpdate(id); wake();
    return one('SELECT status,transcript,attempts FROM transcripts WHERE message_id=?', id);
  }
  async function pump() {
    if (busy || stopped || !enabled || clock() < blockedUntil) return;
    const job = one("SELECT t.*,m.audio,m.seconds FROM transcripts t JOIN messages m ON m.id=t.message_id WHERE t.status IN ('pending','budget') AND t.next_attempt<=? ORDER BY t.message_id LIMIT 1", clock());
    if (!job) return;
    busy = true;
    try {
      if (job.attempts >= 3) { run("UPDATE transcripts SET status='failed',error_code='ATTEMPT_LIMIT',updated=? WHERE message_id=?", clock(), job.message_id); onUpdate(job.message_id); return; }
      const pcm = Buffer.from(job.audio);
      // Digital silence is common with unavailable microphones; do not invent a transcript or bill it.
      if (pcm.every(byte => byte === 0)) { run("UPDATE transcripts SET status='empty',updated=? WHERE message_id=?", clock(), job.message_id); onUpdate(job.message_id); return; }
      const day = dayKey(), month = day.slice(0, 7);
      const used = one('SELECT seconds FROM asr_usage WHERE day=?', day)?.seconds || 0;
      const monthly = one('SELECT COALESCE(SUM(seconds),0) AS seconds FROM asr_usage WHERE substr(day,1,7)=?', month).seconds;
      if (used + job.seconds > dailySeconds || monthly + job.seconds > monthlySeconds) {
        run("UPDATE transcripts SET status='budget',next_attempt=?,updated=? WHERE message_id=?", clock() + 60000, clock(), job.message_id);
        if (job.status !== 'budget') onUpdate(job.message_id);
        return;
      }
      run("INSERT INTO asr_usage(day,seconds,requests) VALUES(?,?,1) ON CONFLICT(day) DO UPDATE SET seconds=seconds+excluded.seconds,requests=requests+1", day, job.seconds);
      run("UPDATE transcripts SET status='processing',attempts=attempts+1,model=?,updated=? WHERE message_id=?", transcribe.model || 'test', clock(), job.message_id);
      current = new AbortController();
      try {
        const text = await transcribe(pcm, { signal: current.signal });
        if (stopped) return;
        if (typeof text !== 'string' || text.length > 6000) throw new AsrError('INVALID_RESPONSE');
        run("UPDATE transcripts SET status=?,transcript=?,error_code='',updated=? WHERE message_id=?", text.trim() ? 'ready' : 'empty', text.trim(), clock(), job.message_id);
      } catch (e) {
        if (stopped) return;
        const retrying = e instanceof AsrError && e.retryable && job.attempts + 1 < 2;
        const code = e instanceof AsrError ? e.code : 'PROVIDER';
        if (code === 'AUTH' || code === 'BALANCE') blockedUntil = clock() + providerPauseMs;
        run('UPDATE transcripts SET status=?,next_attempt=?,error_code=?,updated=? WHERE message_id=?', retrying ? 'pending' : 'failed', clock() + retryDelayMs, code, clock(), job.message_id);
        console.warn('ASR attempt failed:', code); // Never log audio, transcript, API keys or provider error bodies.
      }
      if (!stopped && one('SELECT 1 FROM transcripts WHERE message_id=?', job.message_id)) onUpdate(job.message_id);
    } catch (e) { if (!stopped) console.warn('ASR queue error:', e.code || e.name); }
    finally { current = null; busy = false; }
  }
  const timer = setInterval(() => { void pump(); }, pollMs); timer.unref(); wake();
  return { enabled, ensure, retry, wake, stop() { stopped = true; clearInterval(timer); clearTimeout(wakeTimer); current?.abort(); } };
}

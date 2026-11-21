import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createVibeServer } from '../src/main.mjs';
import { AsrError, pcmToWav, createDashScopeTranscriber } from '../src/transcription.mjs';
const pcm = Buffer.alloc(32000, 1);
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function until(fn) { for (let i=0;i<150;i++) { const r = await fn(); if (r) return r; await sleep(20); } throw Error('Timed out waiting for transcript'); }
async function fixture(t, extra = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'vibe-asr-'));
  const options = { dbPath: join(dir,'vibe.sqlite'), publicOrigin:'https://vibe.example', testMode:true, asrPollMs:10, asrRetryDelayMs:20, asrProviderPauseMs:0, ...extra };
  let server, base;
  const start = async overrides => { server = createVibeServer({...options,...overrides}); await new Promise(r=>server.listen(0,'127.0.0.1',r)); base='http://127.0.0.1:'+server.address().port; };
  await start();
  const stop = async () => { if (server) { const old=server;server=null;await new Promise(r=>old.close(r)); } };
  t.after(async()=>{await stop();rmSync(dir,{recursive:true});});
  async function api(path,body,who,status=200){const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(who?{Authorization:'Bearer '+who.token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(3000)});const j=await r.json();assert.equal(r.status,status,JSON.stringify(j));return j;}
  const users=[];for (const name of ['alice','bob','eve'])users.push(await api('/v1/register',{username:name,password:'test-password-2026',name},null,201));
  const [alice,bob,eve]=users;
  const cid=(await api('/v1/chats',{kind:'group',name:'语音转写测试'},alice,201)).chat_id;
  const invite=await api('/v1/invites',{chat_id:cid},alice,201);await api('/v1/invites/accept',{token:invite.token},bob);
  const history=async(who=alice)=>(await api('/v1/chats/'+cid+'/messages',undefined,who)).messages;
  const send=async(audio=pcm,client_id=randomUUID())=>(await api('/v1/chats/'+cid+'/messages',{kind:'voice',audio:audio.toString('base64'),client_id},alice,201)).message;
  const audio=async(id,who=bob)=>{const r=await fetch(base+'/v1/audio/'+id,{headers:{Authorization:'Bearer '+who.token}});assert.equal(r.status,200);return Buffer.from(await r.arrayBuffer());};
  const dbEdit=fn=>{const db=new DatabaseSync(options.dbPath);try{return fn(db);}finally{db.close();}};
  return {api,alice,bob,eve,cid,invite,history,send,audio,dbEdit,stop,start};
}

test('provider request uses private WAV data, accepts empty speech, bounds results and rejects credential redirects',async()=>{
  let body;
  const transcribe=createDashScopeTranscriber({apiKey:'test-secret',endpoint:'https://example-workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions',fetchImpl:async(url,opts)=>{
    assert.equal(opts.redirect,'error');assert.equal(opts.headers.Authorization,'Bearer test-secret');body=JSON.parse(opts.body);
    return new Response(JSON.stringify({choices:[{message:{content:' 明天下午三点见。 '}}]}));
  }});
  assert.equal(await transcribe(pcm),'明天下午三点见。');
  assert.equal(body.model,'qwen3-asr-flash');assert.equal(body.asr_options.enable_itn,true);
  const wav=Buffer.from(body.messages[0].content[0].input_audio.data.split(',')[1],'base64');
  assert.equal(wav.subarray(0,4).toString(),'RIFF');assert.equal(wav.readUInt32LE(24),16000);assert.deepEqual(wav.subarray(44),pcm);
  assert.throws(()=>pcmToWav(Buffer.alloc(960002)),/INVALID_AUDIO/);
  assert.throws(()=>createDashScopeTranscriber({apiKey:'secret',endpoint:'https://example.org/compatible-mode/v1/chat/completions'}),/INVALID_CONFIG/);
  const blank=createDashScopeTranscriber({apiKey:'test',fetchImpl:async()=>new Response(JSON.stringify({choices:[{message:{content:''}}]}))});assert.equal(await blank(pcm),'');
  const noSpeech=createDashScopeTranscriber({apiKey:'test',fetchImpl:async()=>new Response(JSON.stringify({error:{code:'ASR_RESPONSE_HAVE_NO_WORDS'}}),{status:400})});assert.equal(await noSpeech(pcm),'');
  const denied=createDashScopeTranscriber({apiKey:'test-secret',fetchImpl:async()=>new Response('sensitive provider message',{status:401})});
  await assert.rejects(denied(pcm),e=>e.code==='AUTH'&&!e.retryable&&!e.message.includes('sensitive'));
  const bad=createDashScopeTranscriber({apiKey:'test',fetchImpl:async()=>new Response(JSON.stringify({choices:[{message:{content:'a'.repeat(6001)}}]}))});await assert.rejects(bad(pcm),/INVALID_RESPONSE/);
});

test('voice delivery is immediate, group transcription is shared once, ACLs and legacy autoplay are preserved',async t=>{
  let release,calls=0;const gate=new Promise(r=>release=r);
  const f=await fixture(t,{transcribe:async()=>{calls++;return gate;}});
  const clientId=randomUUID(),m=await f.send(pcm,clientId);
  assert.equal(m.transcript_status,'pending');await until(()=>calls===1);
  assert.deepEqual(await f.audio(m.id),pcm,'audio stays playable while recognizer is still waiting');
  assert.equal((await f.send(pcm,clientId)).id,m.id);assert.equal(calls,1);
  await f.api('/v1/messages/'+m.id+'/transcription',{},f.eve,404);
  await f.api('/v1/invites/accept',{token:f.invite.token},f.eve);
  release('明天下午三点在公园见。');
  await until(async()=>{const rows=await f.history();return rows[0]?.transcript_status==='ready';});
  assert.equal((await f.history(f.bob))[0].transcript,'明天下午三点在公园见。');
  const events=(await f.api('/v1/events?after=0',undefined,f.bob)).events;
  assert.equal(events.filter(e=>e.kind==='message').length,1);
  const update=events.find(e=>e.kind==='transcript');assert(update);assert(!('message' in update),'legacy clients must never autoplay transcript updates');
  const chat=(await f.api('/v1/state',undefined,f.bob)).chats[0];assert.equal(chat.unread,1);assert.equal(chat.last_preview,'明天下午三点在公园见。');
  assert.equal((await f.history(f.eve)).length,0);assert.equal((await f.api('/v1/state',undefined,f.eve)).chats[0].last_preview,null);
  assert(!(await f.api('/v1/events?after=0',undefined,f.eve)).events.some(e=>e.kind==='transcript'));
  await f.api('/v1/messages/'+m.id+'/transcription',{},f.bob);assert.equal(calls,1);
  await f.api('/v1/chats/'+f.cid+'/leave',{},f.bob);
  await f.api('/v1/messages/'+m.id+'/transcription',{},f.bob,404);
  assert(!(await f.api('/v1/events?after=0',undefined,f.bob)).events.some(e=>e.kind==='message'||e.kind==='transcript'));
  await f.stop();await f.start({transcribe:async()=>{throw Error('must reuse stored text');}});
  assert.equal((await f.history())[0].transcript,'明天下午三点在公园见。');
});

test('temporary ASR failure retries text only, while permanent failures have bounded shared retries',async t=>{
  let calls=0;const f=await fixture(t,{transcribe:async()=>{calls++;if(calls===1)throw new AsrError('HTTP_503',true);return '你好。';}});
  const m=await f.send();await until(async()=>(await f.history())[0].transcript_status==='ready');assert.equal(calls,2);assert.equal((await f.history()).length,1);
  await f.stop();calls=0;await f.start({transcribe:async()=>{calls++;throw new AsrError('AUTH');}});
  const failed=await f.send();await until(async()=>(await f.history()).find(x=>x.id===failed.id)?.transcript_status==='failed');
  for(let i=0;i<4;i++){await Promise.all([f.api('/v1/messages/'+failed.id+'/transcription',{},f.alice),f.api('/v1/messages/'+failed.id+'/transcription',{},f.bob)]);await sleep(40);}
  assert.equal(calls,3);assert.equal((await f.history()).length,2);assert.deepEqual(await f.audio(m.id),pcm);
});

test('missing ASR credentials never fail voice delivery; enabling resumes queued work and skips digital silence',async t=>{
  const f=await fixture(t);const m=await f.send();assert.equal(m.transcript_status,'unavailable');assert.deepEqual(await f.audio(m.id),pcm);
  await f.api('/v1/messages/'+m.id+'/transcription',{},f.alice,503);
  await f.stop();let calls=0;await f.start({transcribe:async()=>{calls++;return '恢复后识别。';}});
  await until(async()=>(await f.history())[0].transcript_status==='ready');assert.equal(calls,1);
  const silent=await f.send(Buffer.alloc(32000));await until(async()=>(await f.history()).find(m=>m.id===silent.id)?.transcript_status==='empty');assert.equal(calls,1);
});

test('ASR spending cap defers text without losing audio and usage survives restart',async t=>{
  let calls=0;const f=await fixture(t,{asrDailySeconds:1,transcribe:async()=>{calls++;return '额度测试。';}});
  await f.send();await until(async()=>(await f.history())[0].transcript_status==='ready');const m=await f.send();
  await until(async()=>(await f.history()).find(x=>x.id===m.id)?.transcript_status==='budget');assert.equal(calls,1);assert.deepEqual(await f.audio(m.id),pcm);
  await f.stop();assert.equal(f.dbEdit(db=>db.prepare('SELECT SUM(seconds) AS n FROM asr_usage').get().n),1);
  await f.start();assert.equal((await f.history()).length,2);assert.equal(calls,1);
});

test('interrupted jobs and opened legacy messages recover without creating duplicate voice messages',async t=>{
  const f=await fixture(t);const m=await f.send();await f.stop();
  f.dbEdit(db=>db.prepare("UPDATE transcripts SET status='processing',attempts=1 WHERE message_id=?").run(m.id));
  let calls=0;await f.start({transcribe:async()=>{calls++;return '重启恢复。';}});
  await until(async()=>(await f.history())[0].transcript_status==='ready');assert.equal(calls,1);assert.equal((await f.history()).length,1);
  await f.stop();f.dbEdit(db=>db.prepare('DELETE FROM transcripts WHERE message_id=?').run(m.id));
  await f.start({transcribe:async()=>{calls++;return '旧语音补充文字。';}});assert.equal((await f.history()).length,1);
  await until(async()=>(await f.history())[0].transcript==='旧语音补充文字。');assert.equal(calls,2);
});


test('invalid service credentials pause the backlog and recover pending text after configuration restart',async t=>{
  let calls=0;const f=await fixture(t,{asrProviderPauseMs:60000,transcribe:async()=>{calls++;throw new AsrError('AUTH');}});
  const first=await f.send();await until(async()=>(await f.history()).find(m=>m.id===first.id)?.transcript_status==='failed');
  const second=await f.send();await sleep(60);assert.equal(calls,1);assert.deepEqual(await f.audio(second.id),pcm);
  await f.stop();await f.start({transcribe:async()=>{calls++;return '恢复识别。';}});
  await until(async()=>(await f.history()).every(m=>m.transcript_status==='ready'));assert.equal(calls,3);
});

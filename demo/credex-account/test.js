const test = require('node:test');
const assert = require('node:assert/strict');
const q = require('./quota.js');

test('unknown and out-of-range quota do not turn into 0%', () => {
    for (const value of [null, undefined, '', ' ', -1, NaN, 101, Infinity, 'oops']) assert.equal(q.validPercent(value), null);
    assert.equal(q.validPercent(0), 0); assert.equal(q.validPercent(100), 100);
});
test('no Provider row never shows default numeric fields as real data', () => {
    const model = q.model({five:0, week:0, kind:'codex'});
    assert.equal(model.state, 'disconnected'); assert.equal(model.five, null);
});
test('a balance source displays its own data and never stale Codex fields', () => {
    const model = q.model({rows:1, kind:'balance', five:90, week:80});
    assert.equal(model.mode, 'balance'); assert.equal(model.state, 'empty');
    assert.equal(model.five, null); assert.equal(model.week, null);
});
test('zero is a valid exhausted window and a missing second window stays unknown', () => {
    const model = q.model({rows:1, kind:'codex', five:0, week:-1, health:'fresh'});
    assert.equal(model.state, 'ready'); assert.equal(model.five, 0); assert.equal(model.week, null);
});
test('authentication and cached data have explicit state while preserving actual last values', () => {
    assert.equal(q.model({rows:1, kind:'codex', five:64, health:'auth_required'}).state, 'auth');
    assert.equal(q.model({rows:1, kind:'codex', five:64, health:'cached'}).five, 64);
    assert.equal(q.model({rows:1, kind:'codex', five:64, health:'cached'}).state, 'cached');
});
test('empty quota is a separate state', () => assert.equal(q.model({rows:1,kind:'codex',five:-1,week:-1,health:'empty'}).state,'empty'));
test('absolute reset time is retained with approximate countdown', () => {
    const info = q.resetInfo('10-03 15:00', new Date(2026,9,3,12,30));
    assert.equal(info.minutes,150); assert.equal(info.label,'10-03 15:00'); assert.match(info.countdown,/约 2小时30分/);
});
test('invalid dates and expired reset times never invent future availability', () => {
    assert.equal(q.resetInfo('02-30 10:00',new Date(2026,1,2)).minutes,null);
    assert.equal(q.resetInfo('10-03 10:00',new Date(2026,9,3,12)).minutes,0);
    assert.equal(q.resetInfo('--',new Date()).minutes,null);
});
test('year boundary is handled without mistaking stale December for next year', () => {
    assert.equal(q.resetInfo('01-01 01:00',new Date(2026,11,31,23)).minutes,120);
    assert.equal(q.resetInfo('12-31 23:00',new Date(2027,0,1,1)).minutes,0);
});
test('swiping cancels taps and vertical drags cannot activate a control', () => {
    assert.equal(q.gesture({x:200,y:200,hit:'body'},{x:80,y:205},100),'next');
    assert.equal(q.gesture({x:200,y:200,hit:'body'},{x:205,y:300},100),'cancel');
});
test('a long press hides values and a short tap switches used versus remaining', () => {
    const down={x:200,y:200,hit:'body'};
    assert.equal(q.gesture(down,{x:201,y:202},700),'privacy');
    assert.equal(q.gesture(down,{x:201,y:202},200),'body');
});
test('camera area and mismatched down/up targets cannot activate controls', () => {
    assert.equal(q.hit(-50,200),'');
    assert.equal(q.gesture({x:-50,y:200,hit:''},{x:100,y:200},300),'cancel');
    assert.equal(q.gesture({x:25,y:475,hit:'overview'},{x:160,y:475},100),'previous');
    assert.equal(q.gesture({x:140,y:475,hit:'overview'},{x:150,y:475},100),'cancel');
});

test('currency values, service details and timestamps are preserved verbatim', () => {
    const data = q.model({rows:1,kind:'balance',health:'fresh',sourceId:'my-service',source:'服务',balanceCount:1,
        b1Name:'余额服务',b1Value:'¥128.50',b1Detail:'可用余额 · 含赠送额度',b1Status:'已更新',b1Updated:'13:30'});
    assert.equal(data.state,'ready'); assert.equal(data.id,'my-service');
    assert.deepEqual(data.balances[0],{name:'余额服务',value:'¥128.50',detail:'可用余额 · 含赠送额度',status:'已更新',updated:'13:30'});
});
test('generic percentages are not converted from used to remaining', () => {
    const data=q.model({rows:1,kind:'balance',health:'fresh',balanceCount:1,b1Value:'136.8%',b1Detail:'已使用 / 套餐额度'});
    assert.equal(data.balances[0].value,'136.8%'); assert.equal(data.balances[0].detail,'已使用 / 套餐额度');
});
test('missing balance values stay unknown while numeric zero is retained', () => {
    for (const value of ['',null,undefined,'--']) {
        assert.equal(q.model({rows:1,kind:'balance',balanceCount:1,b1Value:value}).balances[0].value,'—');
    }
    assert.equal(q.model({rows:1,kind:'balance',balanceCount:1,b1Value:0}).balances[0].value,'0');
});
test('a missing cursor or zero balance count cannot expose stale account slots', () => {
    assert.equal(q.model({rows:0,kind:'balance',balanceCount:1,b1Value:'$500'}).balances.length,0);
    assert.equal(q.model({rows:1,kind:'balance',balanceCount:0,b1Value:'$500'}).balances.length,0);
    assert.equal(q.model({rows:1,kind:'none',balanceCount:0}).state,'unselected');
});
test('all exported slots are read, but Codex selection clears stale balance payloads', () => {
    const payload={rows:1,kind:'balance',balanceCount:3,b1Name:'A',b1Value:'$1',b2Name:'B',b2Value:'¥2',b3Name:'C',b3Value:'3%'};
    assert.deepEqual(q.model(payload).balances.map(x=>x.value),['$1','¥2','3%']);
    assert.equal(q.model({...payload,kind:'codex',five:50}).balances.length,0);
});
test('balance login, failure and cache states retain the actual last display value', () => {
    const base={rows:1,kind:'balance',balanceCount:1,b1Value:'$6.20'};
    for(const health of ['cached','auth_required','not_connected','error']) {
        const data=q.model({...base,health});
        assert.equal(data.state,health==='auth_required'?'auth':health);
        assert.equal(data.balances[0].value,'$6.20');
    }
});
test('balance navigation uses details and status instead of Codex-only windows',()=>{
    assert.deepEqual(q.pageList('balance'),['overview','detail','status']);
    assert.deepEqual(q.pageList('codex'),['overview','five','week','status']);
});
test('long and multiline details wrap without losing text or splitting emoji',()=>{
    const paragraphs='可用额度 12.50\r\n完整说明🙂还有更多';
    const lines=q.wrapLines(paragraphs,5,s=>Array.from(s).length);
    assert.equal(lines.join(''),'可用额度 12.50完整说明🙂还有更多');
    assert.ok(lines.every(s=>Array.from(s).length<=5));
    assert.ok(lines.some(s=>s.includes('🙂')));
    const punct=q.wrapLines('一二三四五。六七八九十。',5,s=>Array.from(s).length);
    assert.ok(punct.every(s=>!s.startsWith('。')));
    assert.equal(punct.join(''),'一二三四五。六七八九十。');
});

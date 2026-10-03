/* Original Credex rear-screen application. HostVar -> data.cq; no account credentials. */
(function (g) {
    'use strict';
    function validPercent(value) {
        if (value === null || value === undefined || String(value).trim() === '') return null;
        var n = Number(value);
        return isFinite(n) && n >= 0 && n <= 100 ? n : null;
    }
    function model(raw) {
        raw = raw || {};
        var rows = Number(raw.rows || 0);
        var count = Math.max(0, Math.min(3, Math.floor(Number(raw.balanceCount) || 0))), balances = [];
        if (rows >= 1) for (var i = 1; i <= count; i++) {
            var value = raw['b' + i + 'Value'];
            value = value === undefined || value === null ? '' : String(value).trim();
            balances.push({ name: String(raw['b' + i + 'Name'] || raw.source || '账户'),
                value: !value || value === '--' || value === 'null' ? '—' : value,
                detail: String(raw['b' + i + 'Detail'] || ''), status: String(raw['b' + i + 'Status'] || raw.status || ''),
                updated: String(raw['b' + i + 'Updated'] || raw.updated || '--') });
        }
        var kind = String(raw.kind || (count ? 'balance' : 'codex'));
        var mode = kind === 'codex' ? 'codex' : 'balance';
        if (mode === 'codex') balances = [];
        var state = rows < 1 ? 'disconnected' : kind === 'none' && !balances.length ? 'unselected' :
            raw.health === 'auth_required' || raw.health === 'signed_out' || raw.status === 'Not signed in' || raw.status === 'Authorization required' ? 'auth' :
            raw.health === 'not_connected' ? 'not_connected' : raw.health === 'error' ? 'error' :
            raw.health === 'cached' || raw.status === 'Refresh failed' ? 'cached' : 'ready';
        var five = validPercent(raw.five), week = validPercent(raw.week);
        if (mode !== 'codex' || rows < 1) { five = null; week = null; }
        if (state === 'ready' && (mode === 'codex' ? five === null && week === null : !balances.length)) state = 'empty';
        return { state: state, mode: mode, balances: balances, id: String(raw.sourceId || raw.source || ''),
            five: five, week: week, fiveReset: String(raw.fiveReset || '--'),
            weekReset: String(raw.weekReset || '--'), status: String(raw.status || ''),
            updated: String(raw.updated || '--'), plan: String(raw.plan || ''), source: String(raw.source || '') };
    }
    function pageList(mode) { return mode === 'codex' ? ['overview', 'five', 'week', 'status'] : ['overview', 'detail', 'status']; }
    function wrapLines(value, width, measure) {
        var result = [];
        String(value || '').replace(/\r\n?/g, '\n').split('\n').forEach(function (paragraph) {
            var line = '';
            Array.from(paragraph).forEach(function (ch) {
                if (line && measure(line + ch) > width) {
                    var carry = '';
                    if (/^[，。！？、；：）】》,.!?;:)\]]$/.test(ch) && Array.from(line).length > 1) {
                        var letters = Array.from(line); carry = letters.pop(); line = letters.join('');
                    }
                    result.push(line); line = carry;
                }
                line += ch;
            });
            result.push(line);
        });
        return result;
    }
    // Credex exports MM-dd HH:mm, not the epoch/year. Countdown is explicitly approximate.
    function resetInfo(text, now) {
        var m = /^(\d{2})-(\d{2}) (\d{2}):(\d{2})$/.exec(text || '');
        if (!m) return { label: '--', countdown: '重置时间未知', minutes: null };
        var month = +m[1], day = +m[2], hour = +m[3], minute = +m[4], year = now.getFullYear();
        if (now.getMonth() === 11 && month === 1) year++;
        if (now.getMonth() === 0 && month === 12) year--;
        var date = new Date(year, month - 1, day, hour, minute);
        if (date.getMonth() !== month - 1 || date.getDate() !== day || hour > 23 || minute > 59) {
            return { label: '--', countdown: '重置时间未知', minutes: null };
        }
        var left = Math.ceil((date.getTime() - now.getTime()) / 60000);
        if (left <= 0) return { label: text, countdown: '已到重置时间 · 等待更新', minutes: 0 };
        if (left > 8 * 24 * 60) return { label: text, countdown: '请以 Credex 时间为准', minutes: null };
        var d = Math.floor(left / 1440), h = Math.floor(left % 1440 / 60), min = left % 60;
        var parts = d ? d + '天' + (h ? h + '小时' : '') : h ? h + '小时' + (min ? min + '分' : '') : min + '分钟';
        return { label: text, countdown: '约 ' + parts + ' 后重置', minutes: left };
    }
    function hit(x, y) {
        if (x < 0 || x > 600 || y < 0 || y > 572) return '';
        if (y >= 468 && y <= 532) {
            if (x >= 24 && x <= 144) return 'overview';
            if (x >= 156 && x <= 256) return 'five';
            if (x >= 268 && x <= 380) return 'week';
            if (x >= 392 && x <= 576) return 'refresh';
        }
        if (y >= 24 && y <= 76 && x >= 420 && x <= 576) return 'status';
        if (y >= 110 && y <= 424 && x >= 24 && x <= 576) return 'body';
        return '';
    }
    function gesture(down, up, elapsed) {
        if (!down.hit) return 'cancel';
        var dx = up.x - down.x, dy = up.y - down.y;
        if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) return dx < 0 ? 'next' : 'previous';
        if (Math.abs(dx) > 14 || Math.abs(dy) > 14) return 'cancel';
        if (down.hit !== hit(up.x, up.y)) return 'cancel';
        if (down.hit === 'body' && elapsed >= 650) return 'privacy';
        return down.hit;
    }
    var api = { validPercent: validPercent, model: model, resetInfo: resetInfo, hit: hit, gesture: gesture,
        pageList: pageList, wrapLines: wrapLines };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (!g.document) return;
    g.CredexQuotaCore = api;
    var canvas = document.getElementById('c'), ctx = canvas.getContext('2d');
    var W = g.CANVAS_W || canvas.width || 904, H = g.CANVAS_H || canvas.height || 572;
    var camera = W / 3, scale = Math.min((W - camera) / 600, H / 572);
    var dx = camera + (W - camera - 600 * scale) / 2, dy = (H - 572 * scale) / 2;
    var pref = { used: false, privacy: false, page: 'overview' }, key = 'credex.rear.quota.v1';
    try {
        var saved = JSON.parse(localStorage.getItem(key) || '{}');
        pref.used = saved.used === true; pref.privacy = saved.privacy === true;
        if (['overview', 'five', 'week', 'detail', 'status'].indexOf(saved.page) >= 0) pref.page = saved.page;
    } catch (_) {}
    var current = model(g.data && g.data.cq), down = null, queued = false, sourceKey = '', slot = 0, detailPart = 0, partCount = 1;
    function updateCurrent() {
        current = model(g.data && g.data.cq);
        var identity = current.mode + ':' + current.id;
        if (identity !== sourceKey) { slot = 0; detailPart = 0; sourceKey = identity; }
        if (pageList(current.mode).indexOf(pref.page) < 0) pref.page = 'overview';
        slot = Math.min(slot, Math.max(0, current.balances.length - 1));
    }
    function save() { try { localStorage.setItem(key, JSON.stringify(pref)); } catch (_) {} }
    function box(x, y, w, h, r, color) {
        ctx.beginPath(); ctx.moveTo(x + r, y);
        ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
        ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r);
        ctx.closePath(); ctx.fillStyle = color; ctx.fill();
    }
    function text(s, x, y, size, color, weight, align) {
        ctx.fillStyle = color; ctx.font = (weight || '400') + ' ' + size + 'px sans-serif';
        ctx.textAlign = align || 'left'; ctx.textBaseline = 'middle'; ctx.fillText(String(s), x, y);
    }
    function fit(s, max, size, weight) {
        s = String(s || ''); ctx.font = (weight || '400') + ' ' + size + 'px sans-serif';
        if (ctx.measureText(s).width <= max) return s;
        var chars = Array.from(s);
        while (chars.length && ctx.measureText(chars.join('') + '…').width > max) chars.pop();
        return chars.join('') + '…';
    }
    function color(value) { return value !== null && value <= 10 ? '#FFB69D' : '#ADF0D1'; }
    function amount(value) { return pref.privacy || value === null ? '—' : String(Math.round(pref.used ? 100 - value : value)); }
    function badge() {
        var labels = { ready: '已连接', cached: '缓存数据', auth: '需登录', unselected: '选择来源', empty: '暂无数据',
            disconnected: '未连接', not_connected: '未登录', error: '读取失败' };
        return labels[current.state] || '未连接';
    }
    function valueCard(which, x, y, w, h) {
        var val = current[which], accent = which === 'five' ? color(val) : val !== null && val <= 10 ? '#FFB69D' : '#A8CEFF';
        var reset = which === 'five' ? current.fiveReset : current.weekReset;
        box(x, y, w, h, 28, '#121A21');
        box(x + 22, y + 24, 6, 24, 3, accent);
        text(which === 'five' ? '5 小时' : '每周', x + 42, y + 36, 25, '#E8F1F2', '600');
        text(pref.privacy ? '已隐藏' : pref.used ? '已使用' : '剩余额度', x + 22, y + 89, 19, '#8B9DA9');
        text(amount(val), x + 22, y + 153, w < 300 ? 76 : 112, '#F1F9F7', '600');
        if (val !== null && !pref.privacy) text('%', x + (w < 300 ? 171 : 291), y + 170, 30, '#8B9DA9');
        box(x + 22, y + h - 84, w - 44, 8, 4, '#293740');
        var shown = val === null ? null : pref.used ? 100 - val : val;
        if (shown !== null && !pref.privacy && shown > 0) box(x + 22, y + h - 84, Math.max(8, (w - 44) * shown / 100), 8, 4, accent);
        text(pref.privacy ? '长按恢复显示' : resetInfo(reset, new Date()).label + ' 重置', x + 22, y + h - 43, 19, '#9AAAB5');
    }
    function statusPage() {
        box(24, 110, 552, 314, 28, '#121A21');
        var title = { disconnected: '等待 Credex', unselected: '先选择展示来源', auth: '登录需要更新', cached: '显示最近缓存',
            empty: '暂未提供数据', ready: '数据已连接', not_connected: '服务尚未登录', error: '服务读取失败' }[current.state];
        text(title, 48, 154, 35, '#EDF7F4', '600');
        var a = current.state === 'unselected' ? 'Credex → 设置 → 背屏配置' : current.state === 'disconnected' ? '安装并打开 Credex，再读取数据' :
            current.state === 'auth' || current.state === 'not_connected' ? '请在 Credex 中登录当前服务' : '来源：' + (current.source || 'Credex');
        var b = current.state === 'unselected' ? '选择一个 Assistant 展示源' : current.state === 'auth' || current.state === 'cached' || current.state === 'error' ?
            '已有数值可能是缓存，请勿当作实时值' : '跟随 Credex 当前选择的服务';
        a = fit(a, 504, 22);
        text(a, 48, 220, 22, '#B9C9D1'); text(b, 48, 259, 21, '#8EABB6');
        text('最近更新  ' + current.updated, 48, 317, 22, '#A8CEFF');
        text(fit(current.status || current.source || '等待本机展示数据', 486, 19), 48, 368, 19, '#9AAAB5');
    }
    function service() { return current.balances[slot] || { name: current.source || '账户', value: '—', detail: '', status: '', updated: current.updated }; }
    function lines(value, size) {
        ctx.font = '400 ' + size + 'px sans-serif';
        return wrapLines(value, 504, function (s) { return ctx.measureText(s).width; });
    }
    function balancePage() {
        var item = service();
        box(24, 110, 552, 314, 28, '#121A21');
        if (pref.page === 'detail') {
            text('服务详情', 48, 148, 25, '#ADF0D1', '600');
            if (pref.privacy) {
                detailPart = 0;
                text('数值与详情已隐藏', 48, 246, 30, '#C0D1D6');
                text('长按卡片恢复显示', 48, 292, 21, '#79939D');
                partCount = 1;
            } else {
                var content = item.value + '\n' + (item.detail || '服务未提供额外详情') + '\n' + item.status;
                var all = lines(content, 22); partCount = Math.max(1, Math.ceil(all.length / 6));
                detailPart %= partCount;
                all.slice(detailPart * 6, detailPart * 6 + 6).forEach(function (line, i) { text(line, 48, 194 + i * 32, 22, '#CDDCE0'); });
            }
            text('详情 ' + (detailPart + 1) + '/' + partCount, 552, 396, 18, '#79939D', '400', 'right');
            return;
        }
        text(fit(item.name, 504, 25, '600'), 48, 147, 25, '#ADF0D1', '600');
        var value = pref.privacy ? '—' : item.value, size = 86;
        ctx.font = '600 ' + size + 'px sans-serif';
        while (size > 30 && ctx.measureText(value).width > 504) { size -= 2; ctx.font = '600 ' + size + 'px sans-serif'; }
        text(fit(value, 504, size, '600'), 48, 231, size, '#F1F9F7', '600');
        var brief = pref.privacy ? ['数值与详情已隐藏', '长按恢复显示'] : lines(item.detail || '数值按 Credex 中的显示设置呈现', 20);
        brief.slice(0, 2).forEach(function (line, i) { text(line, 48, 297 + i * 29, 20, '#9AAAB5'); });
        if (brief.length > 2 && !pref.privacy) text('更多详情 →', 552, 355, 18, '#A8CEFF', '400', 'right');
        text(fit(item.status || badge(), 336, 19), 48, 390, 19, '#A8CEFF');
        text(item.updated, 552, 390, 19, '#79939D', '400', 'right');
    }
    function render() {
        queued = false; updateCurrent();
        ctx.fillStyle = '#020607'; ctx.fillRect(0, 0, W, H);
        ctx.fillStyle = '#000000'; ctx.fillRect(0, 0, camera, H);
        ctx.save(); ctx.translate(dx, dy); ctx.scale(scale, scale);
        text(current.mode === 'codex' ? 'CODEX' : 'CREDEX', 24, 47, 30, '#EFF8F4', '600');
        text(current.mode === 'codex' ? '额度速览' : fit(current.source || '账户速览', 370, 18), 24, 82, 18, '#76929C');
        box(420, 24, 156, 48, 24, current.state === 'ready' ? '#18392D' : '#352D22');
        text(badge(), 498, 49, 21, current.state === 'ready' ? '#ADF0D1' : '#EFC998', '500', 'center');
        if (pref.page === 'status' || ['disconnected', 'unselected', 'empty'].indexOf(current.state) >= 0) statusPage();
        else if (current.mode === 'balance') balancePage();
        else if (pref.page === 'overview') {
            valueCard('five', 24, 110, 268, 314); valueCard('week', 308, 110, 268, 314);
        } else {
            valueCard(pref.page, 24, 110, 552, 314);
            var info = resetInfo(pref.page === 'five' ? current.fiveReset : current.weekReset, new Date());
            text(pref.privacy ? '数值已隐藏' : info.countdown, 550, 313, 21, '#C0D1D6', '400', 'right');
        }
        var hint = pref.privacy ? '隐私模式 · 长按数值恢复' : current.mode === 'balance' ?
            (pref.page === 'detail' && partCount > 1 ? '轻触查看下一段 · 左右滑动翻页' : '轻触查看详情 · 长按隐藏数值') :
            pref.page === 'overview' ? '轻触切换已用 / 剩余 · 长按隐藏' : '轻触切换已用 / 剩余 · 左右滑动翻页';
        text(fit(hint, 552, 18), 24, 448, 18, '#79939D');
        var controls = current.mode === 'codex' ? [['overview','总览',24,120],['five','5 小时',156,100],['week','每周',268,112]] :
            [['overview','总览',24,120],['detail','详情',156,100],['status','状态',268,112]];
        controls.concat([['refresh','读取',392,184]]).forEach(function (b) {
            var active = b[0] === pref.page;
            box(b[2], 468, b[3], 64, 22, active ? '#CDF7E6' : '#17252D');
            text(b[1], b[2] + b[3] / 2, 500, 22, active ? '#153127' : '#AAC0C8', active ? '600' : '400', 'center');
        });
        var footerState = { cached: '缓存 · ', auth: '需登录 · ', error: '读取失败 · ' };
        text((pref.privacy ? 'PRIVATE · ' : footerState[current.state] || '') + '更新 ' + current.updated,
            576, 551, 16, '#5F7985', '400', 'right');
        ctx.restore();
    }
    function drawSoon() {
        if (queued) return; queued = true;
        if (typeof g.requestAnimationFrame === 'function') g.requestAnimationFrame(render); else render();
    }
    function point(event) {
        var r = canvas.getBoundingClientRect();
        return { x: ((event.clientX - r.left) * W / (r.width || W) - dx) / scale,
            y: ((event.clientY - r.top) * H / (r.height || H) - dy) / scale };
    }
    function action(name) {
        updateCurrent();
        var pages = pageList(current.mode);
        if (pages.indexOf(pref.page) < 0) pref.page = 'overview';
        if (current.mode === 'balance') {
            if (name === 'five') name = 'detail'; if (name === 'week') name = 'status';
        }
        if (name === 'next' || name === 'previous') pref.page = pages[(pages.indexOf(pref.page) + (name === 'next' ? 1 : pages.length - 1)) % pages.length];
        else if (name === 'body') {
            if (current.mode === 'codex') pref.used = !pref.used;
            else if (pref.page !== 'detail') { pref.page = 'detail'; detailPart = 0; }
            else if (partCount > 1) {
                detailPart = (detailPart + 1) % partCount;
                if (!detailPart && current.balances.length > 1) slot = (slot + 1) % current.balances.length;
            }
            else if (current.balances.length > 1) { slot = (slot + 1) % current.balances.length; detailPart = 0; }
            else pref.page = 'overview';
        }
        else if (name === 'privacy') pref.privacy = !pref.privacy;
        else if (pages.indexOf(name) >= 0) pref.page = name;
        else if (name === 'refresh' && typeof g.CredexPreviewRefresh === 'function') g.CredexPreviewRefresh();
        save(); drawSoon();
    }
    canvas.addEventListener('pointerdown', function (e) {
        var p = point(e); down = { x:p.x, y:p.y, hit:hit(p.x,p.y), time:Date.now() };
    });
    canvas.addEventListener('pointerup', function (e) {
        if (!down) return; var begin = down; down = null;
        action(gesture(begin, point(e), Date.now() - begin.time));
    });
    canvas.addEventListener('pointercancel', function () { down = null; });
    if (g.data && g.data.addEventListener) g.data.addEventListener('change', drawSoon);
    if (g.addEventListener) g.addEventListener('keydown', function (e) {
        if (e.key === 'ArrowRight') action('next'); if (e.key === 'ArrowLeft') action('previous');
    });
    g.CredexQuotaInspect = function () { return { data:model(g.data && g.data.cq), preferences:JSON.parse(JSON.stringify(pref)) }; };
    g.CredexQuotaAction = action;
    // No animation loop. Host patches and touches redraw; only approximate countdown uses a minute timer.
    if (typeof g.setInterval === 'function') g.setInterval(drawSoon, 60000);
    render();
})(typeof globalThis !== 'undefined' ? globalThis : this);

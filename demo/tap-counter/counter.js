/* Original learning example. Shared verbatim by the JsCanvas and browser builds. */
(function () {
    'use strict';
    const cv = document.getElementById('c');
    const ctx = cv.getContext('2d');
    const W = window.CANVAS_W || 904;
    const H = window.CANVAS_H || 572;
    const CAMERA = Math.floor(W / 3);
    const SCALE = Math.min((W - CAMERA) / 600, H / 572);
    const DX = CAMERA + ((W - CAMERA) - 600 * SCALE) / 2;
    const DY = (H - 572 * SCALE) / 2;
    const KEY = 'rearscreen.learning.tap_counter.v1';
    const MAX = 999999;
    const buttons = [
        { id: 'plus', x: 36, y: 346, w: 528, h: 104 },
        { id: 'minus', x: 36, y: 466, w: 252, h: 68 },
        { id: 'reset', x: 312, y: 466, w: 252, h: 68 }
    ];
    let count = 0;
    let canSave = true;
    let down = null;
    try {
        const raw = localStorage.getItem(KEY);
        if (raw !== null) {
            const value = Number(raw);
            if (isFinite(value) && value >= 0 && value <= MAX) count = Math.floor(value);
        }
    } catch (e) { canSave = false; }

    function rounded(x, y, w, h, r, color) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.arcTo(x + w, y, x + w, y + h, r);
        ctx.arcTo(x + w, y + h, x, y + h, r);
        ctx.arcTo(x, y + h, x, y, r);
        ctx.arcTo(x, y, x + w, y, r);
        ctx.closePath();
        ctx.fillStyle = color;
        ctx.fill();
    }

    function text(label, x, y, size, color, weight) {
        ctx.fillStyle = color;
        ctx.font = (weight || '400') + ' ' + size + 'px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(label, x, y);
    }

    function render() {
        // Reserve the left W/3 as a solid, noninteractive camera area.
        ctx.fillStyle = '#070D0B';
        ctx.fillRect(0, 0, W, H);
        ctx.save();
        ctx.translate(DX, DY);
        ctx.scale(SCALE, SCALE);
        rounded(18, 18, 564, 536, 34, '#10241D');
        rounded(36, 42, 8, 37, 4, '#B9EFCD');
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        ctx.font = '600 28px sans-serif';
        ctx.fillStyle = '#E4F5EA';
        ctx.fillText('轻触计数器', 60, 62);
        text('每一次，都算数', 300, 118, 21, '#A2BDAA');
        text(String(count), 300, 226, count > 99999 ? 86 : 112, '#F0FAF3', '600');
        text(count === MAX ? '已达到上限' : (canSave ? '已保存' : '本次会话'), 300, 304, 18, '#8FAD99');

        const active = down ? down.id : '';
        rounded(36, 346, 528, 104, 27, active === 'plus' ? '#91D3AB' : '#B9EFCD');
        text('+1', 300, 398, 44, '#102D1E', '600');
        rounded(36, 466, 252, 68, 22, active === 'minus' ? '#365344' : '#233D30');
        rounded(312, 466, 252, 68, 22, active === 'reset' ? '#365344' : '#233D30');
        text('−1', 162, 500, 30, count ? '#DCEEE2' : '#75917E', '500');
        text('归零', 438, 500, 26, '#DCEEE2', '500');
        ctx.restore();
    }

    function point(e) {
        // This conversion also handles a browser preview scaled down with CSS.
        const r = cv.getBoundingClientRect();
        return {
            x: ((e.clientX - r.left) * W / (r.width || W) - DX) / SCALE,
            y: ((e.clientY - r.top) * H / (r.height || H) - DY) / SCALE
        };
    }

    function hit(p) {
        for (let i = 0; i < buttons.length; i++) {
            const b = buttons[i];
            if (p.x >= b.x && p.x <= b.x + b.w && p.y >= b.y && p.y <= b.y + b.h) return b.id;
        }
        return '';
    }

    function commit(id) {
        if (id === 'plus') count = Math.min(MAX, count + 1);
        else if (id === 'minus') count = Math.max(0, count - 1);
        else if (id === 'reset') count = 0;
        else return;
        try {
            localStorage.setItem(KEY, String(count));
            canSave = true;
        } catch (e) { canSave = false; }
    }

    cv.addEventListener('pointerdown', function (e) {
        const p = point(e), id = hit(p);
        down = id ? { id: id, x: p.x, y: p.y } : null;
        e.preventDefault();
    }, false);
    cv.addEventListener('pointermove', function (e) {
        if (!down) return;
        const p = point(e);
        if (Math.abs(p.x - down.x) + Math.abs(p.y - down.y) > 24) down = null;
    }, false);
    cv.addEventListener('pointerup', function (e) {
        const p = point(e);
        if (down && hit(p) === down.id && Math.abs(p.x - down.x) + Math.abs(p.y - down.y) <= 24) commit(down.id);
        down = null;
    }, false);
    cv.addEventListener('pointercancel', function () { down = null; }, false);

    // The sampled JsCanvas uses a frame callback; preserve that host convention.
    function frame() { render(); requestAnimationFrame(frame); }
    frame();
})();

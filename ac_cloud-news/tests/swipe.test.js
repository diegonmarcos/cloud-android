/* Regression check for bindSwipeCard()'s gesture logic — run: node tests/swipe.test.js
 *
 * Lives OUTSIDE app/src/main/assets/ on purpose: anything under assets/ is
 * packaged into the APK, and a test has no business shipping to a phone.
 *
 * No framework and no DOM library by design — same "no dependency" rule the
 * page itself follows. The function is pulled straight out of news.html and
 * driven through a stub element, so this tests the code that actually ships
 * rather than a copy that can drift away from it.
 *
 * What it pins down is the part that was genuinely broken and the part that
 * is genuinely easy to break again: the axis lock, the commit threshold, and
 * pointercancel aborting instead of deciding.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'news.html');

function loadBindSwipeCard() {
    const src = fs.readFileSync(HTML, 'utf8');
    const marker = 'function bindSwipeCard(card, opts) {';
    const start = src.indexOf(marker);
    assert.notStrictEqual(start, -1, 'bindSwipeCard() not found in news.html');
    // Every nested function is indented, so the first newline-brace-newline
    // at column 0 is this function's own closing brace.
    const end = src.indexOf('\n}\n', start);
    assert.notStrictEqual(end, -1, 'could not find end of bindSwipeCard()');
    const fn = src.slice(start, end + 3);
    return new Function(fn + '\nreturn bindSwipeCard;')();
}

const bindSwipeCard = loadBindSwipeCard();

const stamp = () => ({ style: {} });

function stubCard() {
    return {
        style: {},
        _handlers: {},
        classList: {
            _set: new Set(),
            add(c) { this._set.add(c); },
            remove(c) { this._set.delete(c); },
            contains(c) { return this._set.has(c); },
        },
        addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); },
        fire(type, ev) { (this._handlers[type] || []).forEach((fn) => fn(ev)); },
        setPointerCapture() {},
    };
}

/** Drive a full gesture: path[0] is the touch-down point. */
function drag(card, path, endWith) {
    const [sx, sy] = path[0];
    card.fire('pointerdown', { clientX: sx, clientY: sy, pointerId: 1, target: { closest: () => null } });
    path.slice(1).forEach(([x, y]) => card.fire('pointermove', { clientX: x, clientY: y, pointerId: 1 }));
    card.fire(endWith || 'pointerup', {});
}

function harness(extra) {
    const fired = [];
    const card = stubCard();
    const opts = Object.assign({
        stampLeft: stamp(), stampRight: stamp(), stampUp: stamp(), stampDown: stamp(),
        onLeft: () => fired.push('left'),
        onRight: () => fired.push('right'),
        onSwipeUp: () => fired.push('up'),
        onSwipeDown: () => fired.push('down'),
    }, extra || {});
    bindSwipeCard(card, opts);
    return { card, opts, fired };
}

const tests = {
    'horizontal past threshold commits'() {
        const a = harness();
        drag(a.card, [[200, 300], [230, 302], [340, 305]]);
        assert.deepStrictEqual(a.fired, ['right']);

        const b = harness();
        drag(b.card, [[200, 300], [170, 302], [60, 305]]);
        assert.deepStrictEqual(b.fired, ['left']);
    },

    'vertical past threshold commits'() {
        const up = harness();
        drag(up.card, [[200, 300], [202, 270], [205, 160]]);
        assert.deepStrictEqual(up.fired, ['up']);

        const down = harness();
        drag(down.card, [[200, 300], [202, 330], [205, 440]]);
        assert.deepStrictEqual(down.fired, ['down']);
    },

    'axis locks on first committed direction and never switches'() {
        // Starts clearly horizontal, then swerves hard downward. The lock
        // must hold: this is a right-swipe, not an add-to-calendar.
        const a = harness();
        drag(a.card, [[200, 300], [260, 300], [300, 520]]);
        assert.deepStrictEqual(a.fired, ['right'], 'a horizontal drag that swerves must stay horizontal');

        // ...and the mirror image: vertical first, then a big sideways drift.
        const b = harness();
        drag(b.card, [[200, 300], [200, 240], [430, 180]]);
        assert.deepStrictEqual(b.fired, ['up'], 'a vertical drag that swerves must stay vertical');
    },

    'movement under the lock distance never moves the card'() {
        const a = harness();
        a.card.fire('pointerdown', { clientX: 200, clientY: 300, pointerId: 1, target: { closest: () => null } });
        a.card.fire('pointermove', { clientX: 208, clientY: 305, pointerId: 1 }); // 8px, 5px — under LOCK
        assert.strictEqual(a.card.style.transform, undefined, 'a tap-sized wobble must not nudge the card');
        a.card.fire('pointerup', {});
        assert.deepStrictEqual(a.fired, []);
    },

    'under-threshold drag snaps back without deciding'() {
        const a = harness();
        drag(a.card, [[200, 300], [240, 302], [260, 304]]); // 60px < 90px threshold
        assert.deepStrictEqual(a.fired, []);
        assert.strictEqual(a.card.style.transform, '', 'card must reset');
        assert.strictEqual(a.opts.stampRight.style.opacity, 0, 'stamps must clear');
    },

    'pointercancel ABORTS even past the threshold'() {
        // The regression that mattered: cancel used to run the same handler
        // as pointerup, so an interrupted drag committed a decision the
        // finger never released.
        const a = harness();
        drag(a.card, [[200, 300], [260, 302], [400, 305]], 'pointercancel');
        assert.deepStrictEqual(a.fired, [], 'a cancelled gesture must decide nothing');
        assert.strictEqual(a.card.style.transform, '', 'cancelled card must reset');
        assert.strictEqual(a.card.classList.contains('dragging'), false);
    },

    'an unbound direction snaps back instead of throwing'() {
        // Feed cards leave onSwipeDown unbound; a down-swipe there must be
        // a no-op, not a TypeError that wedges the deck.
        const a = harness({ onSwipeDown: undefined });
        drag(a.card, [[200, 300], [202, 340], [205, 460]]);
        assert.deepStrictEqual(a.fired, []);
        assert.strictEqual(a.card.style.transform, '');
    },

    'pointerdown inside ignoreSelector never starts a drag'() {
        const fired = [];
        const card = stubCard();
        bindSwipeCard(card, {
            ignoreSelector: '.event-action-btn',
            onLeft: () => fired.push('left'),
            onRight: () => fired.push('right'),
        });
        card.fire('pointerdown', { clientX: 200, clientY: 300, pointerId: 1, target: { closest: (s) => (s === '.event-action-btn' ? {} : null) } });
        card.fire('pointermove', { clientX: 400, clientY: 300, pointerId: 1 });
        card.fire('pointerup', {});
        assert.deepStrictEqual(fired, [], 'buttons must keep their own click');
    },

    'vertical drag applies no rotation'() {
        const a = harness();
        a.card.fire('pointerdown', { clientX: 200, clientY: 300, pointerId: 1, target: { closest: () => null } });
        a.card.fire('pointermove', { clientX: 200, clientY: 240, pointerId: 1 });
        assert.ok(/^translateY\(/.test(a.card.style.transform), 'expected translateY, got ' + a.card.style.transform);
        assert.ok(!/rotate/.test(a.card.style.transform), 'a rising card must not tilt like a discarded one');
    },
};

let failed = 0;
Object.keys(tests).forEach((name) => {
    try {
        tests[name]();
        console.log('  ok   ' + name);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + name + '\n       ' + e.message);
    }
});
console.log(failed ? '\n' + failed + ' failing' : '\nall ' + Object.keys(tests).length + ' passing');
process.exit(failed ? 1 : 0);

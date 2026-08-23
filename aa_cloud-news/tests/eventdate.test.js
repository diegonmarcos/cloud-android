/* Regression check for parseEventDate() — run: node tests/eventdate.test.js
 *
 * Same rules as swipe.test.js: outside assets/ so it never ships, no
 * framework, and the functions are pulled straight out of news.html so this
 * tests the code that actually runs rather than a copy that can drift.
 *
 * A headline parser is guesswork by nature, which is exactly why the UI
 * confirms every result with the user. What these cases pin down is that
 * the guess is SENSIBLE and, more importantly, that ambiguous input returns
 * null instead of a confident wrong answer — a null opens the picker on the
 * publish date, which is honest; a wrong parse quietly books the wrong day.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'news.html');

function load() {
    const src = fs.readFileSync(HTML, 'utf8');
    const grab = (marker) => {
        const start = src.indexOf(marker);
        assert.notStrictEqual(start, -1, marker + ' not found in news.html');
        const end = src.indexOf('\n}\n', start);
        assert.notStrictEqual(end, -1, 'could not find end of ' + marker);
        return src.slice(start, end + 3);
    };
    const consts = "var MONTHS = ['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'];\n"
        + "var WEEKDAYS = ['sunday','monday','tuesday','wednesday','thursday','friday','saturday'];\n";
    const body = consts
        + grab('function parseEventDate(text, refMs) {')
        + grab('function finishParse(day, time, matched) {')
        + grab('function parseClockTime(s) {')
        + '\nreturn parseEventDate;';
    return new Function(body)();
}

const parseEventDate = load();

// Fixed reference so these never depend on when they run: Wed 2026-08-12.
const REF = new Date(2026, 7, 12, 9, 0).getTime();

const iso = (ms) => {
    const d = new Date(ms);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
};
const hhmm = (ms) => {
    const d = new Date(ms);
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
};

const tests = {
    'ISO dates win outright'() {
        const r = parseEventDate('Summit set for 2027-03-21 in Geneva', REF);
        assert.strictEqual(iso(r.ms), '2027-03-21');
        assert.strictEqual(r.allDay, true);
    },

    'month-name forms, both orders'() {
        assert.strictEqual(iso(parseEventDate('Festival opens March 21', REF).ms), '2027-03-21');
        assert.strictEqual(iso(parseEventDate('Festival opens 21 March', REF).ms), '2027-03-21');
        assert.strictEqual(iso(parseEventDate('Vote due Sept 3', REF).ms), '2026-09-03');
        assert.strictEqual(iso(parseEventDate('Vote due 3rd of September', REF).ms), '2026-09-03');
    },

    'an explicit year is always obeyed'() {
        assert.strictEqual(iso(parseEventDate('Games open March 21, 2031', REF).ms), '2031-03-21');
        // ...even when it points backwards.
        assert.strictEqual(iso(parseEventDate('Treaty signed 21 March 1999', REF).ms), '1999-03-21');
    },

    'a missing year picks the nearest sensible reading'() {
        // Reference is August 2026. March already passed this year by ~5
        // months, so a headline about "March 21" means next March.
        assert.strictEqual(iso(parseEventDate('Talks resume March 21', REF).ms), '2027-03-21');
        // September is just ahead — same year.
        assert.strictEqual(iso(parseEventDate('Talks resume September 21', REF).ms), '2026-09-21');
        // Last week is still last week, not next year.
        assert.strictEqual(iso(parseEventDate('Ruling issued August 5', REF).ms), '2026-08-05');
    },

    'times are picked up, and set allDay false'() {
        const r = parseEventDate('Match kicks off March 21 at 8pm', REF);
        assert.strictEqual(iso(r.ms), '2027-03-21');
        assert.strictEqual(hhmm(r.ms), '20:00');
        assert.strictEqual(r.allDay, false);

        assert.strictEqual(hhmm(parseEventDate('Doors 7:30 pm on 21 March', REF).ms), '19:30');
        assert.strictEqual(hhmm(parseEventDate('Briefing 09:15 on 21 March', REF).ms), '09:15');
        assert.strictEqual(hhmm(parseEventDate('Rally at 12am March 21', REF).ms), '00:00');
        assert.strictEqual(hhmm(parseEventDate('Rally at 12pm March 21', REF).ms), '12:00');
    },

    'relative forms resolve against the publish date'() {
        assert.strictEqual(iso(parseEventDate('Verdict expected tomorrow', REF).ms), '2026-08-13');
        assert.strictEqual(iso(parseEventDate('Results due today', REF).ms), '2026-08-12');

        // REF is a Wednesday. "Friday" is the coming Friday.
        assert.strictEqual(iso(parseEventDate('Strike begins Friday', REF).ms), '2026-08-14');
        // "next Friday" is the one after that.
        assert.strictEqual(iso(parseEventDate('Strike begins next Friday', REF).ms), '2026-08-21');
        // Same weekday as the reference means today, not a week out.
        assert.strictEqual(iso(parseEventDate('Hearing set for Wednesday', REF).ms), '2026-08-12');
    },

    'tonight implies an evening, not midnight'() {
        const r = parseEventDate('Leaders meet tonight', REF);
        assert.strictEqual(iso(r.ms), '2026-08-12');
        assert.strictEqual(r.allDay, false);
        assert.strictEqual(hhmm(r.ms), '20:00');
    },

    'ambiguous or absent dates return null rather than guessing'() {
        assert.strictEqual(parseEventDate('Markets close mixed amid tariff talks', REF), null);
        assert.strictEqual(parseEventDate('', REF), null);
        assert.strictEqual(parseEventDate(null, REF), null);
        // Bare numeric dates are deliberately unsupported — 3/4/27 is two
        // different days depending on who wrote it.
        assert.strictEqual(parseEventDate('Deadline is 3/4/27', REF), null);
        // Impossible day numbers must not roll over into the next month.
        assert.strictEqual(parseEventDate('Filed on 32 March', REF), null);
    },

    'reports the text it keyed off so a bad guess is visible'() {
        assert.strictEqual(parseEventDate('Festival opens March 21', REF).matched, 'march 21');
        assert.strictEqual(parseEventDate('Verdict expected tomorrow', REF).matched, 'tomorrow');
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

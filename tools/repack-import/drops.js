#!/usr/bin/env node
// Drop rows for the mobs of the Community Repack's huntable areas (areas.json mobs "hunt"): every mob those maps spawn
// that has no drop_data row gets the rows of a donor, the stock field mob closest in level that has drops (a boss
// takes a boss), without the donor's quest items and its own monster etc. (4000000-4031999), which belong to it.
// Writes src/main/resources/db/extensions/2026-10-26-repack-import-drops.xml. Deterministic; run after export.js.
//
//   node tools/repack-import/drops.js [--server <repo root>] [--check]
'use strict';
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const SERVER = path.resolve(args.includes('--server') ? args[args.indexOf('--server') + 1] : path.join(__dirname, '..', '..'));
const CHECK = args.includes('--check');
const WZ = path.join(SERVER, 'wz');
const OUT = path.join(SERVER, 'src', 'main', 'resources', 'db', 'extensions', '2026-10-26-repack-import-drops.xml');
const AREAS = JSON.parse(fs.readFileSync(path.join(__dirname, 'areas.json'), 'utf8')).areas;
const LIST = path.join(SERVER, 'src', 'main', 'resources', 'mods', 'repack-maps.txt');

const pad = (id, n) => String(id).padStart(n, '0');

/** dropperid -> rows from the stock drop table (and the extension changesets that insert rows: none so far). */
const drops = new Map();
{
  const sql = fs.readFileSync(path.join(SERVER, 'src', 'main', 'resources', 'db', 'data', '152-drop-data.sql'), 'utf8');
  const row = /\((\d+),\s*(-?\d+),\s*(\d+),\s*(\d+),\s*(-?\d+),\s*(\d+)\)/g;
  let m;
  while ((m = row.exec(sql))) {
    const r = { dropper: +m[1], item: +m[2], min: +m[3], max: +m[4], quest: +m[5], chance: +m[6] };
    if (!drops.has(r.dropper)) drops.set(r.dropper, []);
    drops.get(r.dropper).push(r);
  }
}

const mobCache = new Map();
function mob(id) {
  if (mobCache.has(id)) return mobCache.get(id);
  const file = path.join(WZ, 'Mob.wz', pad(id, 7) + '.img.xml');
  let info = null;
  if (fs.existsSync(file)) {
    const xml = fs.readFileSync(file, 'utf8');
    const block = (/\n  <imgdir name="info">([\s\S]*?)\n  <\/imgdir>/.exec(xml) || [])[1] || '';
    // info's own values sit at four spaces; deeper ones (info/skill/0/level) are not the mob's
    const int = name => { const m = new RegExp(`\\n    <int name="${name}" value="(-?\\d+)"/>`).exec(block); return m ? +m[1] : 0; };
    info = { id, level: int('level'), boss: int('boss') === 1, revive: [] };
    const revive = /\n    <imgdir name="revive">([\s\S]*?)\n    <\/imgdir>/.exec(block);
    if (revive) for (const r of revive[1].matchAll(/<int name="\d+" value="(\d+)"\/>/g)) info.revive.push(+r[1]);
  }
  mobCache.set(id, info);
  return info;
}

// The mobs the huntable areas spawn, and what they revive into.
const targets = new Set();
{
  const hunt = AREAS.filter(a => a.mobs === 'hunt');
  for (const line of fs.readFileSync(LIST, 'utf8').split('\n')) {
    const id = Number((/^(\d+)\s/.exec(line) || [])[1]);
    if (!id || !hunt.some(a => id >= a.from && id <= a.to)) continue;
    const xml = fs.readFileSync(path.join(WZ, 'Map.wz', 'Map', 'Map' + pad(id, 9)[0], pad(id, 9) + '.img.xml'), 'utf8');
    for (const m of xml.matchAll(/<string name="type" value="m"\/>\s*<string name="id" value="(\d+)"\/>/g)) targets.add(+m[1]);
  }
  for (const id of [...targets]) for (const r of (mob(id) || { revive: [] }).revive) targets.add(r);
}

// Donors: stock field mobs (ids below 9000000; the 9xxxxxx event and party quest mobs keep their own tables) with
// rows that are not all quest items.
const donors = [...drops.keys()]
  .filter(id => id < 9000000 && !targets.has(id) && mob(id) && mob(id).level > 0 && drops.get(id).some(r => r.quest === 0))
  .sort((a, b) => a - b);

function donorFor(target) {
  let best = null;
  for (const id of donors) {
    const d = mob(id);
    if (d.boss !== target.boss) continue;
    const gap = Math.abs(d.level - target.level);
    if (!best || gap < best.gap) best = { id, gap };
  }
  return best && best.id;
}

const rows = [];
const notes = [];
for (const id of [...targets].sort((a, b) => a - b)) {
  const target = mob(id);
  // a mob the server already has rows for keeps them; 93xxxxx are party quest and boss-script mobs (Arkarium's maze
  // mushrooms and pigs here), which drop nothing of their own
  if (!target || drops.has(id) || (id >= 9300000 && id < 9400000)) continue;
  const donor = donorFor(target);
  if (!donor) {
    notes.push(`${id} lv ${target.level}: no donor`);
    continue;
  }
  const copied = drops.get(donor).filter(r => r.quest === 0 && !(r.item >= 4000000 && r.item < 4032000));
  notes.push(`${id} lv ${target.level}${target.boss ? ' boss' : ''} <- ${donor} lv ${mob(donor).level} (${copied.length} rows)`);
  for (const r of copied) rows.push(`(${id}, ${r.item}, ${r.min}, ${r.max}, 0, ${r.chance})`);
}
const dropped = [...new Set(rows.map(r => r.slice(1, r.indexOf(','))))];

const xml = `<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <!-- Drops for the mobs of the Community Repack's huntable areas (docs/REPACK_IMPORT_PLAN.md), written by
         tools/repack-import/drops.js: each mob gets the rows of the stock field mob closest in level, without that
         mob's quest items and its own monster etc. Mob <- donor:
${notes.map(n => '         ' + n).join('\n')}
    -->
    <changeSet id="2026-10-26-repack-import-drops" author="ganre">
        <sql splitStatements="false">
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
VALUES ${rows.join(',\n       ')};
        </sql>
        <rollback>
            <sql>DELETE FROM drop_data WHERE dropperid IN (${dropped.join(', ')});</sql>
        </rollback>
    </changeSet>
</databaseChangeLog>
`;

const old = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8') : null;
console.log(`${targets.size} mobs in the huntable areas, ${dropped.length} given rows (${rows.length}), ${notes.filter(n => n.includes('no donor')).length} without a donor`);
if (CHECK) {
  if (old !== xml) { console.log('differs: ' + path.relative(SERVER, OUT)); process.exit(1); }
} else if (old !== xml) {
  fs.writeFileSync(OUT, xml);
}

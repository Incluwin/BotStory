#!/usr/bin/env node
// Exports the Community Repack's extra maps (the ones the server's Cosmic map set lacks) and every NPC, mob and
// reactor they need into the server's XML dump, adds their String.wz names, and writes the map list the client gate
// reads (src/main/resources/mods/repack-maps.txt) plus a report (tools/repack-import/export-report.json).
// See tools/repack-import/README.md. Deterministic: a second run over the same inputs changes nothing.
//
//   node tools/repack-import/export.js [--repack <dir with the Repack .wz files>] [--server <repo root>] [--check]
//
// --check writes nothing and exits 1 when the server tree differs from what the export would write.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WzFile } = require('./wz-binary');
const { imageXml, nodeLines } = require('./wz-xml');

const args = process.argv.slice(2);
function option(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
}
const REPACK = option('--repack', 'C:/Users/ganre/Desktop/MapleServer/Community Repack');
const SERVER = path.resolve(option('--server', path.join(__dirname, '..', '..')));
const CHECK = args.includes('--check');
const WZ = path.join(SERVER, 'wz');
const SCRIPTS = path.join(SERVER, 'scripts');
const LIST = path.join(SERVER, 'src', 'main', 'resources', 'mods', 'repack-maps.txt');
const REPORT = path.join(__dirname, 'export-report.json');
const AREAS = JSON.parse(fs.readFileSync(path.join(__dirname, 'areas.json'), 'utf8')).areas;

const pad = (id, n) => String(id).padStart(n, '0');
const child = (node, name) => (node && node.children || []).find(c => c.name === name) || null;
const value = (node, name) => { const c = child(node, name); return c ? c.value : undefined; };

const changed = [];
function writeFile(file, text) {
  const old = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
  if (old === text) return;
  changed.push(path.relative(SERVER, file).replace(/\\/g, '/'));
  if (CHECK) return;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
}

function areaOf(mapId) {
  const hits = AREAS.filter(a => mapId >= a.from && mapId <= a.to);
  if (hits.length !== 1) throw new Error(`map ${mapId} is in ${hits.length} areas of areas.json`);
  return hits[0];
}

/** MapFactory.getMapStringName: the String.wz/Map.img group the server reads a map's name from. */
function mapStringGroup(id) {
  if (id < 100000000) return 'maple';
  if (id < 200000000) return 'victoria';
  if (id < 300000000) return 'ossyria';
  if (id < 400000000) return 'elin';
  if (id >= 540000000 && id < 560000000) return 'singapore';
  if (id >= 600000000 && id < 620000000) return 'MasteriaGL';
  if (id >= 677000000 && id < 677100000) return 'Episode1GL';
  if (id >= 670000000 && id < 682000000) {
    return (id >= 674030000 && id < 674040000) || (id >= 680100000 && id < 680200000) ? 'etc' : 'weddingGL';
  }
  if (id >= 682000000 && id < 683000000) return 'HalloweenGL';
  if (id >= 683000000 && id < 684000000) return 'event';
  if (id >= 800000000 && id < 900000000) return id >= 889100000 && id < 889200000 ? 'etc' : 'jp';
  return 'etc';
}

function sha256(file) {
  const hash = crypto.createHash('sha256');
  const fd = fs.openSync(file, 'r');
  const buf = Buffer.alloc(8 * 1024 * 1024);
  let n;
  while ((n = fs.readSync(fd, buf, 0, buf.length, null)) > 0) hash.update(buf.subarray(0, n));
  fs.closeSync(fd);
  return hash.digest('hex');
}

function serverIds(dir, re) {
  const ids = new Set();
  if (!fs.existsSync(dir)) return ids;
  for (const f of fs.readdirSync(dir)) {
    const m = re.exec(f);
    if (m) ids.add(Number(m[1]));
  }
  return ids;
}

// ---------------------------------------------------------------- inputs
const wz = {};
for (const f of ['Map', 'Mob', 'Npc', 'Reactor', 'String']) wz[f] = new WzFile(path.join(REPACK, f + '.wz'));

const serverMaps = new Set();
for (const d of fs.readdirSync(path.join(WZ, 'Map.wz', 'Map'))) {
  if (/^Map\d$/.test(d)) for (const id of serverIds(path.join(WZ, 'Map.wz', 'Map', d), /^(\d{9})\.img\.xml$/)) serverMaps.add(id);
}
const serverMobs = serverIds(path.join(WZ, 'Mob.wz'), /^(\d{7})\.img\.xml$/);
const serverNpcs = serverIds(path.join(WZ, 'Npc.wz'), /^(\d{7})\.img\.xml$/);
const serverReactors = serverIds(path.join(WZ, 'Reactor.wz'), /^(\d{7})\.img\.xml$/);

// The import set is fixed by the list once written, so a rerun (when the maps are already in the server tree) still
// knows which maps are the Repack's.
const listed = new Set();
if (fs.existsSync(LIST)) {
  for (const line of fs.readFileSync(LIST, 'utf8').split('\n')) {
    const m = /^(\d+)\s/.exec(line);
    if (m) listed.add(Number(m[1]));
  }
}
const repackMaps = [];
for (const p of wz.Map.index().keys()) {
  const m = /^Map\/Map\d\/(\d{9})\.img$/.exec(p);
  if (m) repackMaps.push(Number(m[1]));
}
const importMaps = repackMaps.filter(id => !serverMaps.has(id) || listed.has(id)).sort((a, b) => a - b);

// ---------------------------------------------------------------- maps
const report = {
  sources: {},
  areas: {},
  maps: importMaps.length,
  strippedMobSpawns: 0,
  longsAsInt: 0,
  mapScripts: {},
  portalScripts: {},
  missingMapNames: [],
  imported: { npcs: [], mobs: [], reactors: [] },
  existing: { npcs: [], mobs: [], reactors: [] },
  missingFromRepack: { npcs: [], mobs: [], reactors: [] },
  missingStrings: { mobs: [], npcs: [] },
};
const mapImages = [];
const needNpcs = new Set();
const needMobs = new Set();
const needReactors = new Set();
const stats = {};

function noteScript(table, name, mapId) {
  if (!name) return;
  (table[name] = table[name] || []).push(mapId);
}

for (const id of importMaps) {
  const area = areaOf(id);
  const a = report.areas[area.key] = report.areas[area.key] || { name: area.name, mobs: area.mobs, maps: 0, towns: [] };
  a.maps++;
  const id9 = pad(id, 9);
  const img = wz.Map.image(`Map/Map${id9[0]}/${id9}.img`);
  const info = child(img, 'info');
  if (value(info, 'town') === 1) a.towns.push(id);
  noteScript(report.mapScripts, value(info, 'onUserEnter') && 'onUserEnter/' + value(info, 'onUserEnter'), id);
  noteScript(report.mapScripts, value(info, 'onFirstUserEnter') && 'onFirstUserEnter/' + value(info, 'onFirstUserEnter'), id);
  for (const portal of (child(img, 'portal') || { children: [] }).children) noteScript(report.portalScripts, value(portal, 'script'), id);

  const life = child(img, 'life');
  if (life) {
    if (area.mobs === 'none') {
      const before = life.children.length;
      life.children = life.children.filter(l => value(l, 'type') !== 'm');
      report.strippedMobSpawns += before - life.children.length;
    }
    for (const l of life.children) {
      const lid = Number(value(l, 'id'));
      if (value(l, 'type') === 'n') needNpcs.add(lid);
      else if (value(l, 'type') === 'm') needMobs.add(lid);
    }
  }
  for (const r of (child(img, 'reactor') || { children: [] }).children) needReactors.add(Number(value(r, 'id')));
  mapImages.push({ id, id9, img });
}

// ---------------------------------------------------------------- npcs, mobs, reactors (with their links)
function linkOf(img) {
  const link = value(child(img, 'info'), 'link');
  return link === undefined || link === '' ? null : Number(link);
}

function importAll(kind, need, have, file, follow) {
  const queue = [...need].sort((a, b) => a - b);
  const seen = new Set();
  const imported = [];
  while (queue.length) {
    const id = queue.shift();
    if (seen.has(id)) continue;
    seen.add(id);
    if (have.has(id) && !imported.includes(id) && !needsRewrite(kind, id)) {
      report.existing[kind].push(id);
      continue;
    }
    const img = file.image(pad(id, 7) + '.img');
    if (!img) {
      report.missingFromRepack[kind].push(id);
      continue;
    }
    imported.push(id);
    for (const next of follow(img)) if (!seen.has(next)) queue.push(next);
    writeFile(path.join(WZ, kind[0].toUpperCase() + kind.slice(1, -1) + '.wz', pad(id, 7) + '.img.xml'), imageXml(img, stats));
  }
  report.imported[kind] = imported.sort((a, b) => a - b);
  report.existing[kind].sort((a, b) => a - b);
  return imported;
}

// An id this export wrote earlier counts as the export's own on a rerun (it was absent from the Cosmic set).
const previous = fs.existsSync(REPORT) ? JSON.parse(fs.readFileSync(REPORT, 'utf8')).imported : { npcs: [], mobs: [], reactors: [] };
function needsRewrite(kind, id) {
  return previous[kind].includes(id);
}

const npcs = importAll('npcs', needNpcs, serverNpcs, wz.Npc, img => {
  const link = linkOf(img);
  return link ? [link] : [];
});
// Mob skills: LifeFactory throws on a (skill, level) the server's Skill.wz/MobSkill.img lacks, so a level the Repack
// has is imported into it (a summon's mobs join the import) and a skill id the server does not know at all is taken
// off the mob (info/skill renumbered, the skillN animations renamed to match).
const mobSkillFile = path.join(WZ, 'Skill.wz', 'MobSkill.img.xml');
const serverMobSkills = new Map();
{
  let skill = null;
  let inLevel = false;
  for (const line of fs.readFileSync(mobSkillFile, 'utf8').split('\n')) {
    let m;
    if ((m = /^  <imgdir name="(\d+)">$/.exec(line))) { skill = Number(m[1]); inLevel = false; serverMobSkills.set(skill, new Set()); }
    else if (line === '    <imgdir name="level">') inLevel = true;
    else if (line === '    </imgdir>') inLevel = false;
    else if (inLevel && (m = /^      <imgdir name="(\d+)">$/.exec(line))) serverMobSkills.get(skill).add(Number(m[1]));
  }
}
const previousLevels = new Set(fs.existsSync(REPORT) ? JSON.parse(fs.readFileSync(REPORT, 'utf8')).mobSkillLevels || [] : []);
wz.Skill = new WzFile(path.join(REPACK, 'Skill.wz'));
const repackMobSkills = wz.Skill.image('MobSkill.img');
const mobSkillLevels = new Map(); // "skill/level" -> level node
report.mobSkillsDropped = [];

function mobSkills(id, img) {
  const out = [];
  const skills = child(child(img, 'info'), 'skill');
  if (!skills) return out;
  const kept = [];
  const animations = [];
  skills.children.sort((a, b) => Number(a.name) - Number(b.name));
  for (const entry of skills.children) {
    const skill = value(entry, 'skill');
    const level = value(entry, 'level');
    const key = `${skill}/${level}`;
    const onServer = serverMobSkills.has(skill) && serverMobSkills.get(skill).has(level) && !previousLevels.has(key);
    const repackLevel = child(child(child(repackMobSkills, String(skill)), 'level'), String(level));
    const animation = child(img, 'skill' + (Number(entry.name) + 1));
    if (!serverMobSkills.has(skill) || (!onServer && !repackLevel)) {
      report.mobSkillsDropped.push(`${id} ${key}`);
      if (animation) img.children.splice(img.children.indexOf(animation), 1);
      continue;
    }
    if (!onServer) mobSkillLevels.set(key, repackLevel);
    const levelNode = onServer ? null : repackLevel;
    if (levelNode) for (const c of levelNode.children) if (/^\d+$/.test(c.name)) out.push(Number(c.value));
    kept.push(entry);
    animations.push(animation);
  }
  kept.forEach((entry, i) => {
    entry.name = String(i);
    if (animations[i]) animations[i].name = 'skill' + (i + 1);
  });
  skills.children = kept;
  return out;
}

const mobs = importAll('mobs', needMobs, serverMobs, wz.Mob, img => {
  const out = [];
  const link = linkOf(img);
  if (link) out.push(link);
  for (const r of (child(child(img, 'info'), 'revive') || { children: [] }).children) out.push(Number(r.value));
  out.push(...mobSkills(img.name.replace('.img', ''), img));
  return out;
});
report.mobSkillLevels = [...mobSkillLevels.keys()].sort();
const reactors = importAll('reactors', needReactors, serverReactors, wz.Reactor, img => {
  const link = linkOf(img);
  return link ? [link] : [];
});

// A spawn whose template is in neither WZ set would fail the server's map load and is absent from the Repack client
// too, so it is left out of the map.
report.droppedSpawns = [];
for (const { id, id9, img } of mapImages) {
  const absent = (kind, lid) => report.missingFromRepack[kind].includes(lid);
  const life = child(img, 'life');
  if (life) {
    life.children = life.children.filter(l => {
      const kind = value(l, 'type') === 'n' ? 'npcs' : 'mobs';
      const lid = Number(value(l, 'id'));
      if (!absent(kind, lid)) return true;
      report.droppedSpawns.push(`${id} ${kind.slice(0, -1)} ${lid}`);
      return false;
    });
  }
  const reactorList = child(img, 'reactor');
  if (reactorList) {
    reactorList.children = reactorList.children.filter(r => {
      const lid = Number(value(r, 'id'));
      if (!absent('reactors', lid)) return true;
      report.droppedSpawns.push(`${id} reactor ${lid}`);
      return false;
    });
  }
  writeFile(path.join(WZ, 'Map.wz', 'Map', 'Map' + id9[0], id9 + '.img.xml'), imageXml(img, stats));
}

// ---------------------------------------------------------------- String.wz
const repackStrings = {};
for (const name of ['Map', 'Mob', 'Npc']) repackStrings[name] = wz.String.image(name + '.img');

function repackMapName(id) {
  for (const group of repackStrings.Map.children) {
    const entry = child(group, String(id));
    if (entry) return entry;
  }
  return null;
}

/**
 * Puts entries (id -> imgdir node) into an existing XML image under the imgdir path (names below the root; [] = the
 * root itself), whose children must all be numbered imgdirs. An entry replaces the block with its id, or goes before
 * the first block with a higher id, so the existing blocks keep their order.
 */
function spliceAt(text, names, entries) {
  const lines = text.split('\n');
  let start = 1;
  let end = lines.lastIndexOf('</imgdir>');
  let depth = 1;
  for (const name of names) {
    const indent = '  '.repeat(depth);
    let found = -1;
    for (let i = start + 1; i < end; i++) {
      if (lines[i] === `${indent}<imgdir name="${name}">`) { found = i; break; }
    }
    if (found < 0) throw new Error('no imgdir ' + names.join('/'));
    start = found;
    end = lines.indexOf(`${indent}</imgdir>`, found);
    depth++;
  }
  const indent = '  '.repeat(depth);
  const head = lines.slice(0, start + 1);
  const tail = lines.slice(end);
  const blocks = [];
  const open = new RegExp(`^${indent}<imgdir name="(\\d+)">$`);
  for (let i = start + 1; i < end;) {
    const m = open.exec(lines[i]);
    if (!m) throw new Error(`unexpected line ${i + 1} under ${names.join('/') || 'the root'}: ${lines[i]}`);
    let j = lines.indexOf(`${indent}</imgdir>`, i);
    if (j < 0) throw new Error('unclosed block at line ' + (i + 1));
    blocks.push({ id: Number(m[1]), lines: lines.slice(i, j + 1) });
    i = j + 1;
  }
  for (const id of [...entries.keys()].sort((a, b) => a - b)) {
    const block = { id, lines: nodeLines(entries.get(id), depth, stats) };
    const at = blocks.findIndex(b => b.id === id);
    if (at >= 0) {
      blocks[at] = block;
      continue;
    }
    const after = blocks.findIndex(b => b.id > id);
    if (after < 0) blocks.push(block); else blocks.splice(after, 0, block);
  }
  return [...head, ...blocks.flatMap(b => b.lines), ...tail].join('\n');
}

const stringDir = path.join(WZ, 'String.wz');
{
  const byGroup = new Map();
  for (const id of importMaps) {
    let entry = repackMapName(id);
    if (!entry || !value(entry, 'mapName')) {
      report.missingMapNames.push(id);
      const area = areaOf(id);
      entry = { type: 'imgdir', children: [
        { name: 'streetName', type: 'string', value: area.name },
        { name: 'mapName', type: 'string', value: value(entry, 'mapName') || area.name }] };
    }
    const group = mapStringGroup(id);
    if (!byGroup.has(group)) byGroup.set(group, new Map());
    byGroup.get(group).set(id, { ...entry, name: String(id) });
  }
  const file = path.join(stringDir, 'Map.img.xml');
  let text = fs.readFileSync(file, 'utf8');
  for (const [group, entries] of [...byGroup.entries()].sort()) text = spliceAt(text, [group], entries);
  writeFile(file, text);
}
for (const [kind, ids] of [['Mob', mobs], ['Npc', npcs]]) {
  const entries = new Map();
  for (const id of ids) {
    const entry = child(repackStrings[kind], String(id));
    if (entry) entries.set(id, entry); else report.missingStrings[kind.toLowerCase() + 's'].push(id);
  }
  const file = path.join(stringDir, kind + '.img.xml');
  writeFile(file, spliceAt(fs.readFileSync(file, 'utf8'), [], entries));
}
report.unnamedNpcs = npcs.filter(id => !value(child(repackStrings.Npc, String(id)), 'name'));
{
  let text = fs.readFileSync(mobSkillFile, 'utf8');
  const bySkill = new Map();
  for (const [key, node] of mobSkillLevels) {
    const [skill, level] = key.split('/').map(Number);
    if (!bySkill.has(skill)) bySkill.set(skill, new Map());
    bySkill.get(skill).set(level, { ...node, name: String(level) });
  }
  for (const [skill, levels] of [...bySkill.entries()].sort((a, b) => a[0] - b[0])) {
    text = spliceAt(text, [String(skill), 'level'], levels);
  }
  writeFile(mobSkillFile, text);
}

// ---------------------------------------------------------------- the gate's list and the report
{
  const lines = [
    '# Maps only the Community Repack client has (tools/repack-import/export.js; do not edit by hand).',
    '# A client on the Cosmic WZ files closes when it is sent into one, so server.mods.RepackMaps gates them.',
    '# <map id> <area key from tools/repack-import/areas.json>',
  ];
  for (const id of importMaps) lines.push(`${id} ${areaOf(id).key}`);
  writeFile(LIST, lines.join('\n') + '\n');
}

for (const table of [report.mapScripts, report.portalScripts]) {
  for (const name of Object.keys(table)) {
    const file = table === report.mapScripts ? path.join(SCRIPTS, 'map', name + '.js') : path.join(SCRIPTS, 'portal', name + '.js');
    table[name] = { maps: table[name], server: fs.existsSync(file) };
  }
}
const sorted = obj => Object.fromEntries(Object.keys(obj).sort().map(k => [k, obj[k]]));
report.mapScripts = sorted(report.mapScripts);
report.portalScripts = sorted(report.portalScripts);
report.longsAsInt = stats.longs || 0;
for (const f of Object.keys(wz)) {
  const file = path.join(REPACK, f + '.wz');
  report.sources[f + '.wz'] = { bytes: fs.statSync(file).size, sha256: sha256(file), version: wz[f].version };
}
writeFile(REPORT, JSON.stringify(report, null, 1) + '\n');
for (const f of Object.values(wz)) f.close();

console.log(`maps ${importMaps.length}, npcs ${npcs.length} (+${report.existing.npcs.length} on the server), mobs ${mobs.length}`
  + ` (+${report.existing.mobs.length}), reactors ${reactors.length} (+${report.existing.reactors.length}),`
  + ` stripped mob spawns ${report.strippedMobSpawns}, files ${CHECK ? 'that differ' : 'written'} ${changed.length}`);
if (CHECK && changed.length) {
  for (const f of changed.slice(0, 20)) console.log('  differs: ' + f);
  process.exit(1);
}

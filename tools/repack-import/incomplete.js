#!/usr/bin/env node
// Lists the Repack maps the Repack client itself cannot draw: a map whose background, object or tile is missing from
// the Repack's own Map.wz closes the client as it loads (found live 2026-11-08, Crack in Time). Writes
// src/main/resources/mods/repack-incomplete-maps.txt, which server.mods.RepackMaps closes to players.
// A missing bgm only leaves the map silent, and the client draws only the NPCs, mobs and reactors the server spawns
// (export.js leaves out a spawn whose template neither WZ set has), so neither counts.
//
//   node tools/repack-import/incomplete.js [--repack <dir with the Repack .wz files>] [--server <repo root>] [--check]
//
// --check writes nothing and exits 1 when the list differs from what this run would write.
'use strict';
const fs = require('fs');
const path = require('path');
const { WzFile } = require('./wz-binary');

const args = process.argv.slice(2);
function option(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
}
const REPACK = option('--repack', 'C:/Users/ganre/Desktop/MapleServer/Community Repack');
const SERVER = path.resolve(option('--server', path.join(__dirname, '..', '..')));
const CHECK = args.includes('--check');
const MODS = path.join(SERVER, 'src', 'main', 'resources', 'mods');
const LIST = path.join(MODS, 'repack-maps.txt');
const OUT = path.join(MODS, 'repack-incomplete-maps.txt');

const child = (node, name) => (node && node.children || []).find(c => c.name === String(name)) || null;
const at = (node, names) => names.reduce((n, name) => child(n, name), node);
const value = (node, name) => { const c = child(node, name); return c ? c.value : undefined; };
const rows = node => (node && node.children) || [];

const mapWz = new WzFile(path.join(REPACK, 'Map.wz'));
const images = new Map();
function image(p) {
  if (!images.has(p)) images.set(p, mapWz.image(p));
  return images.get(p);
}

/** The first background, object or tile of a map that the Repack's Map.wz lacks, or null. */
function missing(id) {
  const padded = String(id).padStart(9, '0');
  const map = image(`Map/Map${padded[0]}/${padded}.img`);
  if (!map) return 'the map image';
  for (const back of rows(child(map, 'back'))) {
    const bS = value(back, 'bS');
    if (!bS) continue;
    const kind = value(back, 'ani') === 1 ? 'ani' : 'back';
    const no = value(back, 'no');
    if (!at(image(`Back/${bS}.img`), [kind, no])) return `Back/${bS}.img/${kind}/${no}`;
  }
  for (let layer = 0; layer < 8; layer++) {
    const L = child(map, layer);
    for (const obj of rows(child(L, 'obj'))) {
      const parts = ['l0', 'l1', 'l2'].map(k => value(obj, k));
      const oS = value(obj, 'oS');
      if (!at(image(`Obj/${oS}.img`), parts)) return `Obj/${oS}.img/${parts.join('/')}`;
    }
    const tS = value(child(L, 'info'), 'tS');
    for (const tile of rows(child(L, 'tile'))) {
      const parts = [value(tile, 'u'), value(tile, 'no')];
      if (!at(image(`Tile/${tS}.img`), parts)) return `Tile/${tS}.img/${parts.join('/')}`;
    }
  }
  return null;
}

const ids = fs.readFileSync(LIST, 'utf8').split(/\r?\n/).filter(l => /^\d/.test(l)).map(l => Number(l.split(/\s+/)[0]));
const lines = [
  '# Repack maps the Repack client cannot draw: a background, object or tile is missing from its own Map.wz, so the',
  '# client closes there (tools/repack-import/incomplete.js; do not edit by hand). server.mods.RepackMaps closes them.',
  '# <map id> <the first missing piece>',
];
for (const id of ids) {
  const piece = missing(id);
  if (piece) lines.push(`${id} ${piece.replace(/\s/g, '_')}`);
}
const text = lines.join('\n') + '\n';
mapWz.close();

const old = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8') : null;
console.log(`${ids.length} Repack maps, ${lines.length - 3} incomplete${old === text ? ', list unchanged' : ''}`);
if (old !== text) {
  if (CHECK) process.exit(1);
  fs.writeFileSync(OUT, text);
}

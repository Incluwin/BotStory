// Writes a wz-binary.js node tree in the server's XML dump format (wz/*.wz/*.img.xml): UTF-8, two-space indent, one
// node per line, canvases with their width and height but no pixels, LF line ends.
'use strict';

function escape(text) {
  let out = '';
  for (const ch of String(text)) {
    const c = ch.codePointAt(0);
    if (ch === '&') out += '&amp;';
    else if (ch === '<') out += '&lt;';
    else if (ch === '>') out += '&gt;';
    else if (ch === '"') out += '&quot;';
    else if (ch === "'") out += '&apos;';
    else if (c < 0x20 || c === 0x7F) out += '&#' + c + ';';
    else out += ch;
  }
  return out;
}

/** Floats the way the dump writes them: "10.0", "1.999998" (seven significant digits). */
function formatFloat(value, digits) {
  if (!Number.isFinite(value)) return String(value);
  if (Number.isInteger(value)) return value.toFixed(1);
  let s = value.toPrecision(digits);
  if (s.includes('e')) return String(value);
  if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '.0');
  return s;
}

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;

/**
 * The server parser has no "long" tag (provider.wz.XMLDomMapleData.getType), so a long that fits an int is written
 * as an int and any other as a string; stats.longs counts both.
 */
function writeNode(node, depth, lines, stats) {
  const pad = '  '.repeat(depth);
  const name = escape(node.name);
  switch (node.type) {
    case 'imgdir':
    case 'canvas':
    case 'convex': {
      const size = node.type === 'canvas' ? ` width="${node.width}" height="${node.height}"` : '';
      lines.push(`${pad}<${node.type} name="${name}"${size}>`);
      for (const child of node.children || []) writeNode(child, depth + 1, lines, stats);
      lines.push(`${pad}</${node.type}>`);
      return;
    }
    case 'vector':
      lines.push(`${pad}<vector name="${name}" x="${node.x}" y="${node.y}"/>`);
      return;
    case 'null':
    case 'sound':
      lines.push(`${pad}<${node.type} name="${name}"/>`);
      return;
    case 'short':
    case 'int':
      lines.push(`${pad}<${node.type} name="${name}" value="${node.value}"/>`);
      return;
    case 'long': {
      stats.longs = (stats.longs || 0) + 1;
      const v = node.value;
      const fits = v >= BigInt(INT_MIN) && v <= BigInt(INT_MAX);
      lines.push(`${pad}<${fits ? 'int' : 'string'} name="${name}" value="${v.toString()}"/>`);
      return;
    }
    case 'float':
      lines.push(`${pad}<float name="${name}" value="${formatFloat(node.value, 7)}"/>`);
      return;
    case 'double':
      lines.push(`${pad}<double name="${name}" value="${formatFloat(node.value, 15)}"/>`);
      return;
    case 'string':
    case 'uol':
      lines.push(`${pad}<${node.type} name="${name}" value="${escape(node.value)}"/>`);
      return;
    default:
      throw new Error('cannot write node type ' + node.type);
  }
}

/** The whole file for an image node (its name is the file's, e.g. "219000000.img"). */
function imageXml(image, stats = {}) {
  const lines = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'];
  writeNode(image, 0, lines, stats);
  return lines.join('\n') + '\n';
}

/** The lines of one node at depth, for splicing into an existing file. */
function nodeLines(node, depth, stats = {}) {
  const lines = [];
  writeNode(node, depth, lines, stats);
  return lines;
}

module.exports = { imageXml, nodeLines, escape };

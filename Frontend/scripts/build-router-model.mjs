/**
 * Generates the landing page's assets, so they stay reproducible and carry no
 * third-party licence:
 *   public/models/airpairy-router.glb  router with named, separable sub-meshes
 *   public/hdri/studio.hdr             softbox studio environment
 *
 * The layers stack along +Z. Each mesh stores its exploded-view travel in
 * userData.explode, so the page and the model can't drift apart. The page
 * upgrades the materials (clear coat, printed circuit board, micro surface) by
 * name at runtime; see RouterScene.
 *
 * Run from Frontend/: node scripts/build-router-model.mjs
 */
import { mkdirSync, writeFileSync } from "node:fs";
import * as THREE from "three";
import { GLTFExporter } from "three/examples/jsm/exporters/GLTFExporter.js";
import { mergeGeometries, mergeVertices } from "three/examples/jsm/utils/BufferGeometryUtils.js";

// GLTFExporter reads its output through FileReader, which Node doesn't provide.
globalThis.FileReader = class {
  readAsArrayBuffer(blob) { blob.arrayBuffer().then((buffer) => { this.result = buffer; this.onloadend?.(); }); }
  readAsDataURL(blob) { blob.arrayBuffer().then((buffer) => { this.result = `data:${blob.type};base64,${Buffer.from(buffer).toString("base64")}`; this.onloadend?.(); }); }
};
console.warn = () => {};

const W = 7.2, D = 4.8, R = 0.5;
const FLOOR = 0.22, WALL_TOP = 1.05, LID = 0.26;
const TRAVEL = { base: -1.15, board: -0.2, sink: 0.75, lid: 1.95, glass: 2.8 };

let seed = 42;
const rand = (min = 0, max = 1) => { seed = (seed * 16807) % 2147483647; return min + ((seed - 1) / 2147483646) * (max - min); };

function roundedRect(w, d, r) {
  const x = -w / 2, y = -d / 2, s = new THREE.Shape();
  s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + d - r); s.quadraticCurveTo(x + w, y + d, x + w - r, y + d);
  s.lineTo(x + r, y + d); s.quadraticCurveTo(x, y + d, x, y + d - r);
  s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y);
  return s;
}
/** A narrow slot, cut with few points to keep the vent grid light. */
function slot(cx, cy, w, h) {
  const p = new THREE.Path(), r = h / 2, l = cx - w / 2 + r, q = cx + w / 2 - r;
  p.moveTo(l, cy - r); p.lineTo(q, cy - r); p.lineTo(q + r * 0.7, cy - r * 0.7); p.lineTo(q + r, cy); p.lineTo(q + r * 0.7, cy + r * 0.7); p.lineTo(q, cy + r);
  p.lineTo(l, cy + r); p.lineTo(l - r * 0.7, cy + r * 0.7); p.lineTo(l - r, cy); p.lineTo(l - r * 0.7, cy - r * 0.7); p.closePath();
  return p;
}
function extrude(shape, height, z, bevel = 0, segments = 12) {
  const g = new THREE.ExtrudeGeometry(shape, { depth: height - bevel * 2, bevelEnabled: bevel > 0, bevelSize: bevel, bevelThickness: bevel, bevelSegments: bevel ? 3 : 1, curveSegments: segments });
  return g.translate(0, 0, z + bevel);
}
const plate = (w, d, r, h, z, bevel = 0.03) => extrude(roundedRect(w, d, r), h, z, bevel);
const box = (w, d, h, x, y, z) => new THREE.BoxGeometry(w, d, h).translate(x, y, z + h / 2);
/** A vertical cylinder standing on `z`. */
const post = (r, h, x, y, z, seg = 14) => new THREE.CylinderGeometry(r, r, h, seg).rotateX(Math.PI / 2).translate(x, y, z + h / 2);
/** A cylinder lying along Y, for rear-panel connectors. */
const lug = (r, l, x, y, z, seg = 16) => new THREE.CylinderGeometry(r, r, l, seg).translate(x, y, z);

function merged(list, keepUv = false) {
  const clean = list.map((g) => {
    const out = g.index ? g.toNonIndexed() : g;
    if (!keepUv) out.deleteAttribute("uv");
    return out;
  });
  return mergeGeometries(clean);
}

const m = (params) => new THREE.MeshStandardMaterial(params);
const mats = {
  casing: m({ name: "Lagoon Ink", color: "#0A2A30", metalness: 0.25, roughness: 0.48 }),
  base: m({ name: "Graphite", color: "#0E1719", metalness: 0.3, roughness: 0.56 }),
  rubber: m({ name: "Rubber", color: "#050809", roughness: 0.9 }),
  glass: m({ name: "Smoked Glass", color: "#123A40", metalness: 0.2, roughness: 0.06, transparent: true, opacity: 0.55 }),
  trim: m({ name: "Trim", color: "#C7D6D3", metalness: 1, roughness: 0.26 }),
  screw: m({ name: "Screw", color: "#2B3A3D", metalness: 1, roughness: 0.38 }),
  pcb: m({ name: "Solder Mask", color: "#0B5B57", metalness: 0.1, roughness: 0.5 }),
  chip: m({ name: "Chip", color: "#0C1214", metalness: 0.2, roughness: 0.42 }),
  die: m({ name: "Die Lid", color: "#A9B4B3", metalness: 1, roughness: 0.3 }),
  shield: m({ name: "Tin Shield", color: "#C9CFCB", metalness: 1, roughness: 0.42 }),
  gold: m({ name: "Gold", color: "#D9A94A", metalness: 1, roughness: 0.28 }),
  alu: m({ name: "Aluminium", color: "#BCC9C7", metalness: 1, roughness: 0.32 }),
  ceramic: m({ name: "Ceramic", color: "#B79A72", roughness: 0.55 }),
  capTop: m({ name: "Capacitor", color: "#C2CBCB", metalness: 0.9, roughness: 0.34 }),
  capSleeve: m({ name: "Capacitor Sleeve", color: "#14161A", metalness: 0.1, roughness: 0.45 }),
  ferrite: m({ name: "Ferrite", color: "#26282A", metalness: 0.4, roughness: 0.6 }),
  pad: m({ name: "Thermal Pad", color: "#7C8F93", roughness: 0.75 }),
  antenna: m({ name: "Antenna", color: "#0B1416", metalness: 0.1, roughness: 0.5 }),
  coax: m({ name: "Coax", color: "#0A0C0D", roughness: 0.62 }),
  plastic: m({ name: "Connector Plastic", color: "#111518", roughness: 0.6 }),
  wan: m({ name: "WAN Insert", color: "#0F9A92", roughness: 0.5 }),
  ledGreen: m({ name: "LED Signal", color: "#06332C", emissive: "#37E2C4", emissiveIntensity: 5, roughness: 0.3 }),
  ledAmber: m({ name: "LED Amber", color: "#3A2A07", emissive: "#FFB02E", emissiveIntensity: 3.5, roughness: 0.3 }),
};

const scene = new THREE.Scene();
scene.name = "AirPairyRouter";
function add(name, geometry, mat, explode) {
  // Shared vertices cut the file by more than half without changing the shape.
  const mesh = new THREE.Mesh(mergeVertices(geometry, 1e-4), mat);
  mesh.name = name;
  mesh.userData.explode = explode;
  scene.add(mesh);
}

/* ---------- base shell ---------- */
const wallShape = roundedRect(W, D, R);
wallShape.holes.push(roundedRect(W - 0.24, D - 0.24, R - 0.12));
add("Casing_Base", merged([plate(W, D, R, FLOOR, 0, 0.04), extrude(wallShape, WALL_TOP - FLOOR, FLOOR)], true), mats.base, TRAVEL.base);
add("Feet", merged([-1, 1].flatMap((sx) => [-1, 1].map((sy) => post(0.26, 0.09, sx * (W / 2 - 0.8), sy * (D / 2 - 0.7), -0.09, 24)))), mats.rubber, TRAVEL.base);
add("Standoffs", merged([-1, 1].flatMap((sx) => [-1, 1].map((sy) => post(0.13, 0.2, sx * (W / 2 - 0.55), sy * (D / 2 - 0.55), FLOOR)))), mats.alu, TRAVEL.base);
const trimShape = roundedRect(W + 0.012, D + 0.012, R + 0.006);
trimShape.holes.push(roundedRect(W - 0.2, D - 0.2, R - 0.1));
add("Trim", extrude(trimShape, 0.035, WALL_TOP - 0.05), mats.trim, TRAVEL.base);

/* ---------- rear panel ---------- */
const REAR = D / 2;
const portX = (i) => -1.7 + i * 0.72;
const rearShell = (w, h, wallThick, depth, x, z) => {
  const outer = new THREE.Shape(); outer.moveTo(-w / 2, -h / 2); outer.lineTo(w / 2, -h / 2); outer.lineTo(w / 2, h / 2); outer.lineTo(-w / 2, h / 2); outer.closePath();
  const hole = new THREE.Path(); const iw = w / 2 - wallThick, ih = h / 2 - wallThick;
  hole.moveTo(-iw, -ih); hole.lineTo(iw, -ih); hole.lineTo(iw, ih); hole.lineTo(-iw, ih); hole.closePath();
  outer.holes.push(hole);
  return new THREE.ExtrudeGeometry(outer, { depth, bevelEnabled: false }).rotateX(-Math.PI / 2).rotateY(0).scale(1, 1, -1).translate(x, REAR - depth + 0.05, z);
};
const fixWinding = (g) => { const p = g.attributes.position; for (let i = 0; i < p.count; i += 3) { for (const c of ["x", "y", "z"]) { const a = p[`get${c.toUpperCase()}`](i + 1); p[`set${c.toUpperCase()}`](i + 1, p[`get${c.toUpperCase()}`](i + 2)); p[`set${c.toUpperCase()}`](i + 2, a); } } g.computeVertexNormals(); return g; };
const shell = (...args) => fixWinding(rearShell(...args).toNonIndexed());

const PORT_Z = 0.62;
add("Port_Shells", merged(Array.from({ length: 5 }, (_, i) => shell(0.6, 0.52, 0.03, 0.5, portX(i), PORT_Z))), mats.shield, TRAVEL.base);
add("Port_Bodies", merged(Array.from({ length: 4 }, (_, i) => box(0.54, 0.34, 0.46, portX(i + 1), REAR - 0.26, PORT_Z - 0.23))), mats.plastic, TRAVEL.base);
add("Port_WAN", box(0.54, 0.34, 0.46, portX(0), REAR - 0.26, PORT_Z - 0.23), mats.wan, TRAVEL.base);
add("Port_Pins", merged(Array.from({ length: 5 }, (_, i) => Array.from({ length: 8 }, (_, p) => box(0.028, 0.14, 0.03, portX(i) - 0.2 + p * 0.057, REAR - 0.08, PORT_Z + 0.12))).flat()), mats.gold, TRAVEL.base);
add("Port_LED_Green", merged(Array.from({ length: 5 }, (_, i) => box(0.08, 0.03, 0.05, portX(i) - 0.19, REAR + 0.05, PORT_Z + 0.31))), mats.ledGreen, TRAVEL.base);
add("Port_LED_Amber", merged(Array.from({ length: 5 }, (_, i) => box(0.08, 0.03, 0.05, portX(i) + 0.19, REAR + 0.05, PORT_Z + 0.31))), mats.ledAmber, TRAVEL.base);
add("Rear_Connectors", merged([
  shell(0.5, 0.2, 0.025, 0.4, 2.05, 0.72),
  lug(0.16, 0.4, -2.55, REAR - 0.14, 0.62), lug(0.09, 0.42, -2.55, REAR - 0.13, 0.62),
]), mats.shield, TRAVEL.base);
add("Rear_Button", merged([lug(0.06, 0.2, 2.75, REAR - 0.05, 0.6), box(0.34, 0.1, 0.18, 2.05, REAR + 0.0, 0.3)]), mats.plastic, TRAVEL.base);
add("Vent_Grille", merged(Array.from({ length: 34 }, (_, i) => box(0.09, 0.03, 0.34, -2.9 + i * 0.176, -D / 2 - 0.002, 0.42))), mats.rubber, TRAVEL.base);

/* ---------- circuit board ---------- */
const BOARD_Z = FLOOR + 0.2, BOARD_H = 0.06, TOP = BOARD_Z + BOARD_H;
const boardShape = roundedRect(W - 0.34, D - 0.34, 0.26);
for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
  const hole = new THREE.Path(); hole.absarc(sx * (W / 2 - 0.55), sy * (D / 2 - 0.55), 0.09, 0, Math.PI * 2, true);
  boardShape.holes.push(hole);
}
const board = extrude(boardShape, BOARD_H, BOARD_Z, 0, 10);
{
  // Normalise UVs across the board so the printed texture maps once, edge to edge.
  const p = board.attributes.position, uv = board.attributes.uv;
  for (let i = 0; i < p.count; i++) uv.setXY(i, p.getX(i) / (W - 0.34) + 0.5, p.getY(i) / (D - 0.34) + 0.5);
}
add("PCB", board, mats.pcb, TRAVEL.board);

const SOC = [-0.85, 0.15];
add("Chips", merged([
  box(1.15, 1.15, 0.08, SOC[0], SOC[1], TOP),
  box(0.56, 0.84, 0.09, 0.55, 0.62, TOP), box(0.56, 0.84, 0.09, 0.55, -0.42, TOP),
  box(0.5, 0.38, 0.08, -2.45, -1.15, TOP), box(0.42, 0.42, 0.07, 1.42, -1.25, TOP), box(0.3, 0.3, 0.06, -0.3, -1.45, TOP),
]), mats.chip, TRAVEL.board);
add("Chip_Lid", box(0.72, 0.72, 0.03, SOC[0], SOC[1], TOP + 0.08), mats.die, TRAVEL.board);
add("Thermal_Pad", box(0.72, 0.72, 0.035, SOC[0], SOC[1], TOP + 0.11), mats.pad, TRAVEL.board);

const can = (w, d, h, x, y) => [box(w, d, h - 0.02, x, y, TOP), box(w - 0.1, d - 0.1, 0.02, x, y, TOP + h - 0.02)];
add("RF_Shields", merged([...can(1.05, 0.9, 0.2, 2.0, 0.7), ...can(1.05, 0.9, 0.2, 2.0, -0.45), ...can(0.72, 0.62, 0.16, 1.15, 1.42)]), mats.shield, TRAVEL.board);

const caps = [[-2.75, 0.75, 0.15], [-2.75, 0.3, 0.15], [-2.35, 0.75, 0.13], [-2.35, 0.32, 0.13], [-2.8, -0.35, 0.11], [-2.45, -0.35, 0.11], [0.45, -1.5, 0.13], [0.85, -1.5, 0.13]];
add("Capacitor_Sleeves", merged(caps.map(([x, y, r]) => post(r, 0.3, x, y, TOP, 20))), mats.capSleeve, TRAVEL.board);
add("Capacitor_Tops", merged(caps.map(([x, y, r]) => post(r * 0.94, 0.012, x, y, TOP + 0.3, 20))), mats.capTop, TRAVEL.board);
add("Inductors", merged([[-1.55, 1.5], [-1.0, 1.5], [-0.45, 1.5], [-2.0, -1.5], [-1.6, -1.5]].map(([x, y]) => box(0.36, 0.36, 0.2, x, y, TOP))), mats.ferrite, TRAVEL.board);
add("Crystal", box(0.34, 0.15, 0.09, 0.15, 1.45, TOP), mats.die, TRAVEL.board);

/* Passives scattered around the chips, avoiding the large parts. */
const blocked = [[-1.5, 0.75, -0.5, 0.8], [0.2, 0.95, -0.9, 1.1], [1.4, 2.7, -1.0, 1.9], [-3, -2.1, -0.6, 1], [-2.2, -0.2, 1.25, 1.75], [-2.8, -1.3, -1.6, -1], [0.3, 1.7, -1.7, -1.1]];
const free = (x, y) => !blocked.some(([a, b, c, d]) => x > a && x < b && y > c && y < d);
const passives = { tan: [], dark: [], pads: [] };
for (let i = 0, placed = 0; i < 900 && placed < 210; i++) {
  const x = rand(-3.15, 3.15), y = rand(-1.95, 1.95);
  if (!free(x, y)) continue;
  placed++;
  const horizontal = rand() > 0.5, l = rand() > 0.7 ? 0.11 : 0.075, w = l * 0.56;
  (rand() > 0.4 ? passives.tan : passives.dark).push(box(horizontal ? l : w, horizontal ? w : l, 0.022, x, y, TOP));
  const pad = horizontal ? [[x - l / 2, y], [x + l / 2, y]] : [[x, y - l / 2], [x, y + l / 2]];
  pad.forEach(([px, py]) => passives.pads.push(box(0.03, 0.05, 0.008, px, py, TOP)));
}
add("Passives_A", merged(passives.tan), mats.ceramic, TRAVEL.board);
add("Passives_B", merged(passives.dark), mats.chip, TRAVEL.board);
const gridPins = Array.from({ length: 24 }, (_, i) => post(0.012, 0.16, -0.6 + (i % 12) * 0.05, -1.9 + Math.floor(i / 12) * 0.05, TOP, 6));
add("Solder_Pads", merged([
  ...passives.pads, ...gridPins,
  ...[-1, 1].flatMap((sx) => [-1, 1].map((sy) => post(0.15, 0.008, sx * (W / 2 - 0.55), sy * (D / 2 - 0.55), TOP, 20))),
]), mats.gold, TRAVEL.board);

/* Pigtail coax from the radio shields towards the antenna posts. */
const pigtail = (points) => new THREE.TubeGeometry(new THREE.CatmullRomCurve3(points.map(([x, y, z]) => new THREE.Vector3(x, y, z))), 28, 0.016, 6);
add("Coax", merged([
  pigtail([[2.2, 1.0, TOP + 0.2], [2.5, 1.4, TOP + 0.34], [2.95, 1.75, TOP + 0.22], [3.1, 2.0, TOP + 0.1]]),
  pigtail([[2.1, -0.1, TOP + 0.2], [2.75, 0.1, TOP + 0.36], [3.12, 0.4, TOP + 0.2], [3.3, 0.5, TOP + 0.08]]),
  pigtail([[1.2, 1.5, TOP + 0.17], [0.2, 1.75, TOP + 0.28], [-1.6, 1.85, TOP + 0.3], [-3.1, 2.0, TOP + 0.1]]),
  pigtail([[1.15, 1.2, TOP + 0.17], [-0.2, 0.95, TOP + 0.3], [-2.5, 1.25, TOP + 0.38], [-3.3, 0.5, TOP + 0.08]]),
]), mats.coax, TRAVEL.board);

/* ---------- heat sinks ---------- */
const SINK_Z = TOP + 0.145;
add("Heatsink", merged([
  box(1.9, 1.5, 0.05, SOC[0], SOC[1], SINK_Z),
  ...Array.from({ length: 17 }, (_, i) => box(0.05, 1.5, 0.3 - Math.abs(i - 8) * 0.008, SOC[0] - 0.9 + i * 0.1125, SOC[1], SINK_Z + 0.05)),
]), mats.alu, TRAVEL.sink);

/* ---------- lid ---------- */
const LID_Z = WALL_TOP;
const lidShape = roundedRect(W, D, R);
for (let row = 0; row < 4; row++) for (let col = 0; col < 26; col++) {
  const x = -3.05 + col * 0.244, staggered = row % 2 ? 0.122 : 0;
  lidShape.holes.push(slot(x + staggered, 1.55 + row * 0.15, 0.17, 0.05));
  if (row < 2) lidShape.holes.push(slot(x + staggered, -1.9 - row * 0.15 + 0.15, 0.17, 0.05));
}
add("Casing_Top", extrude(lidShape, LID, LID_Z, 0.045, 12), mats.casing, TRAVEL.lid);
add("Screws", merged([-1, 1].flatMap((sx) => [-1, 1].map((sy) => post(0.085, 0.012, sx * (W / 2 - 0.34), sy * (D / 2 - 0.34), LID_Z + LID, 16)))), mats.screw, TRAVEL.lid);

/* Status lights set into the front of the lid. */
const STATUS_Y = -D / 2 + 0.3;
add("LED_Row", merged(Array.from({ length: 6 }, (_, i) => plate(0.26, 0.05, 0.024, 0.012, LID_Z + LID - 0.004, 0).translate(-0.85 + i * 0.34, STATUS_Y, 0))), mats.ledGreen, TRAVEL.lid);
add("LED_Amber", plate(0.26, 0.05, 0.024, 0.012, LID_Z + LID - 0.004, 0).translate(1.19, STATUS_Y, 0), mats.ledAmber, TRAVEL.lid);

/* The AirPairy mark as a raised metal badge on the glass. */
const LOGO_Z = LID_Z + LID + 0.09;
const toLogo = (x, y) => new THREE.Vector3((x - 32) * 0.021 - 1.95, (32 - y) * 0.021 + 0.05, LOGO_Z);
const curve = (a, b, c, d) => new THREE.CubicBezierCurve3(toLogo(...a), toLogo(...b), toLogo(...c), toLogo(...d));
const stroke = (c, r) => new THREE.TubeGeometry(c, 36, r, 8).scale(1, 1, 0.3).translate(0, 0, LOGO_Z * 0.7);
add("Logo", merged([
  stroke(curve([10, 52], [14, 33], [21, 20], [32, 10]), 0.08), stroke(curve([32, 10], [43, 20], [50, 33], [54, 52]), 0.08),
  stroke(curve([21.5, 41], [28, 35], [36, 35], [42.5, 41]), 0.07),
]), mats.trim, TRAVEL.glass);
add("Logo_Dot", post(0.105, 0.02, toLogo(32, 50).x, toLogo(32, 50).y, LOGO_Z + 0.01, 24), mats.ledGreen, TRAVEL.glass);
add("Glass", plate(5.5, 2.1, 0.22, 0.07, LID_Z + LID + 0.005, 0.02).translate(0, 0.1, 0), mats.glass, TRAVEL.glass);

/* ---------- antennas ---------- */
function paddle() {
  const s = new THREE.Shape();
  s.moveTo(-0.13, 0); s.lineTo(0.13, 0); s.bezierCurveTo(0.22, 0.7, 0.21, 2.0, 0.15, 2.45); s.quadraticCurveTo(0, 2.62, -0.15, 2.45); s.bezierCurveTo(-0.21, 2.0, -0.22, 0.7, -0.13, 0);
  return new THREE.ExtrudeGeometry(s, { depth: 0.09, bevelEnabled: true, bevelSize: 0.035, bevelThickness: 0.035, bevelSegments: 4, curveSegments: 14 }).translate(0, 0, -0.045);
}
function antenna(name, x, y, spin) {
  const blade = paddle().rotateX(Math.PI / 2).rotateZ(spin).rotateX(spin === 0 ? -0.1 : 0).rotateY(spin === 0 ? 0 : (spin > 0 ? 0.1 : -0.1)).translate(x, y, WALL_TOP + 0.04);
  const axis = spin === 0 ? [1, 0, 0] : [0, 1, 0];
  const knuckle = new THREE.CylinderGeometry(0.17, 0.17, 0.4, 20).rotateZ(axis[0] ? Math.PI / 2 : 0).translate(x, y, WALL_TOP - 0.3);
  const riser = box(0.26, 0.12, 0.42, x, y, WALL_TOP - 0.34);
  add(name, merged([blade, knuckle, riser]), mats.antenna, TRAVEL.lid);
}
antenna("Antenna_L", -W / 2 + 0.55, REAR + 0.2, 0);
antenna("Antenna_R", W / 2 - 0.55, REAR + 0.2, 0);
antenna("Antenna_SL", -W / 2 - 0.2, 0.5, Math.PI / 2);
antenna("Antenna_SR", W / 2 + 0.2, 0.5, -Math.PI / 2);
add("SMA_Collars", merged([
  lug(0.1, 0.2, -W / 2 + 0.55, REAR + 0.02, WALL_TOP - 0.3), lug(0.1, 0.2, W / 2 - 0.55, REAR + 0.02, WALL_TOP - 0.3),
  lug(0.1, 0.2, -W / 2 - 0.02, 0.5, WALL_TOP - 0.3).rotateZ(0), lug(0.1, 0.2, W / 2 + 0.02, 0.5, WALL_TOP - 0.3),
]), mats.gold, TRAVEL.base);

const exporter = new GLTFExporter();
const glb = Buffer.from(await exporter.parseAsync(scene, { binary: true }));
mkdirSync("public/models", { recursive: true });
writeFileSync("public/models/airpairy-router.glb", glb);
let triangles = 0;
scene.traverse((o) => { if (o.isMesh) triangles += (o.geometry.index?.count ?? o.geometry.attributes.position.count) / 3; });
console.log(`wrote public/models/airpairy-router.glb ${(glb.length / 1048576).toFixed(2)} MB, ${scene.children.length} parts, ${Math.round(triangles).toLocaleString()} triangles`);

/* A flat (not run-length encoded) Radiance HDR: dark studio, hard-edged softboxes. */
function studioHdr(width = 512, height = 256) {
  const header = `#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y ${height} +X ${width}\n`;
  const pixels = Buffer.alloc(width * height * 4);
  const edge = (d, soft) => Math.min(1, Math.max(0, 0.5 - d / soft));
  const softbox = (u, v, cu, cv, su, sv, soft = 0.012) => edge(Math.abs(Math.min(Math.abs(u - cu), 1 - Math.abs(u - cu))) - su, soft) * edge(Math.abs(v - cv) - sv, soft);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const u = x / width, v = y / height;
    const key = 15 * softbox(u, v, 0.16, 0.2, 0.09, 0.1);
    const strip = 9 * softbox(u, v, 0.66, 0.26, 0.018, 0.2);
    const fill = 2.6 * softbox(u, v, 0.42, 0.14, 0.14, 0.05, 0.05);
    const rim = 5 * softbox(u, v, 0.88, 0.34, 0.06, 0.09, 0.03);
    const floor = 0.16 * Math.max(0, v - 0.55) * 2;
    const room = 0.035 + 0.05 * (1 - v);
    const r = room + floor + key * 0.98 + strip * 0.95 + fill * 0.5 + rim * 0.34;
    const g = room * 1.5 + floor * 1.25 + key + strip + fill * 0.95 + rim * 0.95;
    const b = room * 1.6 + floor * 1.25 + key * 0.97 + strip + fill * 0.92 + rim * 0.9;
    const exponent = Math.ceil(Math.log2(Math.max(r, g, b)));
    const scale = 255 / 2 ** exponent;
    const i = (y * width + x) * 4;
    pixels[i] = r * scale; pixels[i + 1] = g * scale; pixels[i + 2] = b * scale; pixels[i + 3] = exponent + 128;
  }
  return Buffer.concat([Buffer.from(header, "ascii"), pixels]);
}
mkdirSync("public/hdri", { recursive: true });
writeFileSync("public/hdri/studio.hdr", studioHdr());
console.log("wrote public/hdri/studio.hdr");

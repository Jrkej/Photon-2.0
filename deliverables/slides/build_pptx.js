// Builds the presentation (slides/PHOTON_Slides.pptx) with pptxgenjs.
// Diagrams come from the report's TikZ figures (report/build/png), charts are native PowerPoint
// charts with the measured numbers, and the title and team slides read team.json.
//   node slides/build_pptx.js        (run report/render-figures.sh first)
"use strict";
const fs = require("fs");
const path = require("path");
const pptxgen = require("pptxgenjs");
const React = require("react");
const ReactDOMServer = require("react-dom/server");
const sharp = require("sharp");
const fa = require("react-icons/fa");

const ROOT = path.resolve(__dirname, "..");
const PNG = path.join(ROOT, "report", "build", "png");
const SCREENS = path.join(ROOT, "report", "screens");
const OUT = path.join(__dirname, "PHOTON_Slides.pptx");
const team = JSON.parse(fs.readFileSync(path.join(ROOT, "team.json"), "utf8"));

const THEME = {
  name: "PHOTON",
  headFontFace: "Cambria",
  bodyFontFace: "Calibri",
  colors: { dk1: "1E1E1E", lt1: "FFFFFF", dk2: "1B365D", lt2: "EEF4FB", accent1: "2E6DB4", accent2: "F2A93B",
    accent3: "2E7D32", accent4: "C62828", accent5: "8E6BBF", accent6: "6E9CD2", hlink: "2E6DB4", folHlink: "8E6BBF" },
};
const HEX = { navy: "1B365D", blue: "2E6DB4", amber: "F2A93B", green: "2E7D32", red: "C62828", gray: "6F6F6F", light: "EEF4FB", grid: "D9E2EC", line: "6E9CD2" };

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";            // 13.333 x 7.5 in
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "PHOTON: Live Photo Sync for Groups over Unreliable Mobile Networks";
pres.subject = `${team.course} project presentation`;
pres.author = `Group ${team.group_no}`;
const C = pres.SchemeColor;               // text1/text2 = dk1/dk2, background1/2 = lt1/lt2, accent1..6
const W = 13.333, H = 7.5;

// ------------------------------------------------------------------ layouts
pres.defineSlideMaster({
  title: "DARK",
  background: { color: HEX.navy },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.8, y: 2.3, w: 11.7, h: 1.3, fontSize: 54, bold: true, color: C.background1, align: "left", valign: "middle", margin: 0 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: 0.8, y: 3.6, w: 11.7, h: 0.9, fontSize: 22, color: C.accent2, align: "left", valign: "top", margin: 0 }, text: "" } },
  ],
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: "FFFFFF" },
  margin: [0.5, 0.6, 0.6, 0.6],
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.32, w: 12.1, h: 0.8, fontSize: 28, bold: true, color: C.text2, align: "left", valign: "middle", margin: 0 }, text: "" } },
    { text: { text: `PHOTON  ·  ${team.course}  ·  Group ${team.group_no}`, options: { x: 0.6, y: 7.02, w: 9, h: 0.3, fontSize: 10, color: "8A8A8A", margin: 0 } } },
  ],
  slideNumber: { x: 12.2, y: 7.02, w: 0.5, h: 0.3, fontSize: 10, color: "8A8A8A", align: "right" },
});

// ------------------------------------------------------------------ helpers
const iconCache = {};
async function icon(name, color = "#FFFFFF") {
  const key = name + color;
  if (!iconCache[key]) {
    const svg = ReactDOMServer.renderToStaticMarkup(React.createElement(fa[name], { size: 256, color }));
    iconCache[key] = "image/png;base64," + (await sharp(Buffer.from(svg)).resize(256, 256).png().toBuffer()).toString("base64");
  }
  return iconCache[key];
}
async function iconCircle(slide, name, x, y, d, fill = HEX.blue) {
  slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: fill }, line: { color: fill }, objectName: `icon ${name}` });
  const pad = d * 0.24;
  slide.addImage({ data: await icon(name), x: x + pad, y: y + pad, w: d - 2 * pad, h: d - 2 * pad, altText: name.replace(/^Fa/, "") });
}
function pngSize(file) { const b = fs.readFileSync(file); return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) }; }
function fitImage(slide, file, x, y, w, h, alt) {
  const s = pngSize(file);
  let iw = w, ih = w * s.h / s.w;
  if (ih > h) { ih = h; iw = h * s.w / s.h; }
  slide.addImage({ path: file, x: x + (w - iw) / 2, y: y + (h - ih) / 2, w: iw, h: ih, altText: alt || path.basename(file) });
  return { x: x + (w - iw) / 2, y: y + (h - ih) / 2, w: iw, h: ih };
}
const fig = (n) => path.join(PNG, `${n}.png`);
function text(slide, t, o) { slide.addText(t, { isTextBox: true, margin: 0, fontSize: 15, color: C.text1, valign: "top", ...o }); }
function card(slide, x, y, w, h, fill = HEX.light, name = "card") {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { color: fill }, objectName: name });
}
function bullets(items, o = {}) {
  return items.map((it, i) => {
    const runs = Array.isArray(it) ? it : [{ text: it }];
    return runs.map((r, k) => ({ text: r.text, options: { ...(k === 0 ? { bullet: { indent: 16 }, paraSpaceAfter: o.space ?? 8 } : {}), bold: r.bold, color: r.color, breakLine: k === runs.length - 1 && i < items.length - 1 } }));
  }).flat();
}
function content(title, section) {
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: section });
  s.addText(title, { placeholder: "title" });
  return s;
}
function stat(slide, x, y, w, big, label, color = HEX.blue) {
  text(slide, big, { x, y, w, h: 0.75, fontSize: 36, bold: true, color, fontFace: "Cambria", align: "left", valign: "bottom" });
  text(slide, label, { x, y: y + 0.8, w, h: 0.75, fontSize: 13, color: HEX.gray });
}

async function main() {
  // ================================================================ 1. title
  pres.addSection({ title: "Introduction" });
  {
    const s = pres.addSlide({ masterName: "DARK", sectionTitle: "Introduction" });
    s.addImage({ path: path.join(ROOT, "report", "images", "iitr-logo.png"), x: 0.8, y: 0.55, w: 1.05, h: 1.05, altText: "IIT Roorkee logo" });
    text(s, [{ text: team.institute, options: { bold: true, breakLine: true } }, { text: `${team.department}  ·  ${team.course}` }],
      { x: 2.05, y: 0.62, w: 10.4, h: 0.9, fontSize: 15, color: "DCE6F2", valign: "middle" });
    s.addText("PHOTON", { placeholder: "title" });
    s.addText("Live Photo Sync for Groups over Unreliable Mobile Networks", { placeholder: "body" });
    text(s, "A verified, resumable and ephemeral photo-sharing system on HTTP/3, WebSocket and Redis Streams",
      { x: 0.8, y: 4.35, w: 11.7, h: 0.5, fontSize: 16, italic: true, color: "C9D6E6" });
    s.addShape(pres.shapes.LINE, { x: 0.8, y: 5.25, w: 11.7, h: 0, line: { color: "4A6A93", width: 1 }, objectName: "divider" });
    text(s, [{ text: `Group No. ${team.group_no}`, options: { bold: true, color: "FFFFFF", breakLine: true } },
      { text: team.members.map((m) => m.name).join("   ·   "), options: { color: "DCE6F2", breakLine: true } },
      { text: `Submitted to ${team.submitted_to}   ·   ${team.submission_date}`, options: { color: "9FB4CF" } }],
    { x: 0.8, y: 5.45, w: 11.7, h: 1.4, fontSize: 15, paraSpaceAfter: 4 });
  }

  // ================================================================ 2. problem
  {
    const s = content("Every group photo is stuck on one phone", "Introduction");
    const rows = [
      ["FaUserFriends", "Sharing is manual and late", "Someone has to pick photos and send them after the event, so most are never shared."],
      ["FaCloud", "Cloud albums keep everything", "Shared albums store photos indefinitely, recompress them or tie the group to one vendor."],
      ["FaWifi", "Mobile networks break uploads", "Handovers, dead zones and suspended apps restart big uploads and drop notifications."],
      ["FaFingerprint", "A client's hash is only a claim", "If the server trusts it, one member can upload other bytes and hide someone's real photo."],
    ];
    for (let i = 0; i < rows.length; i++) {
      const y = 1.45 + i * 1.32;
      await iconCircle(s, rows[i][0], 0.7, y, 0.78, i === 3 ? HEX.red : HEX.blue);
      text(s, rows[i][1], { x: 1.75, y: y - 0.02, w: 5.6, h: 0.4, fontSize: 18, bold: true, color: C.text2 });
      text(s, rows[i][2], { x: 1.75, y: y + 0.4, w: 5.6, h: 0.7, fontSize: 14, color: HEX.gray });
    }
    card(s, 7.85, 1.45, 4.85, 5.1, HEX.navy, "problem statement");
    text(s, "PROBLEM STATEMENT", { x: 8.2, y: 1.75, w: 4.2, h: 0.35, fontSize: 13, bold: true, color: HEX.amber, charSpacing: 2 });
    text(s, "How can phones on unreliable, changing networks share every photo of a live session with every member automatically, so that each photo is uploaded once, verified before anyone is told, delivered without gaps or duplicates, and deleted from the server within a bounded time?",
      { x: 8.2, y: 2.25, w: 4.2, h: 4.0, fontSize: 17, color: "FFFFFF", fontFace: "Cambria", italic: true, lineSpacingMultiple: 1.1 });
  }

  // ================================================================ 3. solution
  {
    const s = content("PHOTON: join, shoot, and everyone has the photo", "Introduction");
    const steps = [
      ["FaQrcode", "1. Join", "Members join a live session with an 8-character code, an invite link or a QR code."],
      ["FaCamera", "2. Shoot", "Any camera app. PHOTON notices the new photo in the background and uploads it once."],
      ["FaMobileAlt", "3. Receive", "Every member gets a thumbnail at once, then the preview, then the original in the gallery."],
    ];
    for (let i = 0; i < 3; i++) {
      const x = 0.6 + i * 4.15;
      card(s, x, 1.4, 3.85, 2.6, HEX.light, `step ${i + 1}`);
      await iconCircle(s, steps[i][0], x + 0.3, 1.65, 0.72);
      text(s, steps[i][1], { x: x + 1.2, y: 1.72, w: 2.5, h: 0.55, fontSize: 20, bold: true, color: C.text2, valign: "middle" });
      text(s, steps[i][2], { x: x + 0.3, y: 2.55, w: 3.3, h: 1.3, fontSize: 14, color: C.text1 });
    }
    stat(s, 0.6, 4.45, 2.9, "~2 s", "capture to stored on a real phone, app in the background");
    stat(s, 3.7, 4.45, 2.9, "1 upload", "per photo per session: SHA-256 content address, verified by the server");
    stat(s, 6.8, 4.45, 2.9, "3,060", "of 3,060 events delivered to 153 open sockets, none lost", HEX.green);
    stat(s, 9.9, 4.45, 2.8, "30 min", "server copy lifetime, then deleted by two sweepers", HEX.amber);
  }

  // ================================================================ 4. CN concepts
  pres.addSection({ title: "Design" });
  {
    const s = content("Computer networks concepts at work", "Design");
    const cards = [
      ["FaBolt", "HTTP/3 and QUIC", "Photo bytes over UDP 443: no head-of-line blocking, survives Wi-Fi to LTE"],
      ["FaExchangeAlt", "Alt-Svc fallback", "Clients drop to HTTP/2 over TCP when a network blocks UDP"],
      ["FaBroadcastTower", "WebSocket", "Full-duplex event channel, binary frames, close codes 4001 to 4410"],
      ["FaLock", "TLS 1.3 everywhere", "Edge, service to service, and Redis; JWT in the first frame, not the URL"],
      ["FaKey", "OAuth 2.0 + PKCE", "Google and GitHub sign-in without a secret on the phone; EdDSA JWTs"],
      ["FaRandom", "Selective repeat", "1 MiB chunks with CRC32C; only lost chunks are sent again"],
      ["FaStream", "Pub/sub over a log", "Redis Streams: one cursor for backlog and live events"],
      ["FaNetworkWired", "Port forwarding, LAN", "adb reverse over USB, or the laptop's LAN address over Wi-Fi"],
    ];
    for (let i = 0; i < cards.length; i++) {
      const col = i % 4, row = Math.floor(i / 4);
      const x = 0.6 + col * 3.07, y = 1.4 + row * 2.75;
      card(s, x, y, 2.85, 2.5, HEX.light, cards[i][1]);
      await iconCircle(s, cards[i][0], x + 0.25, y + 0.25, 0.62, row === 0 ? HEX.blue : HEX.navy);
      text(s, cards[i][1], { x: x + 0.25, y: y + 1.0, w: 2.4, h: 0.4, fontSize: 16, bold: true, color: C.text2 });
      text(s, cards[i][2], { x: x + 0.25, y: y + 1.42, w: 2.4, h: 1.0, fontSize: 13, color: HEX.gray });
    }
  }

  // ================================================================ 5. architecture
  {
    const s = content("Architecture: one writer for each kind of state", "Design");
    fitImage(s, fig("arch"), 0.5, 1.3, 8.2, 5.55, "System architecture block diagram");
    text(s, bullets([
      [{ text: "Orchestrator ", bold: true }, { text: "owns sessions, photo status and events; never touches bytes" }],
      [{ text: "Image server ", bold: true }, { text: "owns the bytes and their deletion; reports only to the orchestrator (red arrow)" }],
      [{ text: "Auth ", bold: true }, { text: "issues JWTs; other services verify them locally" }],
      [{ text: "Caddy edge ", bold: true }, { text: "terminates TLS 1.3, serves HTTP/3 and HTTP/2" }],
      [{ text: "Redis ", bold: true }, { text: "for short-lived state, " }, { text: "Postgres ", bold: true }, { text: "for accounts" }],
    ], { space: 12 }), { x: 9.0, y: 1.5, w: 3.8, h: 5.2, fontSize: 15 });
  }

  // ================================================================ 6. planes
  {
    const s = content("Four planes, four protocol choices", "Design");
    fitImage(s, fig("stack"), 0.6, 1.25, 12.1, 4.35, "Protocol stack of the four planes");
    card(s, 0.6, 5.8, 12.1, 1.0, HEX.light, "takeaway");
    text(s, [{ text: "Why not one raw TCP protocol?  ", options: { bold: true, color: HEX.navy } },
      { text: "HTTP already gives framing, status codes, auth headers and proxy support, and standard HTTP/3 clients fall back to HTTP/2 on their own. Each plane uses the protocol that suits its traffic." }],
    { x: 0.85, y: 5.9, w: 11.6, h: 0.8, fontSize: 14, valign: "middle" });
  }

  // ================================================================ 7. life of a photo
  {
    const s = content("Life of a photo", "Design");
    fitImage(s, fig("seq"), 0.5, 1.25, 7.9, 5.6, "Sequence diagram of one photo");
    const phases = [
      ["Control  (1 to 3)", "Announce the SHA-256; one atomic Lua call answers UPLOAD, ALREADY_UPLOADED or IN_PROGRESS", HEX.blue],
      ["Data  (4 to 7)", "Chunks with CRC32C, three in parallel; complete; the server recomputes SHA-256 and decodes in a sandbox", HEX.amber],
      ["Internal  (8 to 10)", "Fenced stored report; status and event change in one step; file promoted", HEX.red],
      ["Event  (12 to 14)", "WebSocket frame to every member; thumbnail, then preview, then original", HEX.green],
    ];
    phases.forEach((p, i) => {
      const y = 1.4 + i * 1.35;
      s.addShape(pres.shapes.OVAL, { x: 8.75, y: y + 0.05, w: 0.3, h: 0.3, fill: { color: p[2] }, line: { color: p[2] }, objectName: `phase ${i + 1}` });
      text(s, p[0], { x: 9.2, y, w: 3.6, h: 0.4, fontSize: 16, bold: true, color: C.text2 });
      text(s, p[1], { x: 9.2, y: y + 0.4, w: 3.6, h: 0.9, fontSize: 13, color: HEX.gray });
    });
  }

  // ================================================================ 8. novelty overview
  pres.addSection({ title: "Novelty" });
  {
    const s = content("What is new: six ideas", "Novelty");
    const ideas = [
      ["FaCheckCircle", "N1  Verify before announce", "The server recomputes SHA-256; a photo exists for others only after that check"],
      ["FaHashtag", "N2  Fencing tokens", "Every report names its upload_id; late messages from stalled uploads are rejected"],
      ["FaStream", "N3  One read loop", "Backlog and live events from one XREAD loop, so no event falls in a gap"],
      ["FaBolt", "N4  Transport-aware data plane", "HTTP/3 with fallback, CRC32C chunks, chunk size from measured throughput"],
      ["FaHourglassHalf", "N5  Ephemeral by design", "No shareable links; exact 30-minute expiry; two independent sweepers"],
      ["FaMobileAlt", "N6  Background capture", "Foreground service + content-URI trigger; five checks stop echo uploads"],
    ];
    for (let i = 0; i < 6; i++) {
      const col = i % 3, row = Math.floor(i / 3);
      const x = 0.6 + col * 4.1, y = 1.4 + row * 2.75;
      card(s, x, y, 3.85, 2.5, HEX.light, ideas[i][1]);
      await iconCircle(s, ideas[i][0], x + 0.28, y + 0.3, 0.66, HEX.navy);
      text(s, ideas[i][1], { x: x + 1.1, y: y + 0.3, w: 2.6, h: 0.66, fontSize: 16, bold: true, color: C.text2, valign: "middle" });
      text(s, ideas[i][2], { x: x + 0.28, y: y + 1.15, w: 3.3, h: 1.2, fontSize: 14, color: C.text1 });
    }
  }

  // ================================================================ 9. data plane
  {
    const s = content("Data plane: selective repeat over HTTP/3", "Novelty");
    fitImage(s, fig("chunks"), 0.6, 1.3, 12.1, 3.0, "Resumable chunked upload");
    card(s, 0.6, 4.55, 5.9, 2.25, HEX.light, "formulas");
    text(s, [{ text: "Chunk size from measured throughput", options: { bold: true, color: HEX.navy, breakLine: true } },
      { text: "c = clamp(B̂ × 2 s, 256 KiB, 8 MiB), at least S / 4096", options: { fontFace: "Cambria", italic: true, breakLine: true } },
      { text: " ", options: { breakLine: true, fontSize: 6 } },
      { text: "Retry with exponential backoff and jitter", options: { bold: true, color: HEX.navy, breakLine: true } },
      { text: "dₖ = ½ sₖ + U(0, ½ sₖ),   sₖ = min(60 s, 1 s · 2ᵏ)", options: { fontFace: "Cambria", italic: true } }],
    { x: 0.85, y: 4.75, w: 5.5, h: 1.95, fontSize: 15 });
    text(s, bullets([
      [{ text: "QUIC: ", bold: true }, { text: "a lost packet stalls one stream, not all; the connection ID survives an address change" }],
      [{ text: "Alt-Svc: ", bold: true }, { text: "HTTP/2 over TCP when UDP is blocked, chosen by the client" }],
      [{ text: "Idempotent PUT per chunk: ", bold: true }, { text: "a repeat overwrites itself; the server's bitmap says what is missing" }],
    ], { space: 10 }), { x: 6.9, y: 4.6, w: 5.8, h: 2.2, fontSize: 15 });
  }

  // ================================================================ 10. consistency
  {
    const s = content("Consistency: atomic announce and fencing tokens", "Novelty");
    text(s, bullets([
      [{ text: "Announce is one Redis function: ", bold: true }, { text: "session ACTIVE? member? live-upload limits? quota? then HSETNX" }],
      [{ text: "Two members, same photo, same instant: ", bold: true }, { text: "one gets UPLOAD, the other IN_PROGRESS" }],
      [{ text: "Quota invariant: ", bold: true }, { text: "stored + reserved ≤ 1 GB per session; bytes released exactly once" }],
      [{ text: "Every report names its upload_id: ", bold: true }, { text: "a stale one gets 409 UPLOAD_SUPERSEDED and only its own files are deleted" }],
    ], { space: 12 }), { x: 0.6, y: 1.45, w: 4.6, h: 5.3, fontSize: 15 });
    fitImage(s, fig("fencing"), 5.5, 1.3, 7.2, 3.3, "Fencing by upload_id");
    fitImage(s, fig("photofsm"), 5.5, 4.75, 7.2, 2.1, "Photo status state machine");
  }

  // ================================================================ 11. events
  {
    const s = content("Event plane: no gaps, no duplicates", "Novelty");
    fitImage(s, fig("eventloop"), 0.6, 1.3, 12.1, 3.2, "Replay-then-subscribe versus one XREAD loop");
    text(s, bullets([
      [{ text: "One XREAD BLOCK loop ", bold: true }, { text: "per socket, starting after the client's cursor" }],
      [{ text: "Cursor outside the retained range: ", bold: true }, { text: "close 4409, load the snapshot, continue" }],
      [{ text: "At-least-once + dedupe ", bold: true }, { text: "by event id and hash = exactly-once effect" }],
      [{ text: "Push (FCM, APNs) is only a hint ", bold: true }, { text: "that triggers the same catch-up" }],
    ], { space: 8 }), { x: 0.6, y: 4.75, w: 7.4, h: 2.1, fontSize: 15 });
    stat(s, 8.5, 4.6, 2.2, "1,200", "events, once and in order, across random reconnects", HEX.green);
    stat(s, 10.75, 4.6, 2.0, "2.6 ms", "stream to socket, p50, 10-member session", HEX.blue);
  }

  // ================================================================ 12. security + retention
  {
    const s = content("Security and the 30-minute lifetime", "Novelty");
    fitImage(s, fig("retention"), 0.6, 1.25, 12.1, 2.85, "Retention timeline");
    const rows = [
      ["FaLock", "TLS 1.3 on every hop, Redis included"],
      ["FaKey", "PKCE, single-use state and nonce, exact redirect URIs"],
      ["FaSyncAlt", "Refresh tokens rotate; reuse after 30 s revokes the family"],
      ["FaFingerprint", "Server recomputes SHA-256; 3 mismatches block uploads"],
      ["FaCubes", "Images decoded in a sandboxed process with CPU and memory limits"],
      ["FaEyeSlash", "No links: every download re-checks JWT, membership, status and expiry"],
    ];
    for (let i = 0; i < rows.length; i++) {
      const col = Math.floor(i / 3), row = i % 3;
      const x = 0.6 + col * 6.15, y = 4.35 + row * 0.85;
      await iconCircle(s, rows[i][0], x, y, 0.58, i === 3 ? HEX.red : HEX.blue);
      text(s, rows[i][1], { x: x + 0.78, y, w: 5.1, h: 0.58, fontSize: 15, valign: "middle" });
    }
  }

  // ================================================================ 13. android
  pres.addSection({ title: "Implementation" });
  {
    const s = content("Android: syncing in the background", "Implementation");
    fitImage(s, fig("android"), 0.5, 1.3, 7.8, 4.6, "Android client pipeline");
    text(s, "Android stops ordinary background services, so a foreground service watches the gallery and a WorkManager content-URI trigger fires even after the process is killed.",
      { x: 0.6, y: 6.0, w: 7.6, h: 0.85, fontSize: 13, color: HEX.gray });
    card(s, 8.7, 1.35, 4.0, 5.45, HEX.light, "device results");
    text(s, "Motorola edge 70 fusion, Android 16", { x: 8.95, y: 1.5, w: 3.6, h: 0.4, fontSize: 13, bold: true, color: HEX.navy });
    stat(s, 8.95, 1.8, 3.6, "2.1 s", "test photo stored, PHOTON in the background", HEX.blue);
    stat(s, 8.95, 3.35, 3.6, "1.8 s", "after the app's process was killed", HEX.green);
    stat(s, 8.95, 4.9, 3.6, "14 s", "camera app photo, including the camera finishing its write", HEX.amber);
  }

  // ================================================================ 14. demo screenshots
  {
    const s = content("Demo run: two users, one session", "Implementation");
    const a = fitImage(s, path.join(SCREENS, "demo-share.png"), 0.6, 1.3, 6.0, 4.9, "Alice and Bob share photos with photon-cli");
    const b = fitImage(s, path.join(SCREENS, "demo-receive.png"), 6.75, 1.3, 6.0, 2.6, "Bob receives the events");
    fitImage(s, path.join(SCREENS, "demo-recall.png"), 6.75, 4.05, 6.0, 2.15, "Recall and end");
    text(s, "Real output of the development stack: Alice uploads two photos, a repeat is ALREADY_UPLOADED, Bob's copies have the same SHA-256, and a recalled photo answers 410.",
      { x: 0.6, y: 6.3, w: 12.1, h: 0.55, fontSize: 13, color: HEX.gray });
  }

  // ================================================================ 15. testing
  pres.addSection({ title: "Evaluation" });
  {
    const s = content("Testing, and the bugs only measurement found", "Evaluation");
    stat(s, 0.6, 1.3, 2.9, "155", "server tests: unit, integration, end to end, against real Redis and Postgres", HEX.green);
    stat(s, 0.6, 2.95, 2.9, "135 + 90", "Android unit tests and iOS PhotonKit tests", HEX.blue);
    stat(s, 0.6, 4.6, 2.9, "24k lines", "Python, Lua, Kotlin, Swift and deployment config", HEX.navy);
    const rows = [
      [{ text: "Symptom", options: { bold: true, color: "FFFFFF", fill: { color: HEX.blue } } }, { text: "Cause", options: { bold: true, color: "FFFFFF", fill: { color: HEX.blue } } }, { text: "Fix", options: { bold: true, color: "FFFFFF", fill: { color: HEX.blue } } }],
      ["Phone's WebSocket closed with 1011 every few seconds", "Redis client's 5 s socket timeout cut XREAD BLOCK 15000", "No socket timeout on the blocking pool, TCP keepalive"],
      ["150 sockets at once: 50 closed", "Default pool fails the 101st concurrent command", "Blocking pools of 500 and 200; callers wait"],
      ["Phone on Wi-Fi could not reach the server", "App built for 127.0.0.1, which is the phone itself", "Build for the laptop's LAN address"],
      ["Uploads only while the app was open", "Background service stopped by Android", "Foreground service + content-URI trigger"],
    ].map((r, i) => (i === 0 ? r : r.map((c) => ({ text: c, options: { fill: { color: i % 2 === 0 ? HEX.light : "FFFFFF" } } }))));
    s.addTable(rows, { x: 3.8, y: 1.4, w: 8.9, colW: [3.0, 3.0, 2.9], fontSize: 13, color: "1E1E1E", border: { type: "solid", pt: 0.5, color: "C9D6E6" }, valign: "middle", margin: 0.08, rowH: 0.7 });
  }

  // ================================================================ 16. performance
  {
    const s = content("Performance on one laptop", "Evaluation");
    const axis = { catAxisLabelColor: "444444", valAxisLabelColor: "444444", catAxisLabelFontSize: 11, valAxisLabelFontSize: 11,
      catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt", valGridLine: { color: HEX.grid, size: 0.5 }, catGridLine: { style: "none" },
      titleFontFace: "+mn-lt", titleFontSize: 14, titleColor: HEX.navy, legendFontFace: "+mn-lt", legendFontSize: 11, dataLabelFontFace: "+mn-lt", dataLabelFontSize: 11 };
    s.addChart(pres.charts.LINE, [
      { name: "measured mean latency (ms)", labels: ["1", "16", "64"], values: [1.66, 19.16, 75.48] },
      { name: "Little's law L / λ (ms)", labels: ["1", "16", "64"], values: [1.83, 22.99, 90.91] },
    ], { x: 0.5, y: 1.3, w: 6.1, h: 4.1, ...axis, showTitle: true, title: "Announce latency vs. concurrent clients", chartColors: [HEX.blue, HEX.red],
      lineSize: 2, lineDataSymbol: "circle", lineDataSymbolSize: 7, showLegend: true, legendPos: "b", showValue: true, dataLabelPosition: "t", dataLabelColor: "444444",
      valAxisTitle: "ms", showValAxisTitle: true, valAxisTitleFontSize: 11, valAxisTitleColor: "444444", catAxisTitle: "clients", showCatAxisTitle: true, catAxisTitleFontSize: 11, catAxisTitleColor: "444444" });
    s.addChart(pres.charts.BAR, [
      { name: "announce", labels: ["0.4 MB", "1.5 MB", "3.1 MB", "8.7 MB"], values: [2.56, 2.95, 2.50, 2.58] },
      { name: "chunks", labels: ["0.4 MB", "1.5 MB", "3.1 MB", "8.7 MB"], values: [7.67, 11.35, 21.05, 38.47] },
      { name: "complete (verify, decode, report)", labels: ["0.4 MB", "1.5 MB", "3.1 MB", "8.7 MB"], values: [169, 216, 298, 351] },
    ], { x: 6.8, y: 1.3, w: 6.0, h: 4.1, ...axis, barDir: "bar", barGrouping: "stacked", showTitle: true, title: "Upload time by photo size (ms)", chartColors: [HEX.amber, HEX.blue, HEX.green],
      showLegend: true, legendPos: "b", showValue: false });
    stat(s, 0.6, 5.5, 3.0, "~700 req/s", "announce saturates; latency then grows as Little's law predicts", HEX.blue);
    stat(s, 3.8, 5.5, 3.0, "0.08 ms", "one atomic state transition inside Redis (p50)", HEX.navy);
    stat(s, 7.0, 5.5, 2.8, "24.8 / s", "photos stored per second with 8 members uploading at once", HEX.green);
    stat(s, 10.0, 5.5, 2.7, "159 ms", "upload start to the last of 10 members", HEX.amber);
  }

  // ================================================================ 17. comparison
  {
    const s = content("Comparison with existing tools", "Evaluation");
    const head = ["Feature", "PHOTON", "Google Photos", "iCloud SPL", "AirDrop", "Quick Share", "WhatsApp"];
    const T = "✓", X = "✗", P = "Partial";
    const data = [
      ["Shares new camera photos automatically", T, P, T, X, X, X],
      ["Android and iOS in one group", T, T, X, X, X, T],
      ["Members need not be nearby", T, T, T, X, X, T],
      ["No long-lived server copy", T, X, X, T, T, P],
      ["Original quality by default", T, P, T, T, T, X],
      ["Thumbnail first, original later", T, T, T, X, X, T],
      ["Uploader can recall a photo", T, T, T, X, X, T],
      ["Time-boxed group, code or QR", T, X, X, X, X, X],
      ["Open source, self-hostable", T, X, X, X, X, X],
      ["Score / 9", "9", "5", "5", "2", "2", "4.5"],
    ];
    const cell = (v, r, c) => {
      const last = r === data.length - 1;
      const color = v === T ? HEX.green : v === X ? HEX.red : v === P ? "C77700" : "1E1E1E";
      return { text: v, options: { align: c === 0 ? "left" : "center", bold: last || v === T || v === X, color, fontFace: v === T || v === X ? "Segoe UI Symbol" : undefined,
        fill: { color: last ? "DCE8F6" : r % 2 === 1 ? HEX.light : "FFFFFF" }, fontSize: v === T || v === X ? 16 : 13 } };
    };
    const rows = [head.map((h, c) => ({ text: h, options: { bold: true, color: "FFFFFF", fill: { color: c === 1 ? HEX.navy : HEX.blue }, align: c === 0 ? "left" : "center", fontSize: 13 } })),
      ...data.map((r, ri) => r.map((v, c) => cell(v, ri, c)))];
    s.addTable(rows, { x: 0.6, y: 1.35, w: 12.1, colW: [4.0, 1.35, 1.35, 1.35, 1.35, 1.35, 1.35], border: { type: "solid", pt: 0.5, color: "C9D6E6" }, valign: "middle", margin: 0.05, rowH: 0.44 });
    text(s, "Based on each product's documented default behaviour. Existing tools are ahead on end-to-end encryption, long-term albums, video and offline transfer.",
      { x: 0.6, y: 6.62, w: 12.1, h: 0.3, fontSize: 12, italic: true, color: HEX.gray });
  }

  // ================================================================ 18. limitations + future
  pres.addSection({ title: "Conclusion" });
  {
    const s = content("Limitations and future work", "Conclusion");
    card(s, 0.6, 1.4, 5.9, 5.35, "FCEBEB", "limitations");
    text(s, "Limitations", { x: 0.9, y: 1.6, w: 5.3, h: 0.45, fontSize: 20, bold: true, color: HEX.red });
    text(s, bullets([
      "No end-to-end encryption: the server can read photos during their 30 minutes",
      "HTTP/3 is configured at the edge but was not measured on a phone",
      "iOS core library is built and tested; the app needs Xcode to compile",
      "Benchmarks ran on one laptop; Docker deployment written, not run",
    ], { space: 12 }), { x: 0.9, y: 2.2, w: 5.3, h: 4.3, fontSize: 15 });
    card(s, 6.8, 1.4, 5.9, 5.35, "EAF6EA", "future work");
    text(s, "Future work", { x: 7.1, y: 1.6, w: 5.3, h: 0.45, fontSize: 20, bold: true, color: HEX.green });
    text(s, bullets([
      "Per-session keys for end-to-end encryption; thumbnails made on the phone",
      "Measure HTTP/3 against HTTP/2 across a real Wi-Fi to LTE handover",
      "Peer-to-peer delivery on the same Wi-Fi (WebRTC), server for verification",
      "Object storage and Redis Cluster; the keys already carry {sid} hash tags",
      "Video and Live Photos",
    ], { space: 12 }), { x: 7.1, y: 2.2, w: 5.3, h: 4.3, fontSize: 15 });
  }

  // ================================================================ 19. team
  {
    const s = content("Team and contributions", "Conclusion");
    const hdr = ["Name", "Enroll. No.", team.members.some((m) => m.mobile) ? "Email / Mobile" : "Email", "Contribution"].map((h) => ({ text: h, options: { bold: true, color: "FFFFFF", fill: { color: HEX.blue } } }));
    const rows = [hdr, ...team.members.map((m, i) => [m.name, m.enrollment, m.mobile ? `${m.email}\n${m.mobile}` : m.email, m.contribution]
      .map((v) => ({ text: v, options: { fill: { color: i % 2 === 1 ? HEX.light : "FFFFFF" } } })))];
    s.addTable(rows, { x: 0.6, y: 1.4, w: 12.1, colW: [2.3, 1.6, 3.3, 4.9], fontSize: 12, color: "1E1E1E", border: { type: "solid", pt: 0.5, color: "C9D6E6" }, valign: "middle", margin: 0.07 });
  }

  // ================================================================ 20. closing
  {
    const s = pres.addSlide({ masterName: "DARK", sectionTitle: "Conclusion" });
    s.addText("Thank you", { placeholder: "title" });
    s.addText("Questions?", { placeholder: "body" });
    text(s, "Every hard part of a “simple” sharing feature was the network between the phones. Each was solved with a standard mechanism: QUIC, selective retransmission, idempotent requests, leases, fencing tokens and a durable event log.",
      { x: 0.8, y: 4.75, w: 11.7, h: 1.2, fontSize: 17, color: "DCE6F2", italic: true });
    text(s, `Group ${team.group_no}  ·  ${team.institute_short}  ·  ${team.course}`, { x: 0.8, y: 6.5, w: 11.7, h: 0.4, fontSize: 13, color: "9FB4CF" });
  }

  await pres.writeFile({ fileName: OUT });
  await applyTheme(OUT, THEME);
  console.log(`wrote ${path.relative(ROOT, OUT)}`);
}

// Writes THEME's colours and name into ppt/theme/theme1.xml (pptxgenjs writes Office's palette), and keeps
// only the first <a:pPr> of each paragraph: pptxgenjs writes one per text run, which the schema does not allow.
async function applyTheme(file, theme) {
  const JSZip = require(require.resolve("jszip", { paths: [require.resolve("pptxgenjs")] }));
  const zip = await JSZip.loadAsync(fs.readFileSync(file));
  for (const n of Object.keys(zip.files).filter((f) => /^ppt\/slides\/slide\d+\.xml$/.test(f))) {
    let xml = await zip.file(n).async("string");
    xml = xml.replace(/<a:p>([\s\S]*?)<\/a:p>/g, (m, inner) => {
      let seen = false;
      const fixed = inner.replace(/<a:pPr\b[^>]*?(?:\/>|>[\s\S]*?<\/a:pPr>)/g, (pp) => (seen ? "" : ((seen = true), pp)));
      return `<a:p>${fixed}</a:p>`;
    });
    zip.file(n, xml);
  }
  const names = Object.keys(zip.files).filter((n) => /^ppt\/theme\/theme\d+\.xml$/.test(n));
  for (const n of names) {
    let xml = await zip.file(n).async("string");
    for (const [slot, hex] of Object.entries(theme.colors)) {
      xml = xml.replace(new RegExp(`<a:${slot}>[\\s\\S]*?</a:${slot}>`), `<a:${slot}><a:srgbClr val="${hex}"/></a:${slot}>`);
    }
    xml = xml.replace(/<a:clrScheme name="[^"]*">/, `<a:clrScheme name="${theme.name}">`).replace(/<a:theme([^>]*) name="[^"]*"/, `<a:theme$1 name="${theme.name}"`);
    zip.file(n, xml);
  }
  fs.writeFileSync(file, await zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE" }));
}

main().catch((e) => { console.error(e); process.exit(1); });

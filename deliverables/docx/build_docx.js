// Builds the Word report (PHOTON_Report.docx) from the same LaTeX source as the PDF.
//
//   python3 tools/tex2docx_prep.py          -> report/build/docx/main-pandoc.tex, widths.json
//   pandoc -f latex -t json ... ast.json    -> pandoc's parse of the report
//   node docx/build_docx.js                 -> this script: title page from team.json, then the AST
//
// The table of contents needs page numbers, which only a layout engine knows, so the script builds the
// document, converts it to PDF with LibreOffice, finds the page of every heading, and builds it again.
"use strict";
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const {
  Document, Packer, Paragraph, TextRun, ImageRun, Table, TableRow, TableCell, WidthType, BorderStyle,
  ShadingType, AlignmentType, HeadingLevel, Header, Footer, PageNumber, TabStopType, LevelFormat,
  Bookmark, InternalHyperlink, ExternalHyperlink, Tab, VerticalAlign, TableLayoutType, SectionType,
} = require("docx");

const ROOT = path.resolve(__dirname, "..");
const REPORT = path.join(ROOT, "report");
const BUILD = path.join(REPORT, "build", "docx");
const OUT = path.join(ROOT, "docx", "PHOTON_Report.docx");
const team = JSON.parse(fs.readFileSync(path.join(ROOT, "team.json"), "utf8"));

// ------------------------------------------------------------------ look
const FONT = "Times New Roman";
const MONO = "Courier New";
const C = {
  navy: "1B365D", blue: "2E6DB4", head: "2F5F98", rowalt: "EEF4FB", gray: "6F6F6F", grayline: "8A8A8A",
  green: "2E7D32", red: "C62828", orange: "E08A2E", codebg: "F6F8FA", boxline: "6E9CD2", boxfill: "EAF1FA",
};
const BOX = {
  blue: { fill: "EAF1FA", line: "6E9CD2", tab: "2F5F98" },
  red: { fill: "FCEBEB", line: "D9534F", tab: "C62828" },
  green: { fill: "EAF6EA", line: "5CB85C", tab: "2E7D32" },
  yellow: { fill: "FFF6D5", line: "D4A017", tab: "9A7400" },
};
const PAGE_W = 11906, PAGE_H = 16838, MARGIN = 1440;
const TEXT_W = PAGE_W - 2 * MARGIN;          // 9026 DXA = 6.27 in
const TEXT_IN = TEXT_W / 1440;
const BODY = 22, SMALL = 20, TABLE = 19, CODE = 15;   // half-points

// ------------------------------------------------------------------ state
const widths = JSON.parse(fs.readFileSync(path.join(BUILD, "widths.json"), "utf8"));
let tableIndex = 0, figNo = 0, tabNo = 0, listInstance = 0;
const counters = [0, 0, 0];
const headings = [];   // {level, number, text, id}

// ------------------------------------------------------------------ helpers
const run = (text, o = {}) => new TextRun({ text, font: o.font || (o.mono ? MONO : FONT), size: o.size || BODY, bold: o.bold,
  italics: o.italics, color: o.color, superScript: o.sup, subScript: o.sub, shading: o.shading });
const none = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
const line = (color, size = 6) => ({ style: BorderStyle.SINGLE, size, color });
const rule = (color = C.head, before = 60, after = 60) => new Paragraph({ spacing: { before, after },
  border: { bottom: { style: BorderStyle.SINGLE, size: 6, color, space: 1 } }, children: [] });

// Pixel size and type of a PNG or JPEG (the app screenshots are JPEG).
function imageInfo(file) {
  const b = fs.readFileSync(file);
  if (b[0] === 0xff && b[1] === 0xd8) {
    for (let i = 2; i + 9 < b.length;) {
      if (b[i] !== 0xff) { i++; continue; }
      const m = b[i + 1];
      if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return { type: "jpg", h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
      i += 2 + b.readUInt16BE(i + 2);
    }
    throw new Error(`no frame header in ${file}`);
  }
  return { type: "png", w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}
function image(file, widthIn) {
  const { type, w, h } = imageInfo(file);
  let wi = widthIn, hi = widthIn * h / w;
  if (hi > 8.6) { hi = 8.6; wi = hi * w / h; }
  return new ImageRun({ type, data: fs.readFileSync(file), transformation: { width: Math.round(wi * 96), height: Math.round(hi * 96) },
    altText: { title: path.basename(file), description: path.basename(file), name: path.basename(file) } });
}
function widthFactor(attrs) {
  const w = (attrs.find(([k]) => k === "width") || [null, "\\linewidth"])[1];
  const m = /^([0-9.]*)\\(line|text)width$/.exec(w);
  return m ? (m[1] ? parseFloat(m[1]) : 1) : 1;
}
function plain(inl) {   // inlines -> plain text
  return inl.map((x) => {
    switch (x.t) {
      case "Str": return x.c; case "Space": case "SoftBreak": return " "; case "LineBreak": return " ";
      case "Code": return x.c[1]; case "Math": return mathText(x.c[1]);
      case "Quoted": return "“" + plain(x.c[1]) + "”";
      case "Emph": case "Strong": case "Underline": case "SmallCaps": case "Strikeout": case "Superscript": case "Subscript": return plain(x.c);
      case "Span": case "Link": return plain(x.c[1]);
      case "Cite": return plain(x.c[1]);
      default: return "";
    }
  }).join("");
}

// ------------------------------------------------------------------ math (LaTeX subset -> runs)
const SYM = { alpha: "α", beta: "β", gamma: "γ", delta: "δ", epsilon: "ε", lambda: "λ", mu: "μ", pi: "π", sigma: "σ",
  tau: "τ", Delta: "Δ", Sigma: "Σ", le: "≤", leq: "≤", ge: "≥", geq: "≥", neq: "≠", ne: "≠", approx: "≈", to: "→",
  rightarrow: "→", Rightarrow: "⇒", leftarrow: "←", iff: "⟺", wedge: "∧", vee: "∨", notin: "∉", in: "∈", times: "×",
  cdot: "·", ldots: "…", dots: "…", cdots: "⋯", infty: "∞", lceil: "⌈", rceil: "⌉", lfloor: "⌊", rfloor: "⌋",
  langle: "⟨", rangle: "⟩", lVert: "‖", rVert: "‖", pm: "±", sum: "Σ", min: "min", max: "max", log: "log",
  quad: " ", qquad: "  ", ",": " ", ";": " ", ":": " ", "!": "", " ": " ",
  "{": "{", "}": "}", "%": "%", "_": "_", "#": "#", "&": "&", "|": "‖", left: "", right: "", big: "", Big: "",
  colon: ":", mid: "|", S: "§" };
const SPACED = new Set(["=", "<", ">", "+", "−", "≤", "≥", "≠", "≈", "→", "⇒", "←", "⟺", "∧", "∉", "∈", "×"]);
const TEXTCMD = { text: {}, mathrm: {}, textrm: {}, operatorname: {}, mbox: {}, textbf: { bold: true }, mathbf: { bold: true },
  texttt: { mono: true }, mathit: { italics: true }, textit: { italics: true } };

function mathTokens(s) {
  const t = []; let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === "\\") {
      const m = /^\\([A-Za-z]+|.)/.exec(s.slice(i)); t.push({ cmd: m[1] }); i += m[0].length;
      if (/^[A-Za-z]+$/.test(m[1])) while (s[i] === " ") i++;
    } else if (c === "{") {
      let d = 0, j = i;
      for (; j < s.length; j++) { if (s[j] === "\\") { j++; continue; } if (s[j] === "{") d++; else if (s[j] === "}" && --d === 0) break; }
      t.push({ group: s.slice(i + 1, j) }); i = j + 1;
    } else { t.push({ ch: c }); i++; }
  }
  return t;
}
function mathAtoms(s, f, out) {   // appends {text, italics, bold, sub, sup, mono}
  const t = mathTokens(s); let i = 0;
  const arg = () => { const x = t[i++]; return x ? (x.group !== undefined ? x.group : x.ch !== undefined ? x.ch : "\\" + x.cmd) : ""; };
  while (i < t.length) {
    const x = t[i++];
    if (x.group !== undefined) { mathAtoms(x.group, f, out); continue; }
    if (x.ch !== undefined) {
      if (x.ch === " " || x.ch === "\n") continue;
      if (x.ch === "^" || x.ch === "_") { mathAtoms(arg(), { ...f, [x.ch === "^" ? "sup" : "sub"]: true }, out); continue; }
      if (x.ch === "~") { out.push({ ...f, text: " " }); continue; }
      let ch = x.ch === "-" ? "−" : x.ch;
      const spaced = SPACED.has(ch) && !f.sub && !f.sup;
      out.push({ ...f, text: spaced ? " " + ch + " " : ch, italics: /[A-Za-z]/.test(ch) && !f.upright ? true : f.italics });
      continue;
    }
    const cmd = x.cmd;
    if (TEXTCMD[cmd]) { const a = arg(); out.push({ ...f, ...TEXTCMD[cmd], text: a.replace(/\\_/g, "_").replace(/\\,/g, " ").replace(/\{,\}/g, ","), italics: TEXTCMD[cmd].italics || false }); continue; }
    if (cmd === "frac" || cmd === "tfrac" || cmd === "dfrac") {
      const a = arg(), b = arg();
      if (a.trim() === "1" && b.trim() === "2") { out.push({ ...f, text: "\u00BD" }); continue; }
      const wrap = (z) => (/^[A-Za-z0-9]$/.test(z.trim()) ? z : "(" + z + ")");
      mathAtoms(wrap(a), f, out); out.push({ ...f, text: "/" }); mathAtoms(wrap(b), f, out); continue;
    }
    if (cmd === "hat" || cmd === "bar" || cmd === "tilde") {
      mathAtoms(arg(), f, out); out[out.length - 1].text += { hat: "̂", bar: "̄", tilde: "̃" }[cmd]; continue;
    }
    if (cmd === "mathrel" || cmd === "mathbin" || cmd === "mathop") { mathAtoms(arg(), { ...f, upright: true }, out); continue; }
    if (cmd === "eqtag") { arg(); continue; }
    if (SYM[cmd] !== undefined) {
      const sym = SYM[cmd];
      const spaced = SPACED.has(sym) && !f.sub && !f.sup;
      out.push({ ...f, text: spaced ? " " + sym + " " : sym, italics: false });
      continue;
    }
    // unknown command: drop it, keep any group argument that follows
  }
  return out;
}
function mathText(tex) { return mathAtoms(tex, {}, []).map((a) => a.text).join(""); }
function mathRuns(tex, base = {}) {
  const atoms = mathAtoms(tex, {}, []);
  const merged = [];
  for (const a of atoms) {
    const last = merged[merged.length - 1];
    if (last && ["italics", "bold", "sub", "sup", "mono"].every((k) => !!last[k] === !!a[k])) last.text += a.text;
    else merged.push({ ...a });
  }
  return merged.filter((a) => a.text).map((a) => run(a.text, { size: base.size || BODY, italics: a.italics, bold: a.bold || base.bold,
    sub: a.sub, sup: a.sup && !a.sub, mono: a.mono, color: base.color }));
}

// ------------------------------------------------------------------ inlines
function inlines(list, f = {}) {
  const out = [];
  for (let k = 0; k < list.length; k++) {
    const x = list[k];
    switch (x.t) {
      case "Str": {
        let text = x.c, color = f.color, bold = f.bold;
        let font;
        if (text === "✓") { color = C.green; bold = true; font = "Segoe UI Symbol"; }
        if (text === "✗") { color = C.red; bold = true; font = "Segoe UI Symbol"; }
        out.push(run(text, { ...f, color, bold, font }));
        break;
      }
      case "Space": case "SoftBreak": out.push(run(" ", f)); break;
      case "LineBreak": out.push(new TextRun({ break: 1 })); break;
      case "Emph": out.push(...inlines(x.c, { ...f, italics: !f.italics })); break;
      case "Strong": out.push(...inlines(x.c, { ...f, bold: true })); break;
      case "Underline": case "SmallCaps": case "Strikeout": out.push(...inlines(x.c, f)); break;
      case "Superscript": out.push(...inlines(x.c, { ...f, sup: true })); break;
      case "Subscript": out.push(...inlines(x.c, { ...f, sub: true })); break;
      case "Code": out.push(run(x.c[1], { ...f, mono: true, size: (f.size || BODY) - 3 })); break;
      case "Math": out.push(...mathRuns(x.c[1], f)); break;
      case "Quoted": out.push(run("“", f), ...inlines(x.c[1], f), run("”", f)); break;
      case "Span": case "Cite": out.push(...inlines(x.c[x.t === "Span" ? 1 : 1], f)); break;
      case "Link": {
        const url = x.c[2][0];
        const kids = inlines(x.c[1], { ...f, color: "2E6DB4" });
        if (/^https?:/.test(url)) out.push(new ExternalHyperlink({ link: url, children: kids }));
        else out.push(...kids);
        break;
      }
      case "Image": out.push(image(path.join(REPORT, x.c[2][0]), widthFactor(x.c[0][2]) * TEXT_IN)); break;
      default: break;
    }
  }
  return out;
}

// ------------------------------------------------------------------ blocks
function para(children, o = {}) {
  return new Paragraph({ children, alignment: o.align || AlignmentType.JUSTIFIED, spacing: { after: o.after ?? 110, before: o.before || 0, line: o.line || 252 },
    indent: o.indent, numbering: o.numbering, keepNext: o.keepNext, border: o.border, shading: o.shading, tabStops: o.tabStops });
}

function equation(tex) {
  const m = /\\eqtag\{([^}]*)\}/.exec(tex);
  const tag = m ? `(${m[1]})` : "";
  return para([new TextRun({ children: [new Tab()] }), ...mathRuns(tex.replace(/\\eqtag\{[^}]*\}/, "")), new TextRun({ children: [new Tab()] }), run(tag)],
    { align: AlignmentType.LEFT, before: 60, after: 120, tabStops: [{ type: TabStopType.CENTER, position: Math.round(TEXT_W / 2) }, { type: TabStopType.RIGHT, position: TEXT_W }] });
}

// splits a paragraph's inlines at display math
function paraBlocks(inl, o = {}) {
  const out = []; let cur = [];
  const flush = () => {
    while (cur.length && ["Space", "SoftBreak"].includes(cur[0].t)) cur.shift();
    if (cur.length) out.push(para(inlines(cur, o.f || {}), o));
    cur = [];
  };
  for (const x of inl) {
    if (x.t === "Math" && x.c[0].t === "DisplayMath") { flush(); out.push(equation(x.c[1])); } else cur.push(x);
  }
  flush();
  return out;
}

let runIn = null;   // \paragraph{...} title waiting for its paragraph

function blocks(list, ctx = {}) {
  const out = [];
  for (const b of list) {
    switch (b.t) {
      case "Header": out.push(...header(b)); break;
      case "Para": case "Plain": {
        let inl = b.c;
        const first = inl[0] && inl[0].t === "Str" ? inl[0].c : "";
        if (first.startsWith("BIBITEM@@")) {
          const n = first.split("@@")[1];
          out.push(para([run(`[${n}]`, { size: SMALL }), new TextRun({ children: [new Tab()] }), ...inlines(inl.slice(2), { size: SMALL })],
            { align: AlignmentType.LEFT, indent: { left: 560, hanging: 560 }, after: 40, line: 240, tabStops: [{ type: TabStopType.LEFT, position: 560 }] }));
          break;
        }
        if (runIn) { inl = [{ t: "Strong", c: runIn }, { t: "Space" }, ...inl]; runIn = null; }
        out.push(...paraBlocks(inl, { ...ctx.paraOpts, f: ctx.f }));
        break;
      }
      case "BulletList": case "OrderedList": out.push(...listBlocks(b, ctx, 0)); break;
      case "Table": out.push(...table(b, ctx)); break;
      case "Div": out.push(...blocks(b.c[1], ctx)); break;
      case "Figure": out.push(...figure(b)); break;
      case "BlockQuote": out.push(...quote(b.c, ctx)); break;
      case "CodeBlock": out.push(codeBlock(b.c[1])); break;
      case "HorizontalRule": out.push(rule()); break;
      default: break;
    }
  }
  return out;
}

function header(b) {
  const [level, attr, inl] = b.c;
  if (level >= 4) { runIn = inl; return []; }
  counters[level - 1]++; for (let i = level; i < 3; i++) counters[i] = 0;
  const number = counters.slice(0, level).join(".");
  const id = "h_" + number.replace(/\./g, "_");
  const text = plain(inl);
  headings.push({ level, number, text, id });
  const size = [28, 23, 22][level - 1];
  const kids = [new Bookmark({ id, children: [run(level === 1 ? `${number}.  ${text}` : `${number}  ${text}`, { bold: true, italics: level === 3, color: C.head, size })] })];
  const p = new Paragraph({ heading: [HeadingLevel.HEADING_1, HeadingLevel.HEADING_2, HeadingLevel.HEADING_3][level - 1], children: kids, keepNext: true,
    spacing: { before: level === 1 ? 300 : 200, after: level === 1 ? 140 : 80 },
    border: level === 1 ? { bottom: { style: BorderStyle.SINGLE, size: 8, color: C.head, space: 3 } } : undefined });
  return [p];
}

function listBlocks(b, ctx, level) {
  const ordered = b.t === "OrderedList";
  const items = ordered ? b.c[1] : b.c;
  let ref = ordered ? "numbers" : "bullets";
  const firstStr = ordered && items[0] && items[0][0] && items[0][0].c && items[0][0].c[0];
  let steps = false;
  if (firstStr && firstStr.t === "Str" && firstStr.c.startsWith("STEPLIST@@")) { steps = true; ref = "steps"; }
  const instance = ++listInstance;
  const out = [];
  for (const item of items) {
    item.forEach((blk, k) => {
      if ((blk.t === "Para" || blk.t === "Plain") && k === 0) {
        let inl = blk.c;
        if (steps && inl[0] && inl[0].t === "Str" && inl[0].c.startsWith("STEPLIST@@")) {
          inl = inl.slice(1); while (inl[0] && inl[0].t === "Space") inl = inl.slice(1);
        }
        out.push(para(inlines(inl, ctx.f || {}), { numbering: { reference: ref, level, instance }, after: 50, align: AlignmentType.JUSTIFIED }));
      } else if (blk.t === "BulletList" || blk.t === "OrderedList") {
        out.push(...listBlocks(blk, ctx, Math.min(level + 1, 2)));
      } else {
        out.push(...blocks([blk], { ...ctx, paraOpts: { indent: { left: 720 * (level + 1) } } }));
      }
    });
  }
  if (out.length) out[out.length - 1] = out[out.length - 1];
  return out.concat([new Paragraph({ spacing: { after: 40 }, children: [] })]);
}

function caption(kind, inl) {
  const n = kind === "Figure" ? ++figNo : ++tabNo;
  return para([run(`${kind} ${n}: `, { size: SMALL, bold: true }), ...inlines(inl, { size: SMALL })],
    { align: AlignmentType.CENTER, before: kind === "Table" ? 100 : 40, after: 200 });
}
function captionInlines(cap) { const blks = cap[1] || []; return blks.length ? blks[0].c : []; }

function figure(b) {
  const [, cap, content] = b.c;
  const out = [];
  for (const blk of content) {
    if ((blk.t === "Plain" || blk.t === "Para") && blk.c.some((x) => x.t === "Image")) {
      out.push(new Paragraph({ alignment: AlignmentType.CENTER, keepNext: true, spacing: { before: 120, after: 60 }, children: inlines(blk.c.filter((x) => x.t === "Image")) }));
    } else if (blk.t === "Table") {
      tableIndex++;   // keep the column-spec list in step
      const rows = [...blk.c[3][1], ...blk.c[4].flatMap((bd) => [...bd[2], ...bd[3]])];
      const cellInl = (cell) => cell[4].flatMap((x) => (x.c && Array.isArray(x.c) ? x.c : []));
      const n = rows[0][1].length;
      const stops = Array.from({ length: n }, (_, i) => ({ type: TabStopType.CENTER, position: Math.round(TEXT_W * (2 * i + 1) / (2 * n)) }));
      for (const r of rows) {
        const cells = r[1].map(cellInl);
        const hasImg = cells.some((c) => c.some((x) => x.t === "Image"));
        const kids = [];
        cells.forEach((c) => { kids.push(new TextRun({ children: [new Tab()] })); kids.push(...inlines(hasImg ? c.filter((x) => x.t === "Image") : c, { size: SMALL })); });
        out.push(new Paragraph({ tabStops: stops, keepNext: true, spacing: { before: hasImg ? 120 : 0, after: 40 }, children: kids }));
      }
    } else out.push(...blocks([blk]));
  }
  out.push(caption("Figure", captionInlines(cap)));
  return out;
}

function colWidths(spec, n) {
  let w = spec && spec.length === n ? spec : Array(n).fill("X");
  const pad = 0.35;
  const fixed = w.map((x) => (/^[0-9.]+/.test(x) ? Math.round((parseFloat(x) + pad) * 567) : null));
  const flex = w.filter((x, i) => fixed[i] === null).length;
  const used = fixed.reduce((a, x) => a + (x || 0), 0);
  let rest = Math.max(TEXT_W - used, flex * 900);
  let dx = fixed.map((x) => (x === null ? Math.floor(rest / flex) : x));
  const total = dx.reduce((a, x) => a + x, 0);
  if (total > TEXT_W) dx = dx.map((x) => Math.floor(x * TEXT_W / total));
  const sum = dx.reduce((a, x) => a + x, 0); dx[dx.length - 1] += TEXT_W - sum;
  return { dx, center: w.map((x) => /c$/.test(x)) };
}

function cellText(blks) { return blks.map((b) => (b.c && Array.isArray(b.c) ? plain(b.c) : "")).join(" ").trim(); }

function table(b, ctx = {}) {
  const [attr, cap, colspecs, head, bodies] = b.c;
  const n = colspecs.length;
  const spec = widths[tableIndex++];
  const { dx, center } = colWidths(spec, n);
  let rows = [...head[1], ...bodies.flatMap((bd) => [...bd[2], ...bd[3]])];
  const plainTable = !!ctx.plainTable;
  const narrow = n >= 6;
  const out = [];
  const trs = rows.map((r, ri) => {
    const cells = r[1];
    let emph = false;
    let col = 0;
    const isHead = ri === 0 && !plainTable;
    const tcs = cells.map((cell) => {
      const span = cell[3];
      let blks = cell[4];
      // ROWEM@@ marks a row that followed \hline (totals, results)
      if (blks[0] && blks[0].c && blks[0].c[0] && blks[0].c[0].t === "Str" && blks[0].c[0].c.startsWith("ROWEM@@")) {
        emph = true;
        const s = blks[0].c[0].c.slice(7);
        blks = [{ ...blks[0], c: s ? [{ t: "Str", c: s }, ...blks[0].c.slice(1)] : blks[0].c.slice(1) }];
      }
      const w = dx.slice(col, col + span).reduce((a, x) => a + x, 0);
      const centered = plainTable || (center[col] && span === 1);
      col += span;
      const txt = cellText(blks);
      const color = isHead ? "FFFFFF" : txt === "Partial" ? C.orange : undefined;
      let kids = [];
      for (const blk of blks) {
        if (blk.t === "Para" || blk.t === "Plain") {
          // keepNext on every row keeps the table on one page together with its caption below
          kids.push(new Paragraph({ alignment: centered ? AlignmentType.CENTER : AlignmentType.LEFT, spacing: { after: 0, line: 240 }, keepNext: !plainTable || undefined,
            children: inlines(blk.c, { size: plainTable ? SMALL : (narrow && isHead ? 17 : TABLE), bold: isHead || undefined, color }) }));
        } else kids.push(...blocks([blk], { f: { size: TABLE } }));
      }
      if (!kids.length) kids = [new Paragraph({ children: [] })];
      return { w, span, kids };
    });
    return { tcs, emph, isHead };
  });
  let bodyRow = 0;
  const tableRows = trs.map((r) => {
    const fill = r.isHead ? C.blue : r.emph ? C.boxfill : (plainTable ? undefined : (bodyRow++ % 2 === 0 ? "FFFFFF" : C.rowalt));
    return new TableRow({ tableHeader: r.isHead, cantSplit: true, children: r.tcs.map((c) => new TableCell({
      width: { size: c.w, type: WidthType.DXA }, columnSpan: c.span > 1 ? c.span : undefined,
      shading: fill ? { type: ShadingType.CLEAR, color: "auto", fill } : undefined,
      margins: { top: 45, bottom: 45, left: narrow ? 50 : 90, right: narrow ? 50 : 90 }, verticalAlign: plainTable ? VerticalAlign.BOTTOM : VerticalAlign.TOP,
      borders: plainTable ? { top: none, bottom: none, left: none, right: none } : { top: r.emph ? line(C.blue, 6) : none, bottom: none, left: none, right: none },
      children: c.kids })) });
  });
  const border = plainTable ? none : line(C.blue, 8);
  out.push(new Table({ width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: dx, layout: TableLayoutType.FIXED,
    borders: { top: border, bottom: border, left: border, right: border, insideHorizontal: none, insideVertical: plainTable ? none : line("BFD3EA", 4) },
    rows: tableRows }));
  out.push(plainTable ? new Paragraph({ spacing: { after: 80 }, children: [] }) : caption("Table", captionInlines(cap)));
  return out;
}

function boxTable(kind, title, children) {
  const c = BOX[kind] || BOX.blue;
  const kids = [];
  if (title) kids.push(new Paragraph({ spacing: { after: 80 }, children: [run(` ${title} `, { bold: true, color: "FFFFFF", size: TABLE, shading: { type: ShadingType.CLEAR, color: "auto", fill: c.tab } })] }));
  kids.push(...children);
  return [new Table({ width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: [TEXT_W], layout: TableLayoutType.FIXED,
    borders: { top: line(c.line, 6), bottom: line(c.line, 6), left: line(c.line, 6), right: line(c.line, 6), insideHorizontal: none, insideVertical: none },
    rows: [new TableRow({ cantSplit: false, children: [new TableCell({ width: { size: TEXT_W, type: WidthType.DXA },
      shading: { type: ShadingType.CLEAR, color: "auto", fill: c.fill }, margins: { top: 90, bottom: 90, left: 140, right: 140 }, children: kids })] })] }),
  new Paragraph({ spacing: { after: 100 }, children: [] })];
}

function quote(list, ctx) {
  const first = list[0];
  const s = first && first.c && first.c[0] && first.c[0].t === "Str" ? first.c[0].c : "";
  if (s.startsWith("BOX@@")) {
    const [, kind, ...rest] = plain(first.c).split("@@");
    const title = rest.join("@@").trim();
    const inner = [];
    for (const blk of list.slice(1)) {
      if (blk.t === "CodeBlock") inner.push(...codeLines(blk.c[1], "FFFFFF"));
      else if (blk.t === "Para" || blk.t === "Plain") inner.push(...paraBlocks(blk.c, { f: { size: SMALL }, align: AlignmentType.LEFT, after: 60 }));
      else inner.push(...blocks([blk], { f: { size: SMALL } }));
    }
    return boxTable(kind, title, inner);
  }
  return blocks(list, { ...ctx, paraOpts: { indent: { left: 567, right: 567 } } });
}

function codeLines(text, fill) {
  return text.replace(/\s+$/, "").split("\n").map((l) => new Paragraph({ spacing: { after: 0, line: 228 }, shading: fill ? { type: ShadingType.CLEAR, color: "auto", fill } : undefined,
    children: [run(l.length ? l : " ", { mono: true, size: CODE })] }));
}
function codeBlock(text) {
  return new Table({ width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: [TEXT_W], layout: TableLayoutType.FIXED,
    borders: { top: line(C.boxline, 4), bottom: line(C.boxline, 4), left: line(C.boxline, 4), right: line(C.boxline, 4), insideHorizontal: none, insideVertical: none },
    rows: [new TableRow({ children: [new TableCell({ width: { size: TEXT_W, type: WidthType.DXA }, shading: { type: ShadingType.CLEAR, color: "auto", fill: C.codebg },
      margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: codeLines(text) })] })] });
}

// ------------------------------------------------------------------ title page
function titlePage() {
  const center = (children, o = {}) => new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: o.before || 0, after: o.after ?? 60 }, children });
  const out = [];
  out.push(center([image(path.join(REPORT, "images", "iitr-logo.png"), 1.0)], { after: 160 }));
  out.push(new Table({ width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: [TEXT_W], layout: TableLayoutType.FIXED,
    borders: { top: none, bottom: none, left: none, right: none, insideHorizontal: none, insideVertical: none },
    rows: [new TableRow({ children: [new TableCell({ width: { size: TEXT_W, type: WidthType.DXA }, shading: { type: ShadingType.CLEAR, color: "auto", fill: C.navy },
      margins: { top: 140, bottom: 140, left: 120, right: 120 }, children: [
        center([run(team.institute, { bold: true, color: "FFFFFF", size: 26 })], { after: 50 }),
        center([run(team.department, { color: "E3E8EF", size: 20 })], { after: 30 }),
        center([run(`Subject: ${team.course}  |  Project Report`, { color: "E3E8EF", size: 20 })], { after: 0 }),
      ] })] })] }));
  out.push(rule(C.head, 200, 120));
  out.push(center([run("PHOTON", { bold: true, color: C.navy, size: 60 })], { after: 40 }));
  out.push(center([run("Live Photo Sync for Groups over Unreliable Mobile Networks", { bold: true, color: C.head, size: 26 })], { after: 80 }));
  out.push(center([run("A verified, resumable and ephemeral photo-sharing system", { italics: true, color: C.gray, size: 21 })], { after: 0 }));
  out.push(center([run("built on HTTP/3, WebSocket and Redis Streams", { italics: true, color: C.gray, size: 21 })], { after: 60 }));
  out.push(rule(C.head, 60, 160));
  out.push(center([run(`Group No. ${team.group_no}`, { bold: true, color: C.navy, size: 30 })], { after: 60 }));
  out.push(center([run("Submitted To: ", { bold: true, color: C.gray }), run(team.submitted_to, { color: C.gray })], { after: 30 }));
  out.push(center([run("Submission Date: ", { bold: true, color: C.gray }), run(team.submission_date, { color: C.gray })], { after: 200 }));
  out.push(new Paragraph({ spacing: { after: 60 }, children: [run(" Group Members & Contributions ", { bold: true, color: "FFFFFF", size: TABLE, shading: { type: ShadingType.CLEAR, color: "auto", fill: C.head } })] }));
  const dx = [1700, 1150, 2650]; dx.push(TEXT_W - dx.reduce((a, x) => a + x, 0));
  const cell = (kids, w, fill, headRow) => new TableCell({ width: { size: w, type: WidthType.DXA }, shading: { type: ShadingType.CLEAR, color: "auto", fill },
    margins: { top: 50, bottom: 50, left: 80, right: 80 }, borders: { top: none, bottom: none, left: none, right: none },
    children: kids.map((k) => new Paragraph({ spacing: { after: 0, line: 240 }, children: Array.isArray(k) ? k : [run(k, { size: 18, bold: headRow, color: headRow ? "FFFFFF" : undefined })] })) });
  const contactHead = team.members.some((m) => m.mobile) ? "Email / Mobile" : "Email";
  const rows = [new TableRow({ tableHeader: true, children: ["Name", "Enroll. No.", contactHead, "Individual Contribution"].map((h, i) => cell([h], dx[i], C.blue, true)) })];
  team.members.forEach((m, i) => {
    const fill = i % 2 === 0 ? "FFFFFF" : C.rowalt;
    rows.push(new TableRow({ cantSplit: true, children: [
      cell([m.name], dx[0], fill), cell([m.enrollment], dx[1], fill),
      cell([[run(m.email, { mono: true, size: 15 })], ...(m.mobile ? [m.mobile] : [])], dx[2], fill), cell([m.contribution], dx[3], fill)] }));
  });
  out.push(new Table({ width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: dx, layout: TableLayoutType.FIXED,
    borders: { top: line(C.blue, 8), bottom: line(C.blue, 8), left: line(C.blue, 8), right: line(C.blue, 8), insideHorizontal: none, insideVertical: line("BFD3EA", 4) }, rows }));
  out.push(rule(C.head, 400, 60));
  out.push(center([run(team.version_line, { color: C.gray, size: 20 })]));
  return out;
}

// ------------------------------------------------------------------ table of contents (static, with page numbers)
function toc(pages) {
  const out = [new Paragraph({ spacing: { after: 160 }, children: [run("Contents", { bold: true, color: C.head, size: 28 })] })];
  for (const h of headings.filter((x) => x.level <= 2)) {
    const pg = pages && pages[h.id] ? String(pages[h.id]) : "";
    const indent = [0, 400, 1000][h.level - 1];
    out.push(new Paragraph({
      spacing: { before: h.level === 1 ? 140 : 0, after: 20 }, indent: { left: indent },
      tabStops: [{ type: TabStopType.RIGHT, position: TEXT_W, leader: h.level === 1 ? "none" : "dot" }],
      children: [new InternalHyperlink({ anchor: h.id, children: [
        run(`${h.number}${h.level === 1 ? "" : ""}  ${h.text}`, { bold: h.level === 1, color: C.head, size: h.level === 1 ? 22 : 20 }),
        new TextRun({ children: [new Tab()] }), run(pg, { bold: h.level === 1, size: h.level === 1 ? 22 : 20 })] })] }));
  }
  return out;
}

// ------------------------------------------------------------------ document
function build(pages) {
  tableIndex = 0; figNo = 0; tabNo = 0; listInstance = 0; counters.fill(0); headings.length = 0; runIn = null;
  const ast = JSON.parse(fs.readFileSync(path.join(BUILD, "ast.json"), "utf8"));
  const body = blocks(ast.blocks);
  const tocBlocks = toc(pages);
  const headerPara = new Paragraph({ tabStops: [{ type: TabStopType.CENTER, position: Math.round(TEXT_W / 2) }, { type: TabStopType.RIGHT, position: TEXT_W }],
    border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: C.head, space: 4 } },
    children: [run(team.institute_short, { size: 18, color: C.grayline }), new TextRun({ children: [new Tab()] }), run("PHOTON", { size: 18, bold: true, color: C.head }),
      new TextRun({ children: [new Tab()] }), run(`Group ${team.group_no}`, { size: 18, color: C.grayline })] });
  const footerPara = new Paragraph({ alignment: AlignmentType.CENTER, border: { top: { style: BorderStyle.SINGLE, size: 6, color: C.head, space: 4 } },
    children: [new TextRun({ children: [PageNumber.CURRENT], size: 18, color: C.grayline, font: FONT })] });
  const page = { size: { width: PAGE_W, height: PAGE_H }, margin: { top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN, header: 708, footer: 708 } };
  const bullet = (lvl, ch) => ({ level: lvl, format: LevelFormat.BULLET, text: ch, alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 400 + lvl * 360, hanging: 260 } } } });
  const number = (lvl) => ({ level: lvl, format: LevelFormat.DECIMAL, text: `%${lvl + 1}.`, alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 420 + lvl * 360, hanging: 300 } } } });
  const doc = new Document({
    creator: "PHOTON project group", title: "PHOTON: Live Photo Sync for Groups over Unreliable Mobile Networks",
    description: `${team.course} project report`,
    styles: {
      default: { document: { run: { font: FONT, size: BODY } } },
      paragraphStyles: [
        { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 28, bold: true, color: C.head, font: FONT }, paragraph: { outlineLevel: 0 } },
        { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 23, bold: true, color: C.head, font: FONT }, paragraph: { outlineLevel: 1 } },
        { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 22, bold: true, italics: true, color: C.head, font: FONT }, paragraph: { outlineLevel: 2 } },
      ],
    },
    numbering: { config: [
      { reference: "bullets", levels: [bullet(0, "•"), bullet(1, "–"), bullet(2, "•")] },
      { reference: "numbers", levels: [number(0), number(1), number(2)] },
      { reference: "steps", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "Step %1:", alignment: AlignmentType.LEFT,
        style: { run: { bold: true }, paragraph: { indent: { left: 1000, hanging: 1000 } } } }] },
    ] },
    sections: [
      { properties: { page }, children: titlePage() },
      { properties: { type: SectionType.NEXT_PAGE, page: { ...page, pageNumbers: { start: 1 } } },
        headers: { default: new Header({ children: [headerPara] }) }, footers: { default: new Footer({ children: [footerPara] }) },
        children: [...tocBlocks, new Paragraph({ pageBreakBefore: true, children: [] }), ...body] },
    ],
  });
  if (tableIndex !== widths.length) console.warn(`warning: ${tableIndex} tables rendered, ${widths.length} column specs`);
  return doc;
}

function headingPages(pdf) {
  const n = parseInt(/Pages:\s+(\d+)/.exec(execFileSync("pdfinfo", [pdf]).toString())[1], 10);
  const texts = [];
  for (let p = 1; p <= n; p++) texts.push(execFileSync("pdftotext", ["-f", String(p), "-l", String(p), pdf, "-"]).toString().replace(/\s+/g, " "));
  const start = texts.findIndex((t) => t.includes("1. Introduction"));
  const pages = {}; let p = Math.max(start, 0);
  for (const h of headings) {
    const needle = (h.level === 1 ? `${h.number}. ${h.text}` : `${h.number} ${h.text}`).replace(/\s+/g, " ").slice(0, 40);
    for (let q = p; q < n; q++) if (texts[q].includes(needle)) { p = q; pages[h.id] = q; break; }   // PDF page q+1 shows number q
  }
  return pages;
}

function toPdf(docx, outDir) {
  const wrapper = path.join(process.env.HOME || "", ".claude/skills/synced/9dcd7083-6c69-4630-ae95-99477074b542_39081fbb-71c7-49b2-9908-de45b753b64d/docx/scripts/office/soffice.py");
  if (fs.existsSync(wrapper)) execFileSync("python3", [wrapper, "--headless", "--convert-to", "pdf", "--outdir", outDir, docx], { stdio: "ignore" });
  else execFileSync("soffice", ["--headless", "--convert-to", "pdf", "--outdir", outDir, docx], { stdio: "ignore" });
  return path.join(outDir, path.basename(docx).replace(/\.docx$/, ".pdf"));
}

async function main() {
  const tmp = path.join(BUILD, "pass1.docx");
  fs.writeFileSync(tmp, await Packer.toBuffer(build(null)));
  let pages = {};
  try { pages = headingPages(toPdf(tmp, BUILD)); } catch (e) { console.warn("page numbers skipped:", e.message); }
  // the TOC gets its numbers in pass 2; its length does not change, so neither do the pages
  fs.writeFileSync(OUT, await Packer.toBuffer(build(pages)));
  console.log(`wrote ${path.relative(ROOT, OUT)} (${headings.length} headings, ${Object.keys(pages).length} with page numbers, ${figNo} figures, ${tabNo} tables)`);
}
main().catch((e) => { console.error(e); process.exit(1); });

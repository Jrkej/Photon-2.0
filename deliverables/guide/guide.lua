-- Pandoc filter: local.md -> LaTeX for the local testing guide PDF (see template.tex).
--   * the first level-1 heading becomes the title; "## " sections become \section
--   * inline code -> \path{...}, which can break at / and spaces (code blocks: pandoc --listings)
--   * links to other .md files become file names (they are not in the PDF); #anchors stay links
--   * tables -> blue-header striped tabular, as in the report, with widths from the cell text

local function tex_code(s)
  if s:find("[{}%%#\\]") then
    local esc = s:gsub("[\\{}%%#&_%$~^]", function(c)
      if c == "\\" then return "\\textbackslash{}" end
      if c == "~" then return "\\textasciitilde{}" end
      if c == "^" then return "\\textasciicircum{}" end
      return "\\" .. c
    end)
    return "\\texttt{" .. esc .. "}"
  end
  return "\\path{" .. s .. "}"
end

local function Code(el)
  return pandoc.RawInline("latex", tex_code(el.text))
end

local function Link(el)
  if el.target:match("^#") or el.target:match("^%a+://") then return nil end
  return pandoc.RawInline("latex", tex_code(el.target))
end

local function cell_tex(blocks)
  return (pandoc.write(pandoc.Pandoc(blocks), "latex"):gsub("%s+$", ""))
end

local function visible_len(tex)
  return #tex:gsub("\\%a+", ""):gsub("[{}%[%]]", "")
end

local function Table(tbl)
  local s = pandoc.utils.to_simple_table(tbl)
  local n = #s.headers
  local head, rows = {}, {}
  local sum, mx = {}, {}
  for i = 1, n do
    head[i] = cell_tex(s.headers[i])
    sum[i], mx[i] = visible_len(head[i]), visible_len(head[i])
  end
  for r, row in ipairs(s.rows) do
    rows[r] = {}
    for i = 1, n do
      local t = cell_tex(row[i])
      rows[r][i] = t
      local l = visible_len(t)
      sum[i], mx[i] = sum[i] + l, math.max(mx[i], l)
    end
  end
  -- column weight: geometric mean of average and longest cell; no column under 14%
  local w, total = {}, 0
  for i = 1, n do
    w[i] = math.sqrt((sum[i] / (#s.rows + 1)) * mx[i]); total = total + w[i]
  end
  for i = 1, n do w[i] = math.max(w[i] / total, 0.14) end
  total = 0; for i = 1, n do total = total + w[i] end
  local spec = {}
  for i = 1, n do
    spec[i] = string.format(">{\\raggedright\\arraybackslash}p{\\dimexpr %.3f\\linewidth-2\\tabcolsep\\relax}", w[i] / total)
  end
  local out = { "\\par\\begingroup\\small\\rowcolors{2}{white}{rowalt}\\noindent",
    "\\begin{tabular}{" .. table.concat(spec) .. "}" }
  local hc = {}
  for i = 1, n do hc[i] = "\\hd{" .. head[i] .. "}" end
  out[#out + 1] = "\\rowcolor{pblue}" .. table.concat(hc, " & ") .. "\\\\"
  for _, r in ipairs(rows) do out[#out + 1] = "\\relax " .. table.concat(r, " & ") .. "\\\\" end
  out[#out + 1] = "\\end{tabular}\\par\\endgroup\\smallskip"
  return pandoc.RawBlock("latex", table.concat(out, "\n"))
end

function Pandoc(doc)
  local blocks, title = pandoc.List(), nil
  for _, b in ipairs(doc.blocks) do
    if b.t == "Header" and b.level == 1 and not title then
      title = b.content
    else
      blocks:insert(b)
    end
  end
  doc.meta.title = pandoc.MetaInlines(title or { pandoc.Str("PHOTON") })
  doc.blocks = blocks
  return doc:walk({
    Code = Code, Link = Link, Table = Table,
    Header = function(h) h.level = math.max(h.level - 1, 1); return h end,
  })
end

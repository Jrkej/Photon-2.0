#!/usr/bin/env bash
# Builds every deliverable from source:
#   report/PHOTON_Report.pdf   (LaTeX, report/main.tex; report/PHOTON_Report_LaTeX.zip uploads to Overleaf)
#   docx/PHOTON_Report.docx    (same source, through pandoc and docx-js)
#   slides/PHOTON_Slides.pptx  (pptxgenjs, slides/build_pptx.js)
#   guide/PHOTON_Local_Testing.pdf  (../local.md through pandoc, guide/guide.lua and guide/template.tex)
# Needs: TeX Live (pdflatex, latexmk, TikZ, pgfplots, tcolorbox), pandoc, poppler-utils, Python 3 with Pillow,
# Node 18+ (npm install, once), and LibreOffice for the Word table of contents' page numbers.
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules ] || npm install --no-audit --no-fund

step() { printf '\n== %s\n' "$*"; }

step "team details (team.json -> report/team.tex)"
python3 tools/gen_team.py

step "figures (TikZ and pgfplots -> PDF and PNG)"
report/render-figures.sh

step "terminal screenshots"
python3 tools/terminal_shots.py

step "PDF report"
(cd report && latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex > build/latexmk.log 2>&1) || { tail -40 report/build/latexmk.log; exit 1; }
cp report/main.pdf "report/PHOTON_Report.pdf"
(cd report && rm -f PHOTON_Report_LaTeX.zip && zip -qr PHOTON_Report_LaTeX.zip main.tex figstyle.tex team.tex standalone.tex render-figures.sh sections figures images screens -x 'screens/src/*')
echo "wrote report/PHOTON_Report.pdf ($(pdfinfo report/main.pdf | awk '/Pages/ {print $2}') pages)"

step "Word report"
python3 tools/tex2docx_prep.py
(cd report && pandoc -f latex -t json build/docx/main-pandoc.tex -o build/docx/ast.json)
node docx/build_docx.js

step "slides"
node slides/build_pptx.js

step "local testing guide (../local.md -> PDF)"
mkdir -p guide/build && cp report/team.tex guide/build/
pandoc ../local.md -f markdown -t latex --listings --template=guide/template.tex --lua-filter=guide/guide.lua -o guide/build/guide.tex
(cd guide/build && latexmk -pdf -interaction=nonstopmode -halt-on-error guide.tex > latexmk.log 2>&1) || { tail -40 guide/build/latexmk.log; exit 1; }
cp guide/build/guide.pdf guide/PHOTON_Local_Testing.pdf
echo "wrote guide/PHOTON_Local_Testing.pdf ($(pdfinfo guide/PHOTON_Local_Testing.pdf | awk '/Pages/ {print $2}') pages)"

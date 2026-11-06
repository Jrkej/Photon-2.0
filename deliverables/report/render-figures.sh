#!/usr/bin/env bash
# Renders every figures/*.tex to build/fig/<name>.pdf and build/png/<name>.png (for the Word report and the slides).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/fig build/png
names=("$@")
if [ ${#names[@]} -eq 0 ]; then names=($(ls figures/*.tex | xargs -n1 basename | sed 's/\.tex$//')); fi
for f in "${names[@]}"; do
  pdflatex -interaction=nonstopmode -halt-on-error -output-directory=build/fig -jobname="$f" "\def\fig{$f}\input{standalone}" > build/fig/$f.out 2>&1 || { echo "FAILED: $f"; grep -A5 '^!' build/fig/$f.log | head -20; exit 1; }
  pdftoppm -png -r 220 -singlefile build/fig/$f.pdf build/png/$f
  echo "rendered $f"
done

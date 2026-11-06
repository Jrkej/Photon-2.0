"""Write report/team.tex from team.json (the title page data for the LaTeX report)."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPECIAL = {"\\": r"\textbackslash{}", "&": r"\&", "%": r"\%", "$": r"\$", "#": r"\#", "_": r"\_",
           "{": r"\{", "}": r"\}", "~": r"\textasciitilde{}", "^": r"\textasciicircum{}"}


def tex(s: str) -> str:
    return "".join(SPECIAL.get(c, c) for c in s)


def main() -> None:
    t = json.loads((ROOT / "team.json").read_text())
    lines = ["% Generated from team.json by tools/gen_team.py. Edit team.json and run ./build.sh,",
             "% or edit this file directly if you only compile the LaTeX (for example on Overleaf).",
             rf"\newcommand{{\groupno}}{{{tex(t['group_no'])}}}",
             rf"\newcommand{{\institute}}{{{tex(t['institute'])}}}",
             rf"\newcommand{{\instshort}}{{{tex(t['institute_short'])}}}",
             rf"\newcommand{{\department}}{{{tex(t['department'])}}}",
             rf"\newcommand{{\course}}{{{tex(t['course'])}}}",
             rf"\newcommand{{\submittedto}}{{{tex(t['submitted_to'])}}}",
             rf"\newcommand{{\subdate}}{{{tex(t['submission_date'])}}}",
             rf"\newcommand{{\versionline}}{{{tex(t['version_line']).replace('|', '$|$')}}}",
             rf"\newcommand{{\contacthead}}{{{'Email / Mobile' if any(m.get('mobile') for m in t['members']) else 'Email'}}}",
             r"\newcommand{\memberrows}{%"]
    for m in t["members"]:
        contact = rf"\texttt{{\footnotesize {tex(m['email'])}}}" + (rf"\newline {tex(m['mobile'])}" if m.get("mobile") else "")
        lines.append(rf"  \relax {tex(m['name'])} & {tex(m['enrollment'])} & {contact} & {tex(m['contribution'])} \\")
    lines.append("}")
    (ROOT / "report" / "team.tex").write_text("\n".join(lines) + "\n")
    print("wrote report/team.tex")


if __name__ == "__main__":
    main()

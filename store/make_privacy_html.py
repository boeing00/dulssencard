"""`store/privacy-policy.md` → `docs/privacy.html` (GitHub Pages 에 올라가는 공개 방침).

방침 원문은 마크다운 한 벌만 둔다. 이 스크립트가 그걸 정적 HTML 로 옮긴다 — Pages 의 Jekyll
처리에 기대지 않으려고(`docs/.nojekyll`) 직접 만든다. 필요한 문법만 다룬다:
제목 · 문단 · 표 · 글머리표 · 굵게 · 링크 · 인라인 코드 · 가로줄. HTML 주석(TODO)은 버린다.

    python store/make_privacy_html.py
"""

import html
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "store", "privacy-policy.md")
DST = os.path.join(ROOT, "docs", "privacy.html")

PAGE = """<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>덜쎈카드 개인정보처리방침</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{
    max-width: 44rem; margin: 0 auto; padding: 2.5rem 1.25rem 6rem;
    font: 16px/1.75 -apple-system, "Segoe UI", "Malgun Gothic", sans-serif;
    word-break: keep-all; overflow-wrap: break-word;
  }}
  h1 {{ font-size: 1.6rem; line-height: 1.35; margin: 0 0 .4rem; }}
  h2 {{ font-size: 1.2rem; margin: 2.4rem 0 .6rem; }}
  h3 {{ font-size: 1.02rem; margin: 1.6rem 0 .4rem; }}
  hr {{ border: 0; border-top: 1px solid rgba(128,128,128,.3); margin: 2rem 0; }}
  table {{ border-collapse: collapse; width: 100%; margin: 1rem 0; font-size: .94rem; }}
  th, td {{ border: 1px solid rgba(128,128,128,.35); padding: .5rem .6rem; text-align: left; vertical-align: top; }}
  th {{ background: rgba(128,128,128,.12); }}
  code {{ font-size: .9em; padding: .1em .35em; background: rgba(128,128,128,.15); border-radius: 3px; }}
  li {{ margin: .25rem 0; }}
  .wrap {{ overflow-x: auto; }}
</style>
</head>
<body>
{body}
</body>
</html>
"""


def inline(text):
    """굵게 · 링크 · 인라인 코드. 나머지는 이스케이프한다."""
    out, last = [], 0
    pattern = re.compile(r"`([^`]+)`|\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)]+)\)")
    for m in pattern.finditer(text):
        out.append(html.escape(text[last:m.start()]))
        if m.group(1) is not None:
            out.append("<code>%s</code>" % html.escape(m.group(1)))
        elif m.group(2) is not None:
            out.append("<strong>%s</strong>" % html.escape(m.group(2)))
        else:
            out.append('<a href="%s">%s</a>' % (html.escape(m.group(4)), html.escape(m.group(3))))
        last = m.end()
    out.append(html.escape(text[last:]))
    return "".join(out)


def convert(md):
    md = re.sub(r"<!--.*?-->", "", md, flags=re.S)
    lines = md.split("\n")
    out, i = [], 0
    while i < len(lines):
        line = lines[i].rstrip()

        if not line.strip():
            i += 1
            continue

        if line.startswith("---") and set(line.strip()) == {"-"}:
            out.append("<hr>")
            i += 1
            continue

        heading = re.match(r"^(#{1,3})\s+(.*)$", line)
        if heading:
            level = len(heading.group(1))
            out.append("<h%d>%s</h%d>" % (level, inline(heading.group(2)), level))
            i += 1
            continue

        # 표: 헤더 | 구분선 | 본문...
        if line.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:\-|]+\|$", lines[i + 1].strip()):
            def cells(row):
                return [c.strip() for c in row.strip().strip("|").split("|")]

            head = cells(line)
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(cells(lines[i]))
                i += 1
            out.append('<div class="wrap"><table>')
            out.append("<tr>" + "".join("<th>%s</th>" % inline(c) for c in head) + "</tr>")
            for row in rows:
                out.append("<tr>" + "".join("<td>%s</td>" % inline(c) for c in row) + "</tr>")
            out.append("</table></div>")
            continue

        if re.match(r"^[-*]\s+", line):
            out.append("<ul>")
            while i < len(lines) and re.match(r"^[-*]\s+", lines[i].rstrip()):
                item = re.sub(r"^[-*]\s+", "", lines[i].rstrip())
                i += 1
                # 들여쓴 줄은 같은 항목의 이어지는 문장이다.
                while i < len(lines) and lines[i].startswith("  ") and lines[i].strip():
                    item += " " + lines[i].strip()
                    i += 1
                out.append("<li>%s</li>" % inline(item))
            out.append("</ul>")
            continue

        para = [line]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r"^(#{1,3}\s|[-*]\s|\|)", lines[i]):
            para.append(lines[i].rstrip())
            i += 1
        out.append("<p>%s</p>" % inline(" ".join(para)))

    return "\n".join(out)


def main():
    md = io.open(SRC, encoding="utf-8").read()
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    io.open(DST, "w", encoding="utf-8", newline="\n").write(PAGE.format(body=convert(md)))
    # Jekyll 을 거치지 않게 한다. 없으면 `_` 로 시작하는 경로 등에서 예상 밖 동작을 한다.
    io.open(os.path.join(ROOT, "docs", ".nojekyll"), "w", encoding="utf-8").write("")
    print("wrote", DST)


if __name__ == "__main__":
    main()

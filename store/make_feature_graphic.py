# -*- coding: utf-8 -*-
"""Play 스토어 그래픽 이미지(1024x500) 생성.

앱과 같은 것만 쓴다 — `Ds` 색 토큰과 `res/font/` 의 IBM Plex Sans KR·Plex Mono.
스토어 카드에서 앱 화면과 다른 인상을 주면 안 되기 때문이다.

Play 는 이 이미지를 기기·자리에 따라 잘라 쓴다. 중요한 것은 가장자리에서 띄운다.

    python store/make_feature_graphic.py
"""

import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "app", "src", "main", "res", "font")
ICON = os.path.join(ROOT, "store", "ic_playstore_512.png")
DST = os.path.join(ROOT, "store", "feature_graphic_1024x500.png")

W, H = 1024, 500
MARGIN = 72

# Ds 토큰 (ui/theme)
PAPER = (244, 241, 232)
PAPER2 = (237, 233, 220)
INK = (23, 21, 15)
ACCENT = (178, 58, 18)
TEXT_BODY = (61, 56, 44)
TEXT_SUBTLE = (107, 100, 85)
LINE = (220, 214, 198)


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


SANS_SB = lambda s: font("plex_sans_kr_semibold.ttf", s)
SANS_MD = lambda s: font("plex_sans_kr_medium.ttf", s)
SANS_RG = lambda s: font("plex_sans_kr_regular.ttf", s)
# Plex Mono 는 한글이 없다. 숫자·라틴 전용으로만 쓸 것.
MONO_MD = lambda s: font("plex_mono_medium.ttf", s)


def tracked(draw, xy, text, fnt, fill, tracking):
    """자간을 준 글자. 앱의 eyebrow 가 자간을 크게 쓴다."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=fnt, fill=fill)
        x += draw.textlength(ch, font=fnt) + tracking
    return x


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def main():
    img = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(img)

    # 오른쪽에 아주 옅은 면을 깔아 아이콘이 뜨지 않게 한다.
    d.rectangle([W - 366, 0, W, H], fill=PAPER2)
    d.line([(W - 366, 0), (W - 366, H)], fill=LINE, width=2)

    # 눈썹 문구. **Plex Mono 에는 한글 글리프가 없다** — 섞어 쓰면 한글이 두부가 된다.
    # 앱 화면도 같은 이유로 이 줄을 Sans 로 낸다.
    tracked(d, (MARGIN, 104), "SMS · 푸시 → 추적", SANS_MD(20), ACCENT, 3.2)

    # 워드마크 — 앱 홈과 같은 분할(덜쎈 = ink, 카드 = accent)
    wm = SANS_SB(78)
    x = MARGIN
    d.text((x, 146), "덜쎈", font=wm, fill=INK)
    x += d.textlength("덜쎈", font=wm)
    d.text((x, 146), "카드", font=wm, fill=ACCENT)

    # 설명
    body = SANS_MD(30)
    d.text((MARGIN, 268), "결제 알림을 읽어 카드 실적을", font=body, fill=TEXT_BODY)
    d.text((MARGIN, 310), "자동으로 세어 드립니다", font=body, fill=TEXT_BODY)

    # 신뢰 한 줄 — 이 앱의 핵심 주장이다
    small = SANS_RG(22)
    d.text((MARGIN, 388), "인터넷 권한 없음 · 기록은 폰 밖으로 나가지 않습니다",
           font=small, fill=TEXT_SUBTLE)

    # 아이콘
    icon = Image.open(ICON).convert("RGB").resize((252, 252), Image.LANCZOS)
    icon = rounded(icon, 56)
    img.paste(icon, (W - 366 + (366 - 252) // 2, (H - 252) // 2), icon)

    img.save(DST)
    print("wrote %s (%dx%d)" % (DST, W, H))


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""덜쎈카드 런처 아이콘 생성: 원본 타일 PNG -> adaptive/legacy/store 아이콘 세트"""
import os, numpy as np
from PIL import Image, ImageDraw

SRC = r"C:\Users\moons\Downloads\1000022136.png"
RES = r"C:\Users\moons\AndroidStudioProjects\dulssencard\app\src\main\res"
ROOT = r"C:\Users\moons\AndroidStudioProjects\dulssencard"

img = Image.open(SRC).convert("RGB")
a = np.asarray(img).astype(np.float32)

# 1) 노랑 타일만 잘라내고 정사각 확보 (바깥 흰 여백 + 드롭섀도 제거)
Rf, Gf, Bf = a[..., 0], a[..., 1], a[..., 2]
tile_yellow = (Rf > 200) & (Gf > 140) & (Bf < 150) & (Rf - Bf > 80)
ys, xs = np.where(tile_yellow)
y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
cy, cx = (y0 + y1) / 2, (x0 + x1) / 2
side = max(y1 - y0, x1 - x0)
y0 = int(round(cy - side / 2)); x0 = int(round(cx - side / 2))
tile = a[y0:y0 + side, x0:x0 + side]
N = tile.shape[0]
print("tile", N)

# 2) 행별 배경 노랑(그라디언트) 추정 — 노랑 계열 픽셀의 중앙값
R, G, B = tile[..., 0], tile[..., 1], tile[..., 2]
yellowish = (R > 200) & (G > 140) & (B < 150) & (R - B > 80)
bg_row = np.zeros((N, 3), np.float32)
last = np.array([255., 212., 0.], np.float32)
for y in range(N):
    m = yellowish[y]
    if m.sum() > N * 0.05:
        last = np.median(tile[y][m], axis=0)
    bg_row[y] = last
# 이동평균으로 부드럽게
k = 41
pad = np.pad(bg_row, ((k // 2, k // 2), (0, 0)), mode="edge")
bg_row = np.stack([np.convolve(pad[:, c], np.ones(k) / k, "valid") for c in range(3)], 1)
TOP, BOT = bg_row[int(N * 0.02)], bg_row[int(N * 0.98)]
hexc = lambda c: "#%02X%02X%02X" % tuple(int(round(v)) for v in c)
print("bg top", hexc(TOP), "bottom", hexc(BOT))

# 3) 배경 노랑 키잉 -> 글자+고양이만 남긴 알파
dist = np.linalg.norm(tile - bg_row[:, None, :], axis=2)
alpha = np.clip((dist - 45.0) / (90.0 - 45.0), 0, 1)   # 타일 노랑은 가로세로로 미세한 그라디언트가 있어 여유를 크게
# 둥근 사각 바깥(흰 여백/드롭섀도)은 통째로 잘라낸다. 모서리 반경은 타일 첫 행에서 측정
rows = np.where(yellowish.sum(1) > N * 0.05)[0]
cols = np.where(yellowish.sum(0) > N * 0.05)[0]
t, b, l, r = int(rows[0]), int(rows[-1]), int(cols[0]), int(cols[-1])
row0 = np.where(yellowish[t + 3])[0]
rad = int(max(row0.min() - l, r - row0.max())) + 3
shape = Image.new("L", (N, N), 0)
ImageDraw.Draw(shape).rounded_rectangle([l + 10, t + 10, r - 10, b - 10], radius=rad, fill=255)
near_white = np.asarray(shape) < 250                            # 타일 바깥
print("corner radius", rad)
alpha[near_white] = 0.0
content = np.dstack([tile, alpha * 255.0]).astype(np.uint8)
content_img = Image.fromarray(content, "RGBA")

# 흑백(themed icon) 레이어: 어두운 부분만 알파로
lum = 0.299 * R + 0.587 * G + 0.114 * B
mono_a = np.clip((150.0 - lum) / 60.0, 0, 1)
mono_a[near_white] = 0.0
mono_img = Image.fromarray(np.dstack([np.zeros_like(tile), mono_a * 255.0]).astype(np.uint8), "RGBA")

# 4) 콘텐츠 bbox / 중심에서의 최대 반경(정규화)
m = alpha > 0.25
ys, xs = np.where(m)
u0, u1 = xs.min() / N, (xs.max() + 1) / N
v0, v1 = ys.min() / N, (ys.max() + 1) / N
uc, vc = (u0 + u1) / 2, (v0 + v1) / 2
maxr = float(np.sqrt(((xs / N - uc) ** 2 + (ys / N - vc) ** 2)).max())
print("content bbox u %.3f-%.3f v %.3f-%.3f  maxr %.3f" % (u0, u1, v0, v1, maxr))


def compose(C, radius_frac, margin, bg=True, mono=False):
    """C px 캔버스에 배경 그라디언트 + 콘텐츠(반경 radius_frac*C 원 안에 수납)"""
    if bg:
        canvas = Image.new("RGBA", (C, C))
        d = ImageDraw.Draw(canvas)
        for y in range(C):
            c = TOP + (BOT - TOP) * (y / max(C - 1, 1))
            d.line([(0, y), (C, y)], fill=tuple(int(round(v)) for v in c) + (255,))
    else:
        canvas = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    P = int(round(C * radius_frac * margin / maxr))
    src = (mono_img if mono else content_img).resize((P, P), Image.LANCZOS)
    ox = int(round(C / 2 - P * uc)); oy = int(round(C / 2 - P * vc))
    canvas.alpha_composite(src, (ox, oy))
    return canvas


DPI = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]

# 5) adaptive foreground / monochrome: 108dp 캔버스, 중앙 72dp 세이프존 안에 수납
for name, mono in (("ic_launcher_foreground", False), ("ic_launcher_monochrome", True)):
    for q, s in DPI:
        C = int(108 * s)
        out = os.path.join(RES, "drawable-" + q)
        os.makedirs(out, exist_ok=True)
        compose(C, 36 / 108, 0.98, bg=False, mono=mono).save(os.path.join(out, name + ".png"))

# 6) legacy mipmap: 사각(원본 타일 그대로) / 원형
tile_img = Image.fromarray(
    np.dstack([tile, np.where(near_white, 0, 255)]).astype(np.uint8), "RGBA")
for q, s in DPI:
    C = int(48 * s)
    out = os.path.join(RES, "mipmap-" + q)
    os.makedirs(out, exist_ok=True)
    tile_img.resize((C, C), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    rnd = compose(C * 4, 0.5, 0.96)
    mask = Image.new("L", (C * 4, C * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, C * 4 - 1, C * 4 - 1], fill=255)
    rnd.putalpha(mask)
    rnd.resize((C, C), Image.LANCZOS).save(os.path.join(out, "ic_launcher_round.png"))

# 7) Play 스토어용 512x512 (투명도 없이 원본 그대로)
store = Image.new("RGBA", (N, N))
d = ImageDraw.Draw(store)
for y in range(N):
    d.line([(0, y), (N, y)], fill=tuple(int(round(v)) for v in bg_row[y]) + (255,))
store.alpha_composite(tile_img)
store.convert("RGB").resize((512, 512), Image.LANCZOS).save(
    os.path.join(ROOT, "store", "ic_playstore_512.png"))
# 원본 아트 보관
Image.open(SRC).save(os.path.join(ROOT, "store", "app_icon_source.png"))
print("done")

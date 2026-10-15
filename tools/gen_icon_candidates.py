# -*- coding: utf-8 -*-
"""生成 8 版应用图标候选 → contact_sheet.png（含 48px 缩小检验条）。

火焰用**三次贝塞尔**手画轮廓：单舌/双舌水滴都会被读成水，
必须靠「不对称尖端 + 缺口(谷) + 波浪底」三件套才像火。
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = 'C:/Windows/Fonts/msyhbd.ttc'
FONT_R = 'C:/Windows/Fonts/msyh.ttc'

ORANGE = (255, 122, 69)
DEEP = (233, 79, 30)
LIGHT = (255, 146, 92)
DARK = (14, 19, 29)
DARK2 = (27, 35, 49)
WHITE = (255, 255, 255, 255)


def bez(p0, p1, p2, p3, n=34):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0]
        y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]
        out.append((x, y))
    return out


# 火焰轮廓（顺时针）：主火苗尖 → 右侧 → 波浪底 → 左侧 → 小火苗尖 → 谷 → 回主尖
FLAME_PATH = [
    ((0.575, 0.020), (0.660, 0.175), (0.790, 0.265), (0.842, 0.432)),
    ((0.842, 0.432), (0.898, 0.610), (0.860, 0.830), (0.702, 0.918)),
    ((0.702, 0.918), (0.582, 0.984), (0.424, 0.984), (0.312, 0.908)),
    ((0.312, 0.908), (0.182, 0.818), (0.132, 0.642), (0.192, 0.500)),
    ((0.192, 0.500), (0.236, 0.398), (0.338, 0.358), (0.386, 0.268)),
    ((0.386, 0.268), (0.428, 0.188), (0.404, 0.112), (0.432, 0.012)),
    ((0.432, 0.012), (0.462, 0.108), (0.482, 0.228), (0.482, 0.334)),
    ((0.482, 0.334), (0.504, 0.226), (0.536, 0.122), (0.575, 0.020)),
]


def flame_pts(size, k=1.0, ox=0.5, oy=0.5, cx=0.5, cy=0.52):
    pts = [(0.575, 0.020)]
    for seg in FLAME_PATH:
        pts += bez(seg[0], seg[1], seg[2], seg[3])
    return [(((x - cx) * k + cx + ox) * size, ((y - cy) * k + cy + oy) * size)
            for x, y in pts]


def flame_mask(size, k=1.0, ox=0.0, oy=0.0):
    m = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(m).polygon(flame_pts(size * 4, k, ox, oy), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def diag_grad(size, c0, c1):
    img = Image.new('RGB', (size, size))
    px = img.load()
    d = max(1, size - 1)
    for y in range(size):
        for x in range(size):
            t = min(1.0, max(0.0, (x / d + y / d) / 2.0))
            px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    return img


def mask_round(size, r=0.225):
    big = size * 4
    m = Image.new('L', (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * r), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def glyph(img, ch, k, color, dy=0.0):
    s = img.size[0]
    f = ImageFont.truetype(FONT, int(s * k))
    ImageDraw.Draw(img).text((s / 2, s * (0.5 + dy)), ch, font=f, fill=color, anchor='mm')
    return img


def spark(d, cx, cy, r, color):
    d.polygon([(cx, cy - r * 2.0), (cx + r * 0.55, cy - r * 0.2), (cx + r * 1.8, cy),
               (cx + r * 0.55, cy + r * 0.2), (cx, cy + r * 2.0),
               (cx - r * 0.55, cy + r * 0.2), (cx - r * 1.8, cy),
               (cx - r * 0.55, cy - r * 0.2)], fill=color)


def cand(name):
    def deco(fn):
        fn._cand = name
        return fn
    return deco


@cand('A橙底·白火焰')
def a_(s):
    im = diag_grad(s, LIGHT, DEEP).convert('RGBA')
    im.paste(WHITE, (0, 0, s, s), flame_mask(s, 0.86))
    im.putalpha(mask_round(s))
    return im


@cand('B橙底·白「火」字')
def b_(s):
    im = diag_grad(s, LIGHT, DEEP).convert('RGBA')
    im = glyph(im, '火', 0.60, WHITE)
    im.putalpha(mask_round(s))
    return im


@cand('C深底·橙「火」字')
def c_(s):
    im = diag_grad(s, DARK2, DARK).convert('RGBA')
    im = glyph(im, '火', 0.60, ORANGE + (255,), -0.02)
    im.putalpha(mask_round(s))
    return im


@cand('D深底·渐变火焰')
def d_(s):
    im = diag_grad(s, DARK2, DARK).convert('RGBA')
    im.paste(diag_grad(s, LIGHT, DEEP).convert('RGBA'), (0, 0), flame_mask(s, 0.86))
    im.putalpha(mask_round(s))
    return im


@cand('E橙底·火焰+火花点')
def e_(s):
    im = diag_grad(s, LIGHT, DEEP).convert('RGBA')
    im.paste(WHITE, (0, 0, s, s), flame_mask(s, 0.72, -0.05, 0.03))
    d = ImageDraw.Draw(im)
    spark(d, s * 0.795, s * 0.285, s * 0.024, (255, 255, 255, 235))
    spark(d, s * 0.868, s * 0.388, s * 0.016, (255, 255, 255, 185))
    spark(d, s * 0.722, s * 0.198, s * 0.012, (255, 255, 255, 150))
    im.putalpha(mask_round(s))
    return im


@cand('F橙底·白「盾」字')
def f_(s):
    im = diag_grad(s, LIGHT, DEEP).convert('RGBA')
    im = glyph(im, '盾', 0.56, WHITE)
    im.putalpha(mask_round(s))
    return im


@cand('G浅底·橙火焰')
def g_(s):
    im = diag_grad(s, (255, 251, 248), (246, 230, 220)).convert('RGBA')
    im.paste(diag_grad(s, LIGHT, DEEP).convert('RGBA'), (0, 0), flame_mask(s, 0.86))
    im.putalpha(mask_round(s))
    return im


@cand('H橙底·白火焰+内焰')
def h_(s):
    im = diag_grad(s, LIGHT, DEEP).convert('RGBA')
    im.paste(WHITE, (0, 0, s, s), flame_mask(s, 0.86))
    inner = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    inner.paste(diag_grad(s, LIGHT, DEEP).convert('RGBA'), (0, 0),
                flame_mask(s, 0.42, 0.02, 0.20))
    im.paste(inner, (0, 0), inner)
    im.putalpha(mask_round(s))
    return im


CANDS = [a_, b_, c_, d_, e_, f_, g_, h_]
TAG = 'ABCDEFGH'


def main():
    cell, gap, lab = 210, 26, 30
    strip = 74
    cols, rows = 4, 2
    W = gap + cols * cell + (cols - 1) * gap
    H = gap + rows * (cell + lab) + (rows - 1) * gap + strip + gap
    sheet = Image.new('RGB', (W, H), (18, 23, 33))
    d = ImageDraw.Draw(sheet)
    fs = ImageFont.truetype(FONT_R, 16)
    fk = ImageFont.truetype(FONT, 15)
    fl = ImageFont.truetype(FONT_R, 13)

    for i, fn in enumerate(CANDS):
        r, c = divmod(i, cols)
        x = gap + c * (cell + gap)
        y = gap + r * (cell + lab + gap)
        icon = fn(384).resize((cell, cell), Image.LANCZOS)
        sheet.paste(icon, (x, y), icon)
        d.text((x, y + cell + 6), TAG[i] + '  ' + fn._cand, font=fs, fill=(205, 212, 224))

    sy = H - strip - 6
    d.line([gap, sy - 14, W - gap, sy - 14], fill=(40, 48, 62), width=1)
    d.text((gap, sy), '缩小到 48px 后（桌面启动器/通知栏的真实尺寸）：', font=fl, fill=(120, 130, 148))
    for i, fn in enumerate(CANDS):
        x = gap + i * (48 + 16)
        sm = fn(96).resize((48, 48), Image.LANCZOS)
        sheet.paste(sm, (x, sy + 20), sm)
        d.text((x + 20, sy + 70), TAG[i], font=fk, fill=(110, 120, 140), anchor='mm')

    out = os.path.join(ROOT, 'contact_sheet.png')
    sheet.save(out)
    print('->', out, sheet.size)


if __name__ == '__main__':
    main()

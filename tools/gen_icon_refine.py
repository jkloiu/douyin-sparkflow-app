# -*- coding: utf-8 -*-
"""H 版（橙底·白火焰+内焰）的优化变体 → refine_sheet.png。

优化点：
  ① 外焰用**暖白**（#FFF7F3）而不是纯白 —— 纯白压在饱和橙上会发灰发平
  ② 内芯形状/位置/配色单独调：居中、下沉偏右、亮黄发光、双层、无芯渐深
  ③ 内芯一律**用外焰遮罩裁剪**，保证不会溢出轮廓
  ④ 背景渐变方向改为左上→右下（与火焰主尖方向一致）
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageChops

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_R = 'C:/Windows/Fonts/msyh.ttc'
FONT = 'C:/Users/lenovo/.workbuddy/binaries/python/../../../../../Windows/Fonts/msyhbd.ttc'
FONT = 'C:/Windows/Fonts/msyhbd.ttc'

ORANGE = (255, 122, 69)
DEEP = (233, 79, 30)
LIGHT = (255, 146, 92)
CREAM = (255, 247, 243)
PURE = (255, 255, 255)


def bez(p0, p1, p2, p3, n=34):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]))
    return out


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
CX, CY = 0.55, 0.66          # 火焰「肚子」中心，内芯围绕它缩放


def flame_poly(size, k, ox=0.0, oy=0.0, cx=CX, cy=CY):
    pts = [(0.575, 0.020)]
    for seg in FLAME_PATH:
        pts += bez(seg[0], seg[1], seg[2], seg[3])
    return [(((x - cx) * k + cx + ox) * size, ((y - cy) * k + cy + oy) * size) for x, y in pts]


def mask_from(size, k, ox=0.0, oy=0.0, cx=CX, cy=CY, ky=1.0):
    m = Image.new('L', (size * 4, size * 4), 0)
    pts = flame_poly(size * 4, k, ox, oy, cx, cy)
    if ky != 1.0:                      # 以 cy 为轴做纵向非等比缩放 → 瘦高/矮胖的火芯
        pts = [(x, cy * size * 4 + (y - cy * size * 4) * ky) for x, y in pts]
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def grad(size, c0, c1, vertical=False):
    img = Image.new('RGB', (size, size))
    px = img.load()
    d = max(1, size - 1)
    for y in range(size):
        for x in range(size):
            t = (y / d) if vertical else ((x / d + y / d) / 2.0)
            t = min(1.0, max(0.0, t))
            px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    return img


def mask_round(size, r=0.225):
    big = size * 4
    m = Image.new('L', (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * r), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def build(size, outer, core=None, k_outer=0.86, k_core=0.44, core_ox=0.0, core_oy=0.10,
          core_cx=CX, core_cy=CY, ky=1.0):
    """core = (顶色, 底色, 竖向渐变?)；None = 不画内芯"""
    im = grad(size, LIGHT, DEEP).convert('RGBA')
    outer_m = mask_from(size, k_outer)
    im.paste(outer + (255,), (0, 0, size, size), outer_m)
    if core:
        ctop, cbot, vert = core
        cm = mask_from(size, k_core, core_ox, core_oy, core_cx, core_cy, ky)
        cm = ImageChops.multiply(cm, outer_m)          # 内芯绝不溢出外焰
        im.paste(grad(size, ctop, cbot, vert).convert('RGBA'), (0, 0), cm)
    im.putalpha(mask_round(size))
    return im


def cand(name):
    def deco(fn):
        fn._cand = name
        return fn
    return deco


@cand('现版（纯白外焰 + 小橙芯）')
def h0_(s):
    return build(s, PURE, (LIGHT, DEEP, False), k_core=0.42, core_oy=0.20)


@cand('暖白外焰 + 居中橙芯')
def h1_(s):
    return build(s, CREAM, (LIGHT, DEEP, False), k_core=0.46, core_oy=0.12)


@cand('暖白外焰 + 下沉偏右火芯')
def h2_(s):
    return build(s, CREAM, (LIGHT, DEEP, False), k_core=0.44, core_oy=0.17, core_ox=0.035)


@cand('暖白外焰 + 亮黄发光芯')
def h3_(s):
    return build(s, CREAM, ((255, 226, 176), (244, 104, 48), True), k_core=0.50, core_oy=0.10)


@cand('暖白外焰 + 双层芯')
def h4_(s):
    im = grad(s, LIGHT, DEEP).convert('RGBA')
    om = mask_from(s, 0.86)
    im.paste(CREAM + (255,), (0, 0, s, s), om)
    inner = ImageChops.multiply(mask_from(s, 0.54, 0.0, 0.10), om)
    im.paste(grad(s, (255, 196, 140), (243, 104, 44), True).convert('RGBA'), (0, 0), inner)
    core = ImageChops.multiply(mask_from(s, 0.28, 0.0, 0.22), inner)
    im.paste(grad(s, (255, 243, 218), (255, 156, 92), True).convert('RGBA'), (0, 0), core)
    im.putalpha(mask_round(s))
    return im


@cand('暖白外焰 + 含蓄小火苗芯')
def h5_(s):
    return build(s, CREAM, (LIGHT, DEEP, False),
                 k_core=0.33, core_oy=0.17)


CANDS = [h0_, h1_, h2_, h3_, h4_, h5_]
TAG = ['H0', 'H1', 'H2', 'H3', 'H4', 'H5']


def main():
    cell, gap, lab = 224, 26, 30
    strip = 78
    cols = 3
    rows = 2
    W = gap + cols * cell + (cols - 1) * gap
    H = gap + rows * (cell + lab) + (rows - 1) * gap + strip + gap
    sheet = Image.new('RGB', (W, H), (18, 23, 33))
    d = ImageDraw.Draw(sheet)
    fs = ImageFont.truetype(FONT_R, 16)
    fl = ImageFont.truetype(FONT_R, 13)

    for i, fn in enumerate(CANDS):
        r, c = divmod(i, cols)
        x = gap + c * (cell + gap)
        y = gap + r * (cell + lab + gap)
        icon = fn(420).resize((cell, cell), Image.LANCZOS)
        sheet.paste(icon, (x, y), icon)
        d.text((x, y + cell + 6), TAG[i] + '  ' + fn._cand, font=fs, fill=(205, 212, 224))

    sy = H - strip - 6
    d.line([gap, sy - 14, W - gap, sy - 14], fill=(40, 48, 62), width=1)
    d.text((gap, sy), '缩小到 48px / 32px：', font=fl, fill=(120, 130, 148))
    for i, fn in enumerate(CANDS):
        x = gap + i * 92
        for j, sz in enumerate((48, 32)):
            sm = fn(96).resize((sz, sz), Image.LANCZOS)
            sheet.paste(sm, (x + j * 54, sy + 20), sm)
        d.text((x + 34, sy + 70), TAG[i], font=fl, fill=(110, 120, 140), anchor='mm')

    out = os.path.join(ROOT, 'refine_sheet.png')
    sheet.save(out)
    print('->', out, sheet.size)


if __name__ == '__main__':
    main()
# -*- coding: utf-8 -*-
"""「火花守护」应用图标正式生成脚本（H 方案：橙渐变底 + 白色火焰 + 橙色内芯）。

输出：
  mipmap-*/ic_launcher.png            传统方形图标
  mipmap-*/ic_launcher_round.png      圆形图标
  mipmap-*/ic_launcher_foreground.png 自适应图标前景（白焰，中心挖空让橙底透出 = 橙芯）
  mipmap-*/ic_launcher_background.png 自适应图标背景（橙渐变）
  drawable-*/ic_stat_spark.png        通知栏小图标（白焰剪影，中心镂空）
  preview_icon.png                    人眼确认用拼图

火焰轮廓是三次贝塞尔手画的「双火苗尖 + 中间谷 + 波浪底」——
对称光滑的水滴形会被读成水，必须靠不对称尖端和缺口才像火。
内芯用外焰遮罩做差集（ImageChops.subtract）得到白焰环：既不溢出，镂空处又自然透出橙底。
"""
import os
from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'res')

LIGHT = (255, 146, 92)      # 橙渐变亮端
DEEP = (233, 79, 30)        # 橙渐变暗端
WHITE = (255, 255, 255)

DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

# ── 火焰轮廓（顺时针）：主尖 → 右肩 → 波浪底 → 左肩 → 小尖 → 谷 → 回主尖 ──
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
CX, CY = 0.55, 0.66
K_OUTER, K_CORE, OX, OY = 0.86, 0.42, 0.02, 0.20   # H 方案基准参数


def bez(p0, p1, p2, p3, n=34):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]))
    return out


def _poly(size, k, ox, oy):
    pts = [(0.575, 0.020)]
    for seg in FLAME_PATH:
        pts += bez(seg[0], seg[1], seg[2], seg[3])
    return [(((x - CX) * k + CX + ox) * size, ((y - CY) * k + CY + oy) * size) for x, y in pts]


def _mask(size, k, ox, oy, center=False):
    m = Image.new('L', (size * 4, size * 4), 0)
    pts = _poly(size * 4, k, ox, oy)
    if center:
        # 缩放是围绕 (CX,CY) 做的，形状中心并不在画布正中；
        # 自适应前景/通知小图标必须按包围盒重新居中，否则会偏下甚至溢出。
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        dx = ((size * 4) - (min(xs) + max(xs))) / 2.0
        dy = ((size * 4) - (min(ys) + max(ys))) / 2.0
        pts = [(x + dx, y + dy) for x, y in pts]
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def ring_mask(size, f=1.0, center=False):
    """白焰环：外焰减去内芯。f 为整体缩放（k 与偏移同步缩放）。"""
    return ImageChops.subtract(_mask(size, K_OUTER * f, OX * f, OY * f, center),
                               _mask(size, K_CORE * f, OX * f, OY * f, center))


def grad(size, c0, c1):
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


def mask_circle(size):
    big = size * 4
    m = Image.new('L', (big, big), 0)
    ImageDraw.Draw(m).ellipse([0, 0, big - 1, big - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def make_launcher(size, shape='square'):
    im = grad(size, LIGHT, DEEP).convert('RGBA')
    im.paste(WHITE + (255,), (0, 0, size, size), ring_mask(size, 1.0))
    im.putalpha(mask_round(size) if shape == 'square' else mask_circle(size))
    return im


def make_bg(size):
    return grad(size * 2, LIGHT, DEEP).convert('RGBA').resize((size, size), Image.LANCZOS)


def make_fg(size):
    """自适应前景：108dp 画布，内容压在中央安全区内（f=0.60）。"""
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    im.paste(WHITE + (255,), (0, 0, size, size), ring_mask(size, 0.52, True))
    return im


def make_stat(size):
    """通知栏小图标：白色火焰剪影，中心镂空（系统按主题染色）。"""
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    im.paste(WHITE + (255,), (0, 0, size, size), ring_mask(size, 0.92, True))
    return im


def main():
    for name, ratio in DENSITIES.items():
        d = os.path.join(RES, 'mipmap-' + name)
        os.makedirs(d, exist_ok=True)
        px = int(48 * ratio)
        make_launcher(px, 'square').save(os.path.join(d, 'ic_launcher.png'))
        make_launcher(px, 'circle').save(os.path.join(d, 'ic_launcher_round.png'))
        make_fg(int(108 * ratio)).save(os.path.join(d, 'ic_launcher_foreground.png'))
        make_bg(int(108 * ratio)).save(os.path.join(d, 'ic_launcher_background.png'))
        dd = os.path.join(RES, 'drawable-' + name)
        os.makedirs(dd, exist_ok=True)
        make_stat(int(24 * ratio)).save(os.path.join(dd, 'ic_stat_spark.png'))
        print('%-8s launcher=%-4d fg=%-4d stat=%d' % (name, px, int(108 * ratio), int(24 * ratio)))

    # ---- 预览拼图：方形 / 圆形 / 自适应(裁圆) / 通知小图标 ----
    S = 256
    sheet = Image.new('RGB', (S * 2 + 36, S * 2 + 28), (18, 23, 33))
    sheet.paste(make_launcher(S, 'square').convert('RGB'), (8, 8))
    sheet.paste(make_launcher(S, 'circle').convert('RGB'), (S + 28, 8))
    n = 432
    ad = make_bg(n).convert('RGB')
    ad.paste(WHITE + (255,), (0, 0, n, n), ring_mask(n, 0.52, True))
    inner = n // 108 * 72
    off = (n - inner) // 2
    ad.putalpha(mask_circle(n))
    ad = ad.crop((off, off, off + inner, off + inner))
    sheet.paste(ad.convert('RGB').resize((S, S), Image.LANCZOS), (8, S + 20))
    st = make_stat(192)
    tile = Image.new('RGB', (S, S), (33, 41, 57))
    tile.paste(st.resize((S, S), Image.LANCZOS), (0, 0), st.resize((S, S), Image.LANCZOS))
    sheet.paste(tile, (S + 28, S + 20))
    out = os.path.join(ROOT, 'preview_icon.png')
    sheet.save(out)
    print('preview ->', out)


if __name__ == '__main__':
    main()
"""生成 FloatBit 图标: 圆角渐变背景 + 菱形标记。
输出:
  assets/icon.ico  (多尺寸, 应用/任务栏用)
  assets/icon.png  (256, 通用)
  assets/tray.png  (32, 托盘用)
"""
import os
import math
from PIL import Image, ImageDraw

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "assets")
os.makedirs(OUT_DIR, exist_ok=True)

# 品牌色: 深底 + 青绿 accent (与界面一致)
BG_TOP = (35, 41, 56)      # #232938
BG_BOTTOM = (16, 18, 24)   # #101218
ACCENT = (79, 209, 197)    # #4fd1c5
ACCENT_DARK = (24, 192, 138)  # #18c08a


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def make_base(size):
    # 高分辨率绘制再缩小, 抗锯齿更好
    SS = 4
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # 垂直渐变背景
    grad = Image.new("RGBA", (s, s))
    gd = grad.load()
    for y in range(s):
        t = y / (s - 1)
        c = lerp(BG_TOP, BG_BOTTOM, t)
        for x in range(s):
            gd[x, y] = (c[0], c[1], c[2], 255)

    radius = int(s * 0.22)
    mask = rounded_mask(s, radius)
    img.paste(grad, (0, 0), mask)

    draw = ImageDraw.Draw(img)

    # 中心菱形 (◈ 风格): 外菱形描边 + 内实心菱形
    cx, cy = s / 2, s / 2
    r_out = s * 0.30
    r_in = s * 0.155

    def diamond(r):
        return [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]

    # 外菱形: 渐变描边 (用两层模拟)
    line_w = max(2, int(s * 0.045))
    draw.polygon(diamond(r_out), outline=ACCENT, width=line_w)

    # 内实心菱形
    draw.polygon(diamond(r_in), fill=ACCENT)

    # 顶部高光 (轻微提亮顶部, 增加立体感)
    hi = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hi)
    hd.ellipse([s * 0.1, -s * 0.5, s * 0.9, s * 0.35],
               fill=(255, 255, 255, 22))
    img = Image.alpha_composite(img, Image.composite(
        hi, Image.new("RGBA", (s, s), (0, 0, 0, 0)), mask))

    return img.resize((size, size), Image.LANCZOS)


def main():
    # 各尺寸
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = {sz: make_base(sz) for sz in sizes}

    # .ico 多尺寸
    ico_path = os.path.join(OUT_DIR, "icon.ico")
    images[256].save(ico_path, format="ICO",
                     sizes=[(s, s) for s in sizes])
    print("生成:", os.path.abspath(ico_path))

    # 通用 png
    png_path = os.path.join(OUT_DIR, "icon.png")
    images[256].save(png_path, format="PNG")
    print("生成:", os.path.abspath(png_path))

    # 托盘 png (32px, Windows 托盘适配)
    tray_path = os.path.join(OUT_DIR, "tray.png")
    images[32].save(tray_path, format="PNG")
    print("生成:", os.path.abspath(tray_path))


if __name__ == "__main__":
    main()

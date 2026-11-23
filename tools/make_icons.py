#!/usr/bin/env python3
"""
从一张方形图生成 Android 启动图标（自适应图标 + 各密度 PNG）。

用法：
    python tools/make_icons.py <源图片> [res 目录]

会生成：
    res/drawable-nodpi/ic_launcher_image.png   自适应图标背景层
    res/mipmap-*/ic_launcher.png               各密度方形图标
    res/mipmap-*/ic_launcher_round.png         各密度圆形图标

自适应图标的前景层是透明的（见 res/drawable/ic_launcher_foreground.xml），
所以整张图放在背景层，由启动器按自己的形状遮罩裁切。
"""
import sys
import os
from PIL import Image, ImageDraw

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def circle_mask(size: int, supersample: int = 4) -> Image.Image:
    big = size * supersample
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    src = sys.argv[1]
    res = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "res",
    )

    img = Image.open(src).convert("RGBA")
    side = min(img.size)
    left = (img.width - side) // 2
    top = (img.height - side) // 2
    img = img.crop((left, top, left + side, top + side))

    for density, px in SIZES.items():
        d = os.path.join(res, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)

        square = img.resize((px, px), Image.LANCZOS)
        square.save(os.path.join(d, "ic_launcher.png"))

        rounded = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rounded.paste(square, (0, 0), circle_mask(px))
        rounded.save(os.path.join(d, "ic_launcher_round.png"))

    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    img.resize((512, 512), Image.LANCZOS).save(os.path.join(nodpi, "ic_launcher_image.png"))

    print(f"done: {img.size} -> {res}")


if __name__ == "__main__":
    main()

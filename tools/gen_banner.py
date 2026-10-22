#!/usr/bin/env python3
"""生成 TV launcher banner（320x180 PNG，Google TV banner 规范）。"""
from PIL import Image, ImageDraw

W, H = 320, 180

img = Image.new("RGB", (W, H), "#0B0F14")
d = ImageDraw.Draw(img)

# 右上角淡色装饰弧线（电视屏幕意象）
for i, alpha in enumerate((28, 20, 13)):
    r = 150 + i * 26
    d.ellipse([W - r + 30, H - r + 40, W + r - 30, H + r - 80], outline="#4CC3FF", width=2)

# 中央圆角电视屏幕
sx, sy, sw, sh = 96, 44, 128, 76
d.rounded_rectangle([sx, sy, sx + sw, sy + sh], radius=8, outline="#FFFFFF", width=3)
# 播放三角
cx, cy = sx + sw // 2, sy + sh // 2
d.polygon([(cx - 12, cy - 14), (cx - 12, cy + 14), (cx + 16, cy)], fill="#4CC3FF")
# 底座
d.rounded_rectangle([sx + 34, sy + sh + 10, sx + sw - 34, sy + sh + 16], radius=3, fill="#8AA0B4")

# 标题字样（简洁几何字：TVCast 用默认字体绘制）
try:
    from PIL import ImageFont
    font = ImageFont.truetype("arialbd.ttf", 26)
    d.text((W // 2, 148), "TVCast", font=font, anchor="mm", fill="#FFFFFF")
except Exception:
    d.text((W // 2, 148), "TVCast", anchor="mm", fill="#FFFFFF")

img.save("app/src/main/res/drawable-nodpi/tv_banner.png", optimize=True)
print("banner written:", img.size)

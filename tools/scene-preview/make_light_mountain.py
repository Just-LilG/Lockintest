"""Bakes the light-theme mountain from the dark one: the dark picture is white snow faces with see-through gaps
(the rock is whatever is behind), which is fine on a dark page but turns into black slabs when simply inverted.
Here the whole body is filled in as slate rock, with the snow faces laid back on top and a darker rim so the
ridge reads against the pale sky.
  python3 make_light_mountain.py     -> ../../app/src/main/res/drawable-nodpi/mountain_light.png
"""
import numpy as np
from PIL import Image, ImageFilter

src = Image.open('../../app/src/main/res/drawable-nodpi/mountain.png').convert('RGBA')
W, H = src.size
a = np.array(src).astype(np.float32)
alpha = a[..., 3] / 255.0

# Body: from each column's skyline down to the bottom.
solid = alpha > 0.5
top = np.where(solid.any(axis=0), solid.argmax(axis=0), H)
rows = np.arange(H)[:, None]
body = (rows >= top[None, :]).astype(np.float32)
bodyImg = Image.fromarray((body * 255).astype(np.uint8))
rim = bodyImg.filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.GaussianBlur(1.1))
bodyImg = bodyImg.filter(ImageFilter.GaussianBlur(0.8))
rimA = np.array(rim).astype(np.float32) / 255.0
bodyA = np.array(bodyImg).astype(np.float32) / 255.0

yy = np.linspace(0, 1, H)[:, None] * np.ones((1, W))
xx = np.ones((H, 1)) * np.linspace(0, 1, W)[None, :]
c0 = np.array([150, 163, 190], np.float32)   # high rock: pale slate blue
c1 = np.array([92, 106, 138], np.float32)    # foot of the mountain: deeper
rock = c0[None, None, :] * (1 - yy[..., None]) + c1[None, None, :] * yy[..., None]
rock *= (1.10 - 0.22 * xx)[..., None]        # lit from the upper left

# Soft depth where rock meets snow: darker just under the snow edges.
snowBlur = np.array(Image.fromarray((alpha * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(9))).astype(np.float32) / 255.0
rock *= (1.0 - 0.18 * snowBlur * (1 - alpha))[..., None]

rimCol = np.array([58, 70, 100], np.float32)
out = np.zeros((H, W, 3), np.float32)
outA = np.maximum(rimA, bodyA)
# rim colour where only the rim is, rock where the body is
out = rimCol[None, None, :] * (1 - bodyA[..., None]) + rock * bodyA[..., None]
# snow on top, a touch cool so it sits in a blue daylight
snow = a[..., :3] * np.array([0.975, 0.99, 1.0], np.float32)
snow = snow + (255 - snow) * 0.38 * (1 - alpha[..., None] * 0)  # soften the grey shading on the snow
out = out * (1 - alpha[..., None]) + snow * alpha[..., None]
outA = np.maximum(outA, alpha)

# The foot dissolves into the page instead of ending on a hard flat line with square corners.
fy = np.clip((np.linspace(0, 1, H) - 0.66) / 0.34, 0, 1)
fade = (1 - (fy * fy * (3 - 2 * fy)))[:, None]
outA = outA * fade

res = np.dstack([np.clip(out, 0, 255), outA * 255]).astype(np.uint8)
Image.fromarray(res, 'RGBA').save('../../app/src/main/res/drawable-nodpi/mountain_light.png', optimize=True)
bg = Image.new('RGBA', (W, H), (244, 245, 249, 255)); bg.alpha_composite(Image.fromarray(res, 'RGBA')); bg.convert('RGB').save('/tmp/light_new.png')

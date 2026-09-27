#!/usr/bin/env python3
"""Render docs/workshop/images/41-god-rays.gif: the Workshop's "New!" card of the god rays (pzopt.GodRays) in the animated
New! format (harness/newcard.py). Left half: god rays off, then on, in two scenes from the showcase video's matched runs
(the lossless panes <run>/pane.mkv of harness/stitch-godrays.py: native 1918x1400 crops of the 5120x2160 picture, both
runs on one timeline from their route starts): the sunlit room at 17:00 (runs gv-room-off / -on) and the morning fog at
08:00 (gv-fog-off / -on). Right half: what the feature draws and what it costs (docs/findings-god-rays-2026-09-27.md).

    harness/queue.sh submit media --label godrays-card-gif --out docs/workshop/images/41-god-rays.gif \\
        -- python3 harness/godrays-card-gif.py [--runs <dir holding the gv-* runs>]
    (--still <png> [--scene N] [--on]: one frame, the layout check)
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import BG, INK2, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/41-god-rays.gif"
FPS = 10
OFF_S, ON_S = 1.6, 2.6  # per scene: the stock look, then god rays on
PANE_W, PANE_H, PANE_FPS = 1918, 1400, 30

INTRO = ("Light you can see in the air, off by default: sunbeams through every sunlit window and open doorway into a "
         "room, with the lit patches on the floor and dust motes drifting in the shafts; shafts of light and shade in "
         "fog and morning mist where buildings and trees block the low sun; lamps, torches and headlights glowing in "
         "mist and rain. It follows the real sun and moon, clouds and weather.")
ROWS = [
    ("Sunlit windows", "into rooms, at any hour",
     (None, "flat light"), (None, "shafts and motes"), "new"),
    ("Fog and morning mist", "with the sun low",
     (None, "even fog"), (None, "light and shade"), "new"),
    ("Lamps, torches, headlights", "in fog, rain and mist",
     (None, "no glow"), (None, "a soft halo"), "new"),
    ("Frame time added", "RTX 4090 at 5K, fog / sunlit room",
     (None, "-"), (None, "12 µs"), ("0.3 % at 240 fps", "worse")),
]
FOOTER = [
    "Left: god rays off, then on, the game's own frames: a Rosewood diner at 17:00, the church lot in morning fog at 08:00.",
    "Off by default: Options > Enhancements > God rays (strength, dust, haze, lamps), applies at once. Windows and Linux; "
    "needs OpenGL 4.3 (not on macOS).",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-god-rays-2026-09-27.md.",
]
SCENES = [("gv-room", "Rosewood diner, 17:00"), ("gv-fog", "Morning fog, 08:00")]


def run_dir(root, label):
    runs = sorted(glob.glob(os.path.join(root, label + "-2*")))
    if not runs:
        sys.exit(f"no {label} run under {root}")
    return Path(runs[-1])


def pane_frames(path, n, fps):
    """The first n frames of a pane at fps (resampled from 30 fps), as HxWx3 arrays."""
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-vf", f"fps={fps}", "-frames:v", str(n),
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
    k = len(out) // (PANE_W * PANE_H * 3)
    return np.frombuffer(out[: k * PANE_W * PANE_H * 3], np.uint8).reshape(k, PANE_H, PANE_W, 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default=os.path.expanduser("~/Documents/ZedProjects/PZ_Optimization-godrays/harness/runs"))
    ap.add_argument("--still")
    ap.add_argument("--scene", type=int, default=0)
    ap.add_argument("--on", action="store_true")
    a = ap.parse_args()
    card = Card("New! God rays", "2026-09-27", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, mw, mh = card.media
    lab, cap = font(24, "bold"), font(20)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-godrays-"))
    k = 0
    n = int((OFF_S + ON_S) * FPS)
    for si, (label, where) in enumerate(SCENES):
        if a.still and si != a.scene:
            continue
        off = pane_frames(run_dir(a.runs, label + "-off") / "pane.mkv", n, FPS)
        on = pane_frames(run_dir(a.runs, label + "-on") / "pane.mkv", n, FPS)
        m = min(len(off), len(on))
        ch = PANE_H
        cw = min(PANE_W, round(ch * mw / mh))
        box = ((PANE_W - cw) // 2, 0, (PANE_W - cw) // 2 + cw, ch)
        for i in range(m):
            is_on = a.on if a.still else i >= OFF_S * FPS
            pane = Image.fromarray((on if is_on else off)[i]).crop(box).resize((mw, mh), Image.LANCZOS)
            im = card.base()
            im.paste(pane, (x, y))
            d = ImageDraw.Draw(im)
            text, colour = ("GOD RAYS ON", OPT) if is_on else ("GOD RAYS OFF (STOCK LOOK)", STOCK)
            tw = lab.getlength(text)
            d.rounded_rectangle((x + 4, y + 12, x + 24 + tw, y + 50), radius=6, fill=BG)
            d.text((x + 14, y + 31), text, font=lab, fill=colour, anchor="lm")
            cwid = cap.getlength(where)
            d.rounded_rectangle((x + 4, y + mh - 46, x + 24 + cwid, y + mh - 12), radius=6, fill=BG)
            d.text((x + 14, y + mh - 29), where, font=cap, fill=INK2, anchor="lm")
            if a.still:
                im.save(a.still)
                print(f"wrote {a.still} ({label}, {'on' if is_on else 'off'})")
                return
            k += 1
            im.save(work / f"{k:04d}.png")
        print(f"{label}: {m} frames")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "96")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB, {k} frames)")


if __name__ == "__main__":
    main()

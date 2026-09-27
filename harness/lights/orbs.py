#!/usr/bin/env python3
"""Light orbs in an explore=lights walk (2026-09-27, the maintainer's floating-orbs report): how much the god rays' local
light glow adds round each light, from a run with devGodRaysAlternate=N devGodRaysAlternateAll=true + devCapture.

    orbs.py <run> [--png out.png] [--kind 0|1]

Frames are paired on / off (the nearest frame of the other phase within 1 s while the player stood still: the camera is
the same); around each light's window position (pzopt-lights.out, L lines) the luma added by the on frame is measured in
a 120 window-px radius: max, 99.5th percentile, and the pixels brightened by more than 40 / 255 (an orb: a hot, compact
blob). A light counts as an orb when its max added luma is above 60. --png writes the worst pair's crops side by side.
"""
import argparse
import bisect
import os
import re

import numpy as np
from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--png")
    ap.add_argument("--kind", type=int, choices=(0, 1), help="the worst pair of this kind only (0 car, 1 lamp)")
    a = ap.parse_args()
    c = os.path.join(a.run, "capture")
    lines = open(os.path.join(c, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    st = [int(x) for x in lines[1:] if x.strip()]
    raw = np.memmap(os.path.join(c, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(st), raw.size // (w * h * 4))
    per = t0 = None
    for line in open(os.path.join(a.run, "console.txt"), errors="replace"):
        m = re.search(r"god rays: alternating every (\d+) ms from epoch_ms (\d+)", line)
        if m:
            per, t0 = int(m.group(1)), int(m.group(2))
    # the walk log: player (still or not) and the lights' window positions per epoch ms
    P, L = [], {}
    sw = None
    for line in open(os.path.join(a.run, "pzopt-lights.out")):
        f = line.split()
        if len(f) < 3 or f[0].startswith("#"):
            continue
        ms = int(f[0])
        if f[1] == "P":
            P.append((ms, float(f[2]), float(f[3]), f[4] == "true"))
            sw = int(f[6])
        elif f[1] == "L":
            L.setdefault(ms, []).append((int(f[2]), float(f[6]), float(f[7])))
    pt = [p[0] for p in P]
    lt = sorted(L)
    scale = w / float(sw)

    def still_between(t1, t2):
        i, j = bisect.bisect_left(pt, min(t1, t2)), bisect.bisect_right(pt, max(t1, t2))
        seg = P[i:j]
        return seg and not any(p[3] for p in seg) and max(abs(p[1] - seg[0][1]) + abs(p[2] - seg[0][2]) for p in seg) < 0.05

    def frame(k):
        return raw[k * w * h * 4:(k + 1) * w * h * 4].reshape(h, w, 4)[::-1, :, :3].astype(np.float32)

    def phase_on(t):
        return ((t - t0) // per) % 2 == 0

    worst, rows = None, []
    for k in range(n):
        if not phase_on(st[k]) or (st[k] - t0) % per < 250:
            continue
        best = None
        for j in range(max(0, k - 6), min(n, k + 7)):
            if phase_on(st[j]) or (st[j] - t0) % per < 250 or abs(st[j] - st[k]) > 1000:
                continue
            if still_between(st[j], st[k]) and (best is None or abs(st[j] - st[k]) < abs(st[best] - st[k])):
                best = j
        if best is None:
            continue
        li = bisect.bisect_left(lt, st[k])
        lights = L.get(lt[min(li, len(lt) - 1)], [])
        fa, fb = frame(k), frame(best)
        luma = lambda f: f @ np.array([0.2126, 0.7152, 0.0722], np.float32)
        d = luma(fa) - luma(fb)
        yy, xx = np.mgrid[0:h, 0:w]
        for kind, lx, ly in lights:
            cx, cy = lx * scale, ly * scale
            m = (xx - cx) ** 2 + (yy - cy) ** 2 < (120 * scale) ** 2
            if m.sum() < 50:
                continue
            v = d[m]
            r = (k, best, kind, v.max(), np.percentile(v, 99.5), int((v > 40).sum() / scale ** 2))
            rows.append(r)
            if (a.kind is None or kind == a.kind) and (worst is None or r[3] > worst[3]):
                worst = r + (cx, cy)
    if not rows:
        print("no still on / off pairs")
        return
    mx = np.array([r[3] for r in rows])
    print("%d light-pairs from still stretches: added luma max %.0f (median of maxima %.0f), p99.5 max %.0f, orb px (>40) max %d; orbs (max > 60): %d"
          % (len(rows), mx.max(), np.median(mx), max(r[4] for r in rows), max(r[5] for r in rows), int((mx > 60).sum())))
    for kind in (0, 1):
        sub = [r for r in rows if r[2] == kind]
        if sub:
            print("  %s: %d pairs, max %.0f, p99.5 %.0f" % ("car" if kind == 0 else "lamp", len(sub), max(r[3] for r in sub), max(r[4] for r in sub)))
    if a.png and worst:
        k, j, cx, cy = worst[0], worst[1], worst[6], worst[7]
        R = int(160 * scale)
        box = (int(max(0, cx - 2 * R)), int(max(0, cy - R)), int(min(w, cx + 2 * R)), int(min(h, cy + R)))
        A = Image.fromarray(frame(j).astype(np.uint8)).crop(box)
        B = Image.fromarray(frame(k).astype(np.uint8)).crop(box)
        out = Image.new("RGB", (A.width * 2, A.height))
        out.paste(A, (0, 0))
        out.paste(B, (A.width, 0))
        out = out.resize((out.width * 2, out.height * 2), Image.LANCZOS)
        out.save(a.png)
        print("worst pair (off left, on right):", a.png)


if __name__ == "__main__":
    main()

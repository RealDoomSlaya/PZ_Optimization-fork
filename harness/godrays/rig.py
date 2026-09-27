#!/usr/bin/env python3
"""Offline rig for pzopt.GodRays: a dumped game frame through the real compute + composite GLSL on this GPU.

    rig.py <dump dir> <tag> [--out DIR] [--time N] [--view 0..4] [--strength 1.0] [--sigma-out X] [--sigma-in X]
           [--hour-dir lx,ly,lz] [--crop x,y,w,h] [--scale 0.5]

A dump (devGodRaysDumpAt, ~/Zomboid/pzopt-godrays/<tag>-*) holds the world colour and depth right before the fog pass,
the occupancy texture and the frame's state (view mapping, grid, light, haze). The rig recomputes the visibility and
the integration of the whole volume, composites like the patched screen.frag (without the stock screen grading), and
writes <out>/<tag>-src.png, <tag>-rays.png and, with --view, the dev views. --time N repeats each stage N times and
prints the GPU medians (the desktop must be idle: check harness/queue.sh list first).

Needs moderngl (headless EGL): python -m venv ~/.cache/pzopt-gr-venv && ~/.cache/pzopt-gr-venv/bin/pip install moderngl numpy pillow."""
import argparse
import math
import os
import sys

import numpy as np
import moderngl
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glsl import shader  # noqa: E402

OCC_N, OCC_L = 256, 16


def load_view(path):
    v = {}
    for line in open(path):
        if "=" in line:
            k, x = line.strip().split("=", 1)
            try:
                v[k] = float(x)
            except ValueError:
                v[k] = x
    return v


def floor_mod(a, n):
    r = math.fmod(a, n)
    return r + n if r < 0 else r


QUAD_VS = """#version 330
in vec2 p; out vec2 vUV;
void main() { vUV = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }
"""


class Rig:
    def __init__(self, ddir, tag, args):
        self.args = args
        self.v = v = load_view(os.path.join(ddir, tag + "-view.txt"))
        self.w, self.h = int(v["w"]), int(v["h"])
        self.ctx = ctx = moderngl.create_standalone_context(backend="egl", require=460)
        col = np.fromfile(os.path.join(ddir, tag + "-color.bin"), np.uint8).reshape(self.h, self.w, 4)
        dep = np.fromfile(os.path.join(ddir, tag + "-depth.bin"), np.float32).reshape(self.h, self.w)
        occ = np.fromfile(os.path.join(ddir, tag + "-occ.bin"), np.uint32 if os.path.getsize(os.path.join(ddir, tag + "-occ.bin")) == OCC_N * OCC_N * OCC_L * 4 else np.uint16).reshape(OCC_L, OCC_N, OCC_N).astype(np.uint32)
        self.col_np, self.dep_np, self.occ_np = col, dep, occ
        self.color = ctx.texture((self.w, self.h), 4, col.tobytes())
        self.color.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.depth = ctx.texture((self.w, self.h), 1, dep.tobytes(), dtype="f4")
        self.depth.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.occ = ctx.texture3d((OCC_N, OCC_N, OCC_L), 1, occ.tobytes(), dtype="u4")
        self.occ.filter = (moderngl.NEAREST, moderngl.NEAREST)
        # the chunks' tops (GodRays.build): walls / roofs / crowns fill their level, a floor stops what is under it
        z0 = int(v["occZ0"])
        blk = occ.reshape(OCC_L, 32, 8, 32, 8)
        lv = np.arange(OCC_L).reshape(OCC_L, 1, 1, 1, 1) + z0
        full = np.where((blk & (6 | 24 | 64 | (15 << 8))) != 0, lv + 1, -99)
        flo = np.where((blk & 1) != 0, lv, -99)
        top = np.maximum(full, flo).max(axis=(0, 2, 4))  # (32 y, 32 x)
        tops = np.clip(np.maximum(top, 0) - z0, 0, 255).astype(np.uint8)
        self.tops = ctx.texture((32, 32), 1, tops.tobytes(), dtype="u1")
        self.tops.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.nx, self.ny, self.nz = int(v["nx"]), int(v["ny"]), int(v["nz"])
        self.vtex = ctx.texture3d((self.nx, self.ny, self.nz), 1, dtype="f1")
        self.ftex = ctx.texture3d((self.nx, self.ny, self.nz), 4, dtype="f2")
        self.ftex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.ftex.repeat_x = True
        self.ftex.repeat_y = True
        self.ftex.repeat_z = False
        self.stex = ctx.texture3d((self.nx, self.ny, self.nz), 1, dtype="f1")  # the fog composite's one-byte shade
        self.stex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.vis = ctx.compute_shader(shader("VIS_CS"))
        self.integ = ctx.compute_shader(shader("INTEGRATE_CS"))
        frag = ("#version 330\n#extension GL_ARB_shading_language_420pack : enable\nin vec2 vUV; out vec4 fragOut;\n"
                "uniform sampler2D DIFFUSE;\nvec4 pzGrBicubicStock(sampler2D s, vec2 t) { return texture(s, t); }\n"
                + shader("SCREEN_GLSL") + "\nvoid main() { fragOut = vec4(textureBicubic(DIFFUSE, vUV).rgb, 1.0); }\n")
        self.screen = ctx.program(vertex_shader=QUAD_VS, fragment_shader=frag)
        self.quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], np.float32).tobytes())
        self.vao = ctx.vertex_array(self.screen, [(self.quad, "2f", "p")])
        self.out = ctx.texture((self.w, self.h), 4)
        self.fbo = ctx.framebuffer([self.out])
        # the light volumes (apertures), when the dump has them
        pp = os.path.join(ddir, tag + "-prisms.bin")
        self.prisms = None
        self.aw, self.ah = (self.w + 1) // 2, (self.h + 1) // 2
        self.ap = ctx.texture((self.aw, self.ah), 2, dtype="f2")
        self.ap.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.ap.repeat_x = self.ap.repeat_y = False
        self.apfbo = ctx.framebuffer([self.ap])
        qp = os.path.join(ddir, tag + "-planes.bin")
        if os.path.exists(pp) and os.path.exists(qp) and not args.no_apertures:
            data = np.fromfile(pp, np.float32)
            self.prisms = ctx.buffer(data.tobytes())
            self.nprism = len(data) // 4
            pl = np.fromfile(qp, np.float32).reshape(-1, 4)
            rows = (len(pl) + 1023) // 1024
            pad = np.zeros((rows * 1024, 4), np.float32)
            pad[:len(pl)] = pl
            self.planes = ctx.texture((1024, rows), 4, pad.tobytes(), dtype="f4")
            self.planes.filter = (moderngl.NEAREST, moderngl.NEAREST)
            # (the rig has no texture buffers: the same texels in a 1024-wide 2D texture)
            vert = shader("AP_VERT").replace("uniform samplerBuffer uPlanes;", "uniform sampler2D uPlanes;")
            import re as _re
            vert = _re.sub(r"texelFetch\(uPlanes, (b(?: \+ \d)?)\)", lambda m: "texelFetch(uPlanes, ivec2((%s) %% 1024, (%s) / 1024), 0)" % (m.group(1), m.group(1)), vert)
            self.approg = ctx.program(vertex_shader=vert, fragment_shader=shader("AP_FRAG"))
            self.apvao = ctx.vertex_array(self.approg, [(self.prisms, "4f", "aPos")])

    def light(self):
        v = self.v
        lx, ly, lz = v["lx"], v["ly"], v["lz"]
        if self.args.light:
            lx, ly, lz = [float(t) for t in self.args.light.split(",")]
            n = math.sqrt(lx * lx + ly * ly + lz * lz)
            lx, ly, lz = lx / n, ly / n, lz / n
        h = math.hypot(lx, ly)
        slope = 0.0 if h < 1e-3 else lz / h / 2.4494897
        return lx, ly, lz, h, slope

    def set_common(self, prog):
        v = self.v
        lx, ly, lz, h, slope = self.light()
        cu, cv = v["cu"], v["cv"]
        refX, refY = int(v["refX"]), int(v["refY"])
        so = self.args.sigma_out if self.args.sigma_out is not None else v["sigmaOut"]
        si = self.args.sigma_in if self.args.sigma_in is not None else v["sigmaIn"]
        vals = {
            "uN": (self.nx, self.ny, self.nz, int(v["occZ0"])),
            "uRef": (refX, refY, int(round((refX - refY) / cu)), int(round((refX + refY) / cv))),
            "uCell": (cu, cv, v["cz"], v["zLo"]),
            "uSun": (lx / max(h, 1e-6), ly / max(h, 1e-6), slope, 1.0 if h < 1e-3 else 0.0),
            "uLim": (v["maxTop"], 64.0, 0.55, 0.0),
            "uSigma": (so, si, v["hazeH"], 0.0),
            "uRegion": (int(v["i0"]), int(v["j0"]), self.nx, self.ny),
            "uOcc": 19, "uTop": 21,
        }
        for k, x in vals.items():
            if k in prog:
                prog[k].value = x

    def local(self):
        v = self.v
        n = int(v.get("lights", 0))
        self.loc_on = False
        if n <= 0 or str(v.get("lightsFroxel", "false")) != "true":
            return
        if not hasattr(self, "locprog"):
            self.locprog = self.ctx.compute_shader(shader("LOCAL_CS"))
            self.loctex = self.ctx.texture3d((self.nx, self.ny, self.nz), 4, dtype="f2")
            self.loctex.filter = (moderngl.LINEAR, moderngl.LINEAR)
            self.loctex.repeat_x = self.loctex.repeat_y = True
            self.loctex.repeat_z = False
        la, lb, lc = [], [], []
        for i in range(16):
            for arr, key in ((la, "la"), (lb, "lb"), (lc, "lc")):
                t = str(v.get("%s%d" % (key, i), "0,0,0,0")).split(",")
                arr.extend(float(x) for x in t)
        p = self.locprog
        self.set_common(p)
        p["uNL"].value = n
        p["uLA"].write(np.array(la, np.float32).tobytes())
        p["uLB"].write(np.array(lb, np.float32).tobytes())
        p["uLC"].write(np.array(lc, np.float32).tobytes())
        self.occ.use(19)
        self.tops.use(21)
        self.loctex.bind_to_image(3, read=False, write=True)
        p.run((self.nx + 7) // 8, (self.ny + 7) // 8, 1)
        self.ctx.memory_barrier()
        self.loc_on = True

    def compute(self):
        self.apertures()
        self.local()
        self.occ.use(19)
        self.tops.use(21)
        self.set_common(self.vis)
        self.vtex.bind_to_image(0, read=False, write=True)
        self.vis.run((self.nx + 7) // 8, (self.ny + 7) // 8, (self.nz + 3) // 4)
        self.ctx.memory_barrier()
        self.set_common(self.integ)
        self.vtex.bind_to_image(0, read=True, write=False)
        self.ftex.bind_to_image(1, read=False, write=True)
        self.stex.bind_to_image(3, read=False, write=True)
        self.integ.run((self.nx + 7) // 8, (self.ny + 7) // 8, 1)
        self.ctx.memory_barrier()

    def apertures(self):
        v = self.v
        self.apfbo.use()
        self.apfbo.viewport = (0, 0, self.aw, self.ah)
        self.apfbo.clear(0.0, 0.0, 0.0, 0.0)
        if self.prisms is None:
            return
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_func = moderngl.ONE, moderngl.ONE
        self.ctx.disable(moderngl.DEPTH_TEST | moderngl.CULL_FACE)
        p = self.approg
        p["uMapA"].value = (v["kA"], v["cA"], v["kB"], v["cB"])
        p["uMapB"].value = (v["kC"], v["cC"], v["vx"], v["vy"])
        p["uVp"].value = (self.w, self.h, 2.0, 0.0)
        p["uDepth"].value = 18
        if "uPlanes" in p:
            p["uPlanes"].value = 26
            self.planes.use(26)
        if "uOrg" in p:
            p["uOrg"].value = (v.get("prismOrgX", 0.0), v.get("prismOrgY", 0.0), 0.0, 0.0)
        if "uLight" in p:
            lx, ly, lz, h, slope = self.light()
            p["uLight"].value = (lx, ly, lz, 0.0)
        self.depth.use(18)
        self.apvao.render(moderngl.TRIANGLES, vertices=self.nprism)
        self.ctx.disable(moderngl.BLEND)

    def mapping(self):
        v = self.v
        cu, cv, cz, zLo = v["cu"], v["cv"], v["cz"], v["zLo"]
        kA, cA, kB, cB, kC, cC = v["kA"], v["cA"], v["kB"], v["cB"], v["kC"], v["cC"]
        W, H = self.w, self.h  # the rig's depth texture is the viewport (vx = vy = 0 in the dumps)
        nx, ny, nz = self.nx, self.ny, self.nz
        offU = floor_mod((v["ox"] - v["oy"]) / cu, nx)
        offV = floor_mod((v["ox"] + v["oy"]) / cv, ny)
        mapX = (kA * W / cu / nx, 0.0, 0.0, (cA / cu + offU) / nx)
        mapY = (0.0, kB * H / cv / ny, 0.0, (cB / cv + offV) / ny)
        zs = 1.0 / (nz * cz)
        mapZ = (0.0, -kB * H / 8.0 * zs, kC / 8.0 * zs, ((cC - cB) / 8.0 - zLo) * zs + 0.5 / nz)
        ground = (0.0 - zLo) * zs + 0.5 / nz
        return mapX, mapY, mapZ, ground

    def low_pass(self, view=0):
        v = self.v
        if not hasattr(self, "lowprog"):
            self.lowprog = self.ctx.program(vertex_shader=shader("QUAD_VERT"), fragment_shader=shader("LOW_FRAG"))
            self.lw, self.lh = (self.w + 3) // 4, (self.h + 3) // 4
            self.low = self.ctx.texture((self.lw, self.lh), 4, dtype="f2")
            self.low.filter = (moderngl.LINEAR, moderngl.LINEAR)
            self.low.repeat_x = self.low.repeat_y = False
            self.lowfbo = self.ctx.framebuffer([self.low])
            tri = self.ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], np.float32).tobytes())
            self.lowvao = self.ctx.vertex_array(self.lowprog, [(tri, "2f", "aPos")])
        mapX, mapY, mapZ, ground = self.mapping()
        lx, ly, lz, h, slope = self.light()
        s = self.args.strength
        p = self.lowprog
        vals = {"uVol": 17, "uDepth": 18, "uLow": (self.w / self.lw, self.h / self.lh, 0.0 if self.prisms is not None else 1.0, 0.0),
                "uX": mapX, "uY": mapY, "uZ": mapZ, "uC": (v["r"] * s, v["g"] * s, v["b"] * s, ground),
                "uL": (lx, ly, lz, v["patch"]), "uDev": (float(view), 0.0, 0.0, 0.0),
                "uMapA": (v["kA"], v["cA"], v["kB"], v["cB"]), "uMapB": (v["kC"], v["cC"], v["vx"], v["vy"]),
                "uLocal": 28, "uLocOn": (1.0 if getattr(self, "loc_on", False) else 0.0, 0.0, 0.0, 0.0), "uCl0": (0.0, 0.0, 0.0, 0.0)}
        if getattr(self, "loc_on", False):
            self.loctex.use(28)
        for k, x in vals.items():
            if k in p:
                p[k].value = x
        self.ftex.use(17)
        self.depth.use(18)
        self.lowfbo.use()
        self.ctx.disable(moderngl.BLEND)
        self.lowvao.render(moderngl.TRIANGLES)

    def composite(self, view=0):
        v = self.v
        self.low_pass(view)
        s = self.args.strength
        p = self.screen
        on = self.prisms is not None
        vals = {"pzGrP": (1.0, float(view), v["patch"], 0.0), "pzGrD": (0.0, 0.0, float(self.w), float(self.h)),
                "pzGrC": (v["r"] * s, v["g"] * s, v["b"] * s, 0.0),
                "pzGrAB": (0.0, 0.0, 1.0, 1.0) if on else (2.0, 2.0, -1.0, -1.0),
                "pzGrAS": ((self.args.sigma_in if self.args.sigma_in is not None else v["sigmaIn"]) * 0.6123724 * self.args.dust, 1.0 if on else 0.0, 0.0, 0.0),
                "pzGrLow": 17, "pzGrAp": 20, "DIFFUSE": 0}
        for k, x in vals.items():
            if k in p:
                p[k].value = x
        self.color.use(0)
        self.low.use(17)
        self.ap.use(20)
        self.fbo.use()
        self.vao.render(moderngl.TRIANGLE_STRIP)

    def image(self):
        a = np.frombuffer(self.out.read(), np.uint8).reshape(self.h, self.w, 4)[::-1, :, :3]
        return a

    def src_image(self):
        return self.col_np[::-1, :, :3]

    def time(self, n):
        q = self.ctx.query(time=True)
        res = {}
        for name, fn in (("visibility+integration", self.compute), ("apertures", self.apertures), ("low pass", lambda: self.low_pass(0)), ("composite (incl. low)", lambda: self.composite(0))):
            ts = []
            for _ in range(n):
                with q:
                    fn()
                self.ctx.finish()
                ts.append(q.elapsed / 1000.0)
            res[name] = sorted(ts)[len(ts) // 2]
        vis_only = []
        self.occ.use(19)
        self.tops.use(21)
        for _ in range(n):
            self.set_common(self.vis)
            self.vtex.bind_to_image(0, read=False, write=True)
            with q:
                self.vis.run((self.nx + 7) // 8, (self.ny + 7) // 8, (self.nz + 3) // 4)
            self.ctx.finish()
            vis_only.append(q.elapsed / 1000.0)
        res["visibility"] = sorted(vis_only)[len(vis_only) // 2]
        return res


def save(a, path, crop=None, scale=1.0):
    im = Image.fromarray(np.ascontiguousarray(a))
    if crop:
        x, y, w, h = crop
        im = im.crop((x, y, x + w, y + h))
    if scale != 1.0:
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    im.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("tag")
    ap.add_argument("--out", default="/home/diegov/.cache/pzopt-godrays-rig")
    ap.add_argument("--time", type=int, default=0)
    ap.add_argument("--view", type=int, default=-1)
    ap.add_argument("--strength", type=float, default=1.0)
    ap.add_argument("--dust", type=float, default=1.0, help="dust gain over the haze (pzGrC.w)")
    ap.add_argument("--no-apertures", action="store_true", help="the volume's room light instead of the dumped light volumes")
    ap.add_argument("--sigma-out", type=float, default=None)
    ap.add_argument("--sigma-in", type=float, default=None)
    ap.add_argument("--light", default=None, help="lx,ly,lz direction to the light (world: x east, y south, z up)")
    ap.add_argument("--crop", default=None)
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--suffix", default="")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    r = Rig(args.dump, args.tag, args)
    crop = [int(t) for t in args.crop.split(",")] if args.crop else None
    r.compute()
    r.composite(0)
    base = os.path.join(args.out, args.tag + args.suffix)
    save(r.src_image(), base + "-src.png", crop, args.scale)
    save(r.image(), base + "-rays.png", crop, args.scale)
    if args.view >= 0:
        views = [args.view] if args.view > 0 else [1, 2, 3, 4]
        for vw in views:
            r.composite(vw)
            save(r.image(), base + "-view%d.png" % vw, crop, args.scale)
    v = r.v
    print("dump %s: %dx%d zoom %.2f volume %dx%dx%d cells %.3f/%.3f/%.3f zLo %g light %.2f,%.2f,%.2f sigma %.4f/%.3f colour %.2f,%.2f,%.2f patch %.2f"
          % (args.tag, r.w, r.h, v["zoom"], r.nx, r.ny, r.nz, v["cu"], v["cv"], v["cz"], v["zLo"], v["lx"], v["ly"], v["lz"], v["sigmaOut"], v["sigmaIn"],
             v["r"], v["g"], v["b"], v["patch"]))
    if args.time:
        for k, t in r.time(args.time).items():
            print("  %-24s %8.1f us" % (k, t))
    print("wrote", base + "-*.png")


if __name__ == "__main__":
    main()

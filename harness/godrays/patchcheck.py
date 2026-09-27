#!/usr/bin/env python3
"""Compile the game's own screen.frag (util/math inlined) with pzopt.GodRays' patch applied, on this GPU: the check the
game makes at launch, offline. Exit 1 with the compiler's log on failure."""
import os, re, sys
import moderngl
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glsl import shader

D = os.environ.get("PZ_SHADERS", "/games/steamapps/common/ProjectZomboid/projectzomboid/media/shaders/")


def patched():
    code = open(D + "screen.frag").read().replace('#include "util/math"', open(D + "util/math.glsl").read().replace("#version 110", ""))
    sig = "vec4 textureBicubic(sampler2D sampler, vec2 texCoords)"
    at = code.index(sig)
    o = code.index("{", at)
    d = 0
    for i in range(o, len(code)):
        if code[i] == "{":
            d += 1
        elif code[i] == "}":
            d -= 1
            if d == 0:
                end = i + 1
                break
    eol = code.index("\n")
    return (code[:eol + 1] + "#extension GL_ARB_shading_language_420pack : enable\n" + code[eol + 1:at]
            + "vec4 pzGrBicubicStock(sampler2D sampler, vec2 texCoords)" + code[at + len(sig):end] + "\n" + shader("SCREEN_GLSL") + code[end:])


if __name__ == "__main__":
    ctx = moderngl.create_standalone_context(backend="egl", require=460)
    try:
        ctx.program(vertex_shader=open(D + "screen.vert").read(), fragment_shader=patched())
        print("screen.frag + god rays: compiles")
    except Exception as e:
        print(e)
        sys.exit(1)

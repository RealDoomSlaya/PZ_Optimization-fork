#!/usr/bin/env python3
"""The GLSL strings of pzopt.GodRays (and friends), as the game compiles them, for the offline rigs.

    from glsl import shader
    src = shader("VIS_CS")               # GodRays.java's VIS_CS with COMMON_GLSL and the constants in place

The Java source builds each shader with String.join("\\n", ...): string literals, other shader constants by name, and
literals concatenated with a few int / float constants (" + OCC_L + "). Those are substituted from CONSTANTS; anything
else raises, so a new constant in the Java has to be added here."""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "src", "pzopt", "pzopt")

CONSTANTS = {
    "OCC_L": "16", "OCC_N": "256", "(OCC_N - 1)": "255", "VIEW_PATH": "4.8989795",
    "VOL_UNIT": "17", "DEPTH_UNIT": "18", "OCC_UNIT": "19", "AUX_UNIT": "20", "TOP_UNIT": "21", "(OCC_CHUNKS - 1)": "31", "MARCH_V_UNIT": "22", "MARCH_MM_UNIT": "23", "MARCH_HIST_UNIT": "24", "PLANE_UNIT": "25", "APC_WORLD_UNIT": "26", "MAX_LIGHTS": "16", "CLOUD_UNIT": "27", "LOCAL_UNIT": "28", "FS_DEPTH_UNIT": "18", "LOCAL_CORE_SQ": "2.25", "HAZE_UNIT": "29",
}


def _java(name):
    return os.path.join(SRC, name)


def _block(src, name):
    m = re.search(r'static final String ' + name + r' = String\.join\("\\n",\n(.*?)\);\n', src, re.S)
    if not m:
        raise KeyError(name)
    return m.group(1)


def _literal(tok):
    return tok.encode().decode("unicode_escape")


def shader(name, java="GodRays.java"):
    src = open(_java(java)).read()
    out = []
    for line in _block(src, name).split("\n"):
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        line = re.sub(r',\s*(//.*)?$', '', line)  # the separator and a trailing comment
        if re.fullmatch(r'[A-Z_]+', line):
            out.append(shader(line, java).rstrip("\n"))
            continue
        # "..." (+ CONST + "...")*
        parts = re.findall(r'"((?:[^"\\]|\\.)*)"|\+\s*(\([A-Z_ \-+0-9]+\)|[A-Z_]+)\s*(?=\+)', line)
        s = ""
        for lit, const in parts:
            if const:
                if const not in CONSTANTS:
                    raise KeyError("unknown constant " + const + " in " + name)
                s += CONSTANTS[const]
            else:
                s += _literal(lit)
        out.append(s)
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    import sys
    print(shader(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "GodRays.java"))

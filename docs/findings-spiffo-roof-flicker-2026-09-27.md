# Spiffo's roof flicker with DLSS + per-pixel lighting (2026-09-27)

Maintainer's report: "the Spiffo floor is flickering" in save `Sandbox/2026-09-27_08-41-12` (night, parked by Spiffo's,
their Enhancements tab: DLSS, pixelLight, AO, sun shadows, reflections, sharp sprite filter, grading, HDR, darkness floor).

## Reproduced

`--mode play --source-save Sandbox/2026-09-27_08-41-12 --quit-after 25 --prop devCapture=12,2,60,50` (runs `spf-*`),
metric `/tmp/spf-flick.py` style: share of pixels of the roof crop changing > 30 (RGB sum) between captured frames.
Spiffo's flat roof flipped every frame between a smooth dark roof and rows of bright triangles along the tile rows, an
exact 8-frame cycle (DLSS's Halton jitter): 8.5 % of the crop changed per frame, whole screen 2.05 % of pixels flickering.

One key off per run: `upscaler=off` 0.00 %, `pixelLight=false` 0.01 %; `spriteFilter`, `reflections`, `sunShadows`,
`ambientOcclusion` off: still 8.5-9 %. So pixel lighting under a jittered (DLSS) world pass.

## Cause

The composite reconstructs each pixel's world position from its window position and the chunk texture's depth
(`pplPos(gl_FragCoord.xy, d)`), and picks the level whose light it takes with `lz = floor(P.z + 0.006)`. Two things met:

1. The window position was the pixel centre, the depth the nearest texel's (DEPTH16, `GL_NEAREST`): a point up to half a
   texel off the surface in height. DLSS's jitter moves the pixel centre inside the texel every frame.
2. Flat roof texels sit within +-0.002 levels of their level (DEPTH16: 0.0013 levels a step; dev view 14). The ones a
   hair below enter the tolerance's "lifted" branch, whose brighter-of-two-levels rule (meant for the top row of a wall of
   the level below, 2026-09-25 grid-line fix) picked the level below: Spiffo's lit dining room under the dark roof.

At native resolution the pixel-centre offset happened to lift every roof texel above the level, so it never showed; at
67 % (DLSS) with the jitter rows crossed the level each frame. DLSS without jitter was stable but wrong (a grey-lit roof).

## Fix (pzopt.PixelLight, both default on)

- `pplTexelHeight`: the height comes from the texel the pixel shows, its centre's window y and its own depth
  (`texelFetch`), not from the jittered pixel centre: the light no longer depends on the jitter.
- `pplFloorSnap`: a texel within two DEPTH16 steps (0.0026 levels) of a level whose texels 2 rows above and below
  reconstruct at the same height (within 35 % of a wall's rise over 2 texels) is a floor on that level: snapped, so the
  lift / brighter rule never takes a roof. A band of 0.006 darkened a wall-top trim (the flat trim sits further below;
  seam rig 24.5 -> 55/MP), 0.0026 does not.
- Dev views `devPplView=14` (height offset from the nearest level, grey, +-0.039 levels) and `15` (wall path / level read /
  lifted); run them with `colorGrading=false hdr=false` (the grade shifts the debug colours).

## Result (desktop, 5120x2160, maintainer's settings)

| run | roof crop changed px / frame | whole screen px flickering (> 20 % of frames) |
|---|---|---|
| old code, DLSS (`spf-repro`) | 8.56 % | 2.051 % |
| `pixelLight=false`, DLSS (reference) | 0.01 % | 0.228 % |
| fix, DLSS (`spf-fix3-dlss`) | 0.04 % | 0.181 % |
| fix, native (`spf-snap2-1x`) | 0.00 % | |

The roof now looks like the DLSS-off / pixel-light-off references (smooth, dark). Chunk-edge seam rig (Rosewood noon,
`harness/seam-lines.py`, control `pixelLight=false`): new long seams old 24.5/MP -> fix 20.2/MP (`spf-seam-*`).
Walk into Spiffo's (`explore=restaurant explore_match=spiffo`): transients dominated by motion, fix <= old; its dining
room is level 0, where the level-below rule cannot fire.

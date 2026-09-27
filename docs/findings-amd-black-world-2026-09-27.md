# Black world on AMD / Windows after the god rays release (2026-09-27)

Fixed in `3f8f7dc` (GitHub release `win-b0bbce05d5-3f8f7dc` + Workshop), and players confirmed it the same evening.
**The root cause is still open**: the fix removes both suspects when god rays is off, so it works whichever one was
real. This note is for when the problem comes back (god rays on, a new shader patch, a driver update).

## What the players saw

- Whole game world black, **the player too**; UI, inventory and speech text drawn normally. Some reported a red
  stripe or a white vision cone with red outlines: that came from other mods (gone with no mods, the black stayed).
- AMD Radeon under Windows (RX 6600, RX 6750 XT, Adrenalin driver 26.8.1 in the one console.txt we got); Intel and
  NVIDIA players were fine. New saves too.
- **God rays off** (`godRays=false` on the console's `settings:` line). The game logs no shader error by default
  (`ShaderProgram` sends link / validate failures to `DebugType.Shader`, which console.txt does not show).
- Started with `153e43e` (the god rays merge); `683e1be` (the Mesa fix, see below) did not help.
- Sources: Workshop comments of 2026-09-27 (Hesin, azer, Ismællö, TAQ-3, ditoseadio) and Hesin's console.txt
  (github.com/huangniuma/1).

Compare the Mesa case earlier that day (`683e1be`, maintainer's flip): **only the baked world** was black
(floor, walls, roofs); characters and other per-frame sprites still drew. A black *player* means the world composite
(`screen.frag`), not just the chunk composite.

## What changed in 153e43e

The god rays code patched two game shaders at launch **for every player, god rays on or off** (the console said
`god rays: haze patched into media/shaders/chunkShader.frag` and `god rays: world composite patched` with god rays off):

- the chunk composite `chunkShader.frag` (GLSL 1.20) got a `sampler3D` (the haze volume);
- the world composite `screen.frag` (GLSL 3.30) got two `sampler2D`s (`pzGrLow`, `pzGrAp`).

Before that, the only always-on patch of `screen.frag` was colour grading, which adds `sampler3D`s only.

## The two suspects

1. **Driver uniform order (most likely for the black player).** After the link the game's
   `ShaderProgram.onCompileSuccess` walks the active uniforms in the order the driver lists them and gives every
   `sampler2D` but the first the next unit (1, 2, ...); the first keeps its link-time value. The game binds the world
   to unit 0 and relies on `DIFFUSE` being the first. The order is up to the driver: NVIDIA and Mesa list `DIFFUSE`
   first, so the desktop and the flip never showed it. A driver that lists `pzGrLow` / `pzGrAp` first moves `DIFFUSE`
   to unit 1 or 2, and the whole world composite samples an empty unit: black picture, UI on top.
2. **The `sampler3D` in the GLSL 1.20 chunk shader.** The game validates each program with every sampler on unit 0;
   a `sampler2D` and a `sampler3D` on one unit fail validation and the game destroys the program (the Mesa bug).
   `683e1be` gave the haze sampler `layout(binding = 29)` through `GL_ARB_shading_language_420pack`. If AMD's Windows
   compiler accepts that line in a 1.20 shader but ignores the binding, validation fails as on Mesa. This alone would
   leave characters visible, so it does not explain the reports by itself.

Not the texture unit count: AMD's Windows driver reports 32 fragment units (opengl.gpuinfo.org), and units 17 / 20 / 29
are inside that.

## The fix (3f8f7dc)

- `GodRays.patchChunk` / `GodRays.patchShader`: nothing is patched when god rays is off at launch; the console says
  `god rays: off at launch, <file> stays stock` (once a file). Turned on mid-game, the light volumes appear at once;
  the haze in the chunk composite and the world composite's tap come with the next launch.
- `Shaders.stockSamplerUnits(program, what)`: the first time a patched game program is used, the game's own
  `sampler2D`s get the units stock would give them, in the driver's order without ours; ours are set to their own
  units. It logs `<what>: the game's samplers back on their stock units in program N (DIFFUSE 2 -> 0, ...)` when it
  had to move one. Called from `GodRays.worldUniforms`, `Hdr.worldUniforms` and `PixelLight`'s `setChunkUniforms`,
  which add `sampler2D`s to game programs the same way.
- Checked before release: desktop (NVIDIA) god rays off and on, flip (Mesa AMD) off / on / on + pixelLight (world
  drawn in all), Mac god rays on and + HDR (route complete). No sampler was moved on any of them.

## If it comes back

1. Get the player's `console.txt` (Zomboid folder; a gist or pastebin link, Steam comments cannot attach files) and a
   screenshot. Note from the screenshot whether the player character is visible.
2. Read the `settings:` line (`godRays=`, `pixelLight=`, `hdr=`) and the patch lines:
   `god rays: haze patched` / `world composite patched` / `off at launch ... stays stock`, `hdr: world expansion
   appended`, `pixel light: the chunk composite shader is not in use, pass mode` (a chunk program was destroyed).
3. Read the result:
   - a `the game's samplers back on their stock units` line **and a normal world**: suspect 1 was real, the helper
     caught it. Nothing to do.
   - **black world with god rays on**, player visible, no such line: suspect 2 (the 1.20 chunk shader's `sampler3D`).
     Fix idea: set the haze sampler's unit between link and validation (needs a `ShaderProgram` override), or make the
     haze volume a `sampler2D` atlas so the game renumbers it like its own samplers.
   - **black world with god rays off**: something else patches a game shader for everyone; list every
     `... patched` line and gate it on its feature the same way.
4. Quick player workaround: turn off god rays (and HDR / per-pixel lighting, the other patches with `sampler2D`s),
   restart the game. `enabled=false` in the Optimizations tab ("Disable all") gives the stock game.
5. Any new patch into a shader the game compiles (`ShaderUnit` hook): gate it on its feature, give every
   non-`sampler2D` sampler a `layout(binding)`, call `Shaders.stockSamplerUnits` on first use and set its own
   `sampler2D` units. A compile test (what every patch does) catches neither of these two failures; only link +
   validation on the target driver would.

## Where to look

- `src/pzopt/pzopt/GodRays.java`: `patchChunk`, `patchShader`, `worldUniforms`, `chunkDraw`, `HAZE_UNIT`, `VOL_UNIT`,
  `AUX_UNIT`.
- `src/pzopt/pzopt/Shaders.java`: `stockSamplerUnits`.
- `src/overrides/zombie/core/opengl/ShaderUnit.java`: the chain of shader patches (god rays, grading, HDR,
  pixelLight, cloud shadows, reflections, sprite filter).
- The game's `ShaderProgram` (`decompiled/zombie/core/opengl/ShaderProgram.java`): the link, the validation with
  every sampler on unit 0, `onCompileSuccess`'s renumbering.
- The Mesa case: commit `683e1be` (its message has the whole story).

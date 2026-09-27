#!/usr/bin/env python3
"""The god rays showcase (2026-09-27, docs/findings-god-rays-2026-09-27.md): four scenes, god rays off (the stock look,
left) beside on (right) from a matched pair of recorded runs, then a card with the measured cost.

Pairs (harness/runs/<label>-*): gv-room-off/-on (17:00, find=sunwindow), gv-fog-off/-on (08:00, fog 0.3), gv-clear-off/-on
(08:00, clear), gv-night-off/-on (23:00, fog 0.15, torch and lamps), the player walking slowly south on the bench route.
The source is the game's own presented frames (pzopt.FrameCapture, `--prop devCapture=14,9,30,100,crop=x:y:1918:1400,ram`:
a native 1918x1400 crop of the 5120x2160 picture, nothing resampled). Both runs of a pair are put on one 30 fps timeline
measured from their route start (pzopt-schedule.out), each run first becomes a lossless FFV1 pane (<run>/pane.mkv), SDR
mapped to PQ at 203 nits in the composition (as encode-av1-hdr.sh does). Layout per docs/media-style.md (as
harness/stitch-darkness.py): 3840x1800, 60 fps, header band, two 1918x1400 panes, caption strip; AV1 10-bit PQ / BT.2020,
the poster .jpg the only tone-mapped derivative.

Usage: harness/stitch-godrays.py <out.mp4>     (a queued media job: harness/queue.sh submit media ...)
"""
import glob
import os
import subprocess
import sys

import importlib.util

import numpy as np

_spec = importlib.util.spec_from_file_location('st', os.path.join(os.path.dirname(__file__), 'showcase-times.py'))
st = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(st)

OUT = sys.argv[1] if len(sys.argv) > 1 else 'docs/media/god-rays-off-vs-on.mp4'
SEG = 8.5  # seconds per scene (at most; the captures cover 9 s)
CARD = 7.0
W, H = 3840, 1800
HEAD = 150
PW, PH = 1918, 1400  # pane size (the native capture crop); divider 4 px
CAP_Y = HEAD + PH

FONT_B = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
FONT_R = '/usr/share/fonts/noto/NotoSans-Regular.ttf'
FONT_M = '/usr/share/fonts/noto/NotoSansMono-Regular.ttf'
WHITE, GREY, AMBER, GREEN = '0xB4B4B8', '0x8A8A90', '0xB88A40', '0x5CB878'

SCENES = [
    ('gv-room', 'SUNLIT ROOM, 17:00', 'light volumes through every sunlit window: shafts in the room\'s dust, lit patches on the floor, dust motes',
     'Each window the sun comes through is an exact prism down to the floor or the first wall; what stands outside (trees, other buildings) dims it.'),
    ('gv-fog', 'MORNING FOG, 08:00', 'the fog\'s air in shadow darkens: shafts of light and shade through the fog',
     'A volume of what the low sun reaches past walls, window frames, roofs and tree crowns, applied to the game\'s own fog buffer.'),
    ('gv-clear', 'CLEAR MORNING, 08:00', 'sunbeams through the windows and a light morning haze',
     'Outdoors the air in shadow is a little darker than the lit air, from each pixel\'s own depth in the chunk composite.'),
    ('gv-night', 'NIGHT, 23:00, LIGHT MIST', 'the torch and the street lamps glow in the misty air',
     'Single scattering of each light along each pixel\'s view ray in closed form (Sun et al. 2005), clipped to its cone and its level.'),
]


def run_dir(label):
    runs = sorted(glob.glob(f'harness/runs/{label}-2*'))
    if not runs:
        raise SystemExit(f'no run {label}')
    return runs[-1]


def capture(d):
    """(memmap of the RGBA frames, bottom-up rows; epoch ms per frame; w; h; route start epoch ms)."""
    c = os.path.join(d, 'capture')
    lines = open(os.path.join(c, 'index.txt')).read().split('\n')
    head = dict(kv.split('=') for kv in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(c, 'frames.rgba'), dtype=np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    route = int(st.kv(os.path.join(d, 'pzopt-schedule.out'))['route_start_epoch_ms'])
    return raw[:n * w * h * 4].reshape(n, h, w, 4), stamps[:n], w, h, route


def pane(d, t0, n, fps=30):
    """<run>/pane.mkv: n frames at fps from t0 s after the route start (nearest captured frame), cropped, lossless."""
    frames, stamps, w, h, route = capture(d)
    x, y, cw, ch = 0, 0, w, h  # (the capture is the native crop)
    rel = (stamps - route) / 1000.0
    out = os.path.join(d, 'pane.mkv')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{cw}x{ch}',
                           '-r', str(fps), '-i', '-', '-vf', f'scale={PW}:{PH}:flags=lanczos', '-c:v', 'ffv1', '-pix_fmt', 'gbrp', out],
                          stdin=subprocess.PIPE)
    for i in range(n):
        j = int(np.argmin(np.abs(rel - (t0 + i / fps))))
        f = frames[j, h - y - ch:h - y, x:x + cw, :3][::-1]  # rows are bottom-up
        ff.stdin.write(np.ascontiguousarray(f).tobytes())
    ff.stdin.close()
    if ff.wait() != 0:
        raise SystemExit(f'{d}: pane encode failed')
    return out


def window(a, b):
    """The route-relative span both captures cover."""
    ra, rb = capture(a), capture(b)
    lo = max((ra[1][0] - ra[4]) / 1000.0, (rb[1][0] - rb[4]) / 1000.0) + 0.1
    hi = min((ra[1][-1] - ra[4]) / 1000.0, (rb[1][-1] - rb[4]) / 1000.0) - 0.1
    return lo, hi


def esc(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace("'", '’')  # expansion=none: % is literal


def text(t, x, y, size, color, font=FONT_R):
    return f"drawtext=fontfile={font}:expansion=none:text='{esc(t)}':fontsize={size}:fontcolor={color}:x={x}:y={y}"


inputs, chains, segs = [], [], []
seg_len = []
for i, (label, title, what, line) in enumerate(SCENES):
    a, b = run_dir(label + '-off'), run_dir(label + '-on')
    lo, hi = window(a, b)
    n = int(min(SEG, hi - lo) * 30)
    if n < 90:
        raise SystemExit(f'{label}: the captures overlap only {hi - lo:.1f} s')
    pa, pb = pane(a, lo, n), pane(b, lo, n)
    seg_len.append(n / 30.0)
    print(f'{label}: {a} + {b}, route +{lo:.2f} s, {n} frames')
    ia, ib = len(inputs) // 2, len(inputs) // 2 + 1
    inputs += ['-i', pa, '-i', pb]
    sdr2pq = ('fps=60,scale=out_color_matrix=bt709:out_range=tv,format=yuv444p10le,'  # encode-av1-hdr.sh's SDR -> PQ mapping
              'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
              'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setsar=1')
    chains.append(f'[{ia}:v]{sdr2pq}[a{i}]')
    chains.append(f'[{ib}:v]{sdr2pq}[b{i}]')
    chains.append(
        f'color=c=0x060608:s={W}x{H}:r=60:d={n / 30.0},format=yuv420p10le[bg{i}];'
        f'[bg{i}][a{i}]overlay=0:{HEAD}:shortest=1[s{i}a];[s{i}a][b{i}]overlay={PW + 4}:{HEAD}[s{i}b];'
        f'[s{i}b]drawbox=x={PW}:y={HEAD - 60}:w=4:h={PH + 60}:color=0x202026:t=fill,'
        + text(f'{title}', '(w-tw)/2', 22, 60, WHITE, FONT_B) + ','
        + text('GOD RAYS OFF  ·  STOCK LOOK', f'({PW}-tw)/2', HEAD - 56, 42, AMBER, FONT_B) + ','
        + text('GOD RAYS ON', f'{PW + 4}+({PW}-tw)/2', HEAD - 56, 42, GREEN, FONT_B) + ','
        + text(what, '(w-tw)/2', CAP_Y + 40, 44, WHITE) + ','
        + text(line, '(w-tw)/2', CAP_Y + 108, 36, GREY) + ','
        + text('Project Zomboid B42.20  ·  Rosewood, zoom 1, the player walking slowly  ·  the same save, spot and hour on both sides  ·  the game\'s own frames, native 5K crops, RTX 4090',
               '(w-tw)/2', H - 58, 30, GREY)
        + f',fade=t=in:st=0:d=0.3,fade=t=out:st={n / 30.0 - 0.3}:d=0.3[seg{i}]')
    segs.append(f'[seg{i}]')

# cost card (numbers: docs/findings-god-rays-2026-09-27.md, runs gr-final-*: every pass on / off each second of one run)
rows = [
    ('Morning fog', '56.5 µs', '11.9 µs', '0.3 %'),
    ('Clear morning', '74.0 µs', '13.8 µs', '0.3 %'),
    ('Sunlit room, 12 windows', '36 µs', '12.3 µs', '0.3 %'),
    ('Night, torch and street lamps', '29.5 µs', '9.7 µs', '0.2 %'),
    ('Noon, clear', '', '6.8 µs', '0.2 %'),
]
card = [f'color=c=0x060608:s={W}x{H}:r=60:d={CARD},format=yuv420p10le,'
        + text('WHAT IT COSTS', '(w-tw)/2', 150, 64, WHITE, FONT_B) + ','
        + text('Frame time added at 5120x2160 on an RTX 4090, uncapped: every god ray pass switched on and off each second of the same run', '(w-tw)/2', 250, 34, GREY)]
cx = [520, 2080, 2560, 3040]
card.append(text('FIRST VERSION', cx[1], 380, 34, GREY, FONT_B))
card.append(text('NOW', cx[2], 380, 34, GREY, FONT_B))
card.append(text('OF A 240 FPS FRAME', cx[3], 380, 34, GREY, FONT_B))
for r, (name, s, n, ch) in enumerate(rows):
    yy = 470 + r * 110
    card.append(text(name, cx[0], yy, 46, WHITE))
    if s:
        card.append(text(s, cx[1], yy, 46, AMBER, FONT_M))
    card.append(text(n, cx[2], yy, 46, GREEN, FONT_M))
    card.append(text(ch, cx[3], yy, 46, GREY, FONT_M))
card.append(f'drawbox=x={cx[0]}:y=440:w={W - 2 * cx[0] + 200}:h=2:color=0x505058:t=fill')
card.append(text('The effects ride passes the game already runs (the chunk composite, the fog buffer), light volumes only for rooms on screen,',
                 '(w-tw)/2', 1110, 36, GREY))
card.append(text('volumes recomputed only when the sun, the camera or the world changes: no pass of their own for the haze.',
                 '(w-tw)/2', 1160, 36, GREY))
card.append(text('Off by default: Options > Enhancements > God rays. Runs gr-final-* (harness/godrays/abframes.py: paired on / off seconds, mean of the differences).',
                 '(w-tw)/2', H - 90, 30, GREY))
chains.append(','.join(card) + f',fade=t=in:st=0:d=0.3[seg{len(SCENES)}]')
segs.append(f'[seg{len(SCENES)}]')

fc = ';'.join(chains) + ';' + ''.join(segs) + f'concat=n={len(segs)}:v=1:a=0,' \
     + 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]'
os.makedirs(os.path.dirname(OUT) or '.', exist_ok=True)
cmd = ['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + inputs + [
    '-filter_complex', fc, '-map', '[v]',
    '-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '22', '-b:v', '0', '-maxrate', '120M', '-bufsize', '240M',
    '-pix_fmt', 'p010le', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
    '-movflags', '+faststart+write_colr', '-r', '60', OUT]
subprocess.run(cmd, check=True)
poster = OUT[:-4] + '.jpg'
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', '4', '-i', OUT, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=yuv420p', '-q:v', '2', poster], check=False)
print(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height,codec_name,color_transfer',
                      '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout.strip())

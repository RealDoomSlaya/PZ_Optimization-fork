#!/usr/bin/env python3
"""The all-enhancements showcase (2026-09-27): five scenes, the stock look (left: every Enhancements-tab option and god
rays off) beside everything on (right: sprite filter, ambient occlusion, sun / moon / cloud shadows, reflections,
per-pixel lighting, darkness floor, remembered places, colour grading, god rays), then a card naming what is on.

Pairs (harness/runs/<label>-*): ge-room / ge-fog / ge-storm / ge-clear / ge-night, -off and -on, both with an empty
options file (-Dpzopt.userOptionsFile: the desktop's own tab settings stay out), upscaler off, HDR output off (SDR
frames). Frames, timeline, panes, layout and the HDR chain as harness/stitch-godrays.py.

Usage: harness/stitch-enhancements.py <out.mp4>     (a queued media job: harness/queue.sh submit media ...)
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

OUT = sys.argv[1] if len(sys.argv) > 1 else 'docs/media/all-enhancements-stock-vs-on.mp4'
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
    ('ge-room', 'SUNLIT ROOM, 17:00', 'god rays through the windows  ·  soft sun shadows  ·  ambient occlusion  ·  per-pixel light  ·  golden-hour grade',
     'The low sun comes through every window as a shaft in the room\'s dust; furniture and walls cast soft shadows that follow the real sun.'),
    ('ge-fog', 'MORNING FOG, 08:00', 'shafts of light and shade through the fog  ·  soft shadows  ·  ambient occlusion  ·  misty-morning grade',
     'The fog\'s air in the shadow of the church and the trees darkens; corners and wall bases get their contact shading.'),
    ('ge-storm', 'STORM, 14:00', 'reflections in the puddles  ·  cloud shadows  ·  storm grade  ·  per-pixel light',
     'The wet street mirrors what stands on it; the storm\'s look is cooler and greyer, as the weather is.'),
    ('ge-clear', 'CLEAR MORNING, 08:00', 'sunbeams through the windows  ·  sun and cloud shadows  ·  ambient occlusion  ·  sharp sprite filtering',
     'Trees, fences and walls cast soft shadows from where the sun stands for the date and hour; drifting clouds dim the ground.'),
    ('ge-night', 'NIGHT, 23:00, LIGHT MIST', 'torch and lamp glow in the mist  ·  per-pixel torch beam  ·  moon shadows  ·  darkness floor  ·  night grade',
     'Places you have seen keep a dim moonlit floor instead of black; the torch is drawn per pixel and glows in the misty air.'),
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
        + text('STOCK LOOK', f'({PW}-tw)/2', HEAD - 56, 42, AMBER, FONT_B) + ','
        + text('ALL ENHANCEMENTS + GOD RAYS', f'{PW + 4}+({PW}-tw)/2', HEAD - 56, 42, GREEN, FONT_B) + ','
        + text(what, '(w-tw)/2', CAP_Y + 40, 44, WHITE) + ','
        + text(line, '(w-tw)/2', CAP_Y + 108, 36, GREY) + ','
        + text('Project Zomboid B42.20  ·  Rosewood, zoom 1, the player walking slowly  ·  the same save, spot and hour on both sides  ·  the game\'s own frames, native 5K crops, RTX 4090',
               '(w-tw)/2', H - 58, 30, GREY)
        + f',fade=t=in:st=0:d=0.3,fade=t=out:st={n / 30.0 - 0.3}:d=0.3[seg{i}]')
    segs.append(f'[seg{i}]')

# closing card: what is on (no numbers here: each option's cost is in its own findings doc)
rows = [
    ('Sprite filtering', 'sharp world art when zoomed in or out'),
    ('Ambient occlusion', 'soft shading in corners, wall bases, under furniture'),
    ('Sun, moon and cloud shadows', 'soft shadows that follow the real sky; drifting cloud shadows'),
    ('Reflections', 'rivers, lakes and puddles mirror the scene'),
    ('Per-pixel lighting', 'smooth light, torch and headlight beams per pixel'),
    ('Darkness, remembered places, grading', 'a moonlit floor at night, memory tint, looks per hour and weather'),
    ('God rays', 'shafts through windows, doorways, trees and fog; lights glowing in mist'),
]
card = [f'color=c=0x060608:s={W}x{H}:r=60:d={CARD},format=yuv420p10le,'
        + text('WHAT IS ON THE RIGHT', '(w-tw)/2', 150, 64, WHITE, FONT_B) + ','
        + text('Options > Enhancements, every section on (upscaling and HDR output left off: these frames are SDR at native resolution)', '(w-tw)/2', 250, 34, GREY)]
cx = [620, 1700]
for r, (name, what) in enumerate(rows):
    yy = 400 + r * 100
    card.append(text(name, cx[0], yy, 46, GREEN, FONT_B))
    card.append(text(what, cx[1], yy + 4, 40, WHITE))
card.append(f'drawbox=x={cx[0]}:y=375:w={W - 2 * cx[0]}:h=2:color=0x505058:t=fill')
card.append(text('All off by default, each its own switch. Costs: docs/findings-*.md (god rays 0.2-0.3 % of a 240 fps frame on this machine).',
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

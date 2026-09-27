#!/usr/bin/env python3
"""Runs and videos to the cold-storage bucket (2026-09-27, the disk was full): gs://diegov-videos-coldline (Coldline,
public read: anyone can play a linked video), objects at the path the file has under the home directory of the main checkout
(home/diegov/Documents/ZedProjects/PZ_Optimization/<path in the checkout>; a worktree's files go to the main checkout's
path: run folder names are unique). Every run video uploaded is linked in the Grafana DBs (local and remote, table
run_videos: the Runs table's "video" column and the Run dashboard's video panel).

    harness/cold-store.py runs <runs dir>...        whole run folders (gcloud storage rsync, one per run), except the raw
                                                    dev dumps (*.f16, *.f32, *.bin, capture/frames.rgba|gray): they only
                                                    fed metrics and videos that exist elsewhere
    harness/cold-store.py videos [--delete] [--min-age-h H] <dir>...
                                                    the video files under the dirs (*.mp4, *.mkv, *.webm, *.mov); --delete
                                                    removes each local copy once the bucket holds it at the same size
                                                    (git-tracked files are never deleted); --min-age-h skips files newer
    harness/cold-store.py link <run> <object>       record one run's video by hand

The bucket is public (allUsers read, since 2026-09-27; personal videos moved to the private
gs://diegov-videos-coldline-private): the links play for everyone, and every read is billed (Coldline retrieval + egress). Progress and every upload go to ~/.cache/pzopt-cold-store/log.txt;
finished run folders to ~/.cache/pzopt-cold-store/done-runs.txt (a rerun skips them).
"""
import argparse
import concurrent.futures as cf
import os
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

BUCKET = "diegov-videos-coldline"
MAIN = Path.home() / "Documents/ZedProjects/PZ_Optimization"
PREFIX = "home/diegov/Documents/ZedProjects/PZ_Optimization"
GCLOUD = os.environ.get("GCLOUD", str(Path.home() / "Downloads/google-cloud-sdk/bin/gcloud"))
STATE = Path.home() / ".cache/pzopt-cold-store"
VIDEO_EXT = (".mp4", ".mkv", ".webm", ".mov")
RAW = r".*\.(f16|f32|bin)$|(^|.*/)frames\.(rgba|gray)$"


def log(msg):
    STATE.mkdir(parents=True, exist_ok=True)
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    print(line, flush=True)
    with open(STATE / "log.txt", "a") as f:
        f.write(line + "\n")


def tree_root(p):
    """The checkout a path belongs to (the directory holding harness/)."""
    p = Path(p).resolve()
    for d in [p] + list(p.parents):
        if (d / "harness").is_dir() and (d / "src").is_dir():
            return d
    raise SystemExit(f"{p}: not inside a checkout")


def object_of(path):
    path = Path(path).resolve()
    return f"{PREFIX}/{path.relative_to(tree_root(path)).as_posix()}"


def url_of(obj):
    return f"https://storage.googleapis.com/{BUCKET}/{urllib.parse.quote(obj)}"


def listing(prefix):
    """object -> size under a prefix."""
    out = subprocess.run([GCLOUD, "storage", "ls", "-l", "-r", f"gs://{BUCKET}/{prefix}/**"], capture_output=True, text=True)
    sizes = {}
    for line in out.stdout.splitlines():
        f = line.split(None, 2)
        if len(f) == 3 and f[0].isdigit() and f[2].startswith("gs://"):
            sizes[f[2][len(f"gs://{BUCKET}/"):]] = int(f[0])
    return sizes


# ---- the Grafana DBs ----

def psql(sql, remote):
    env = dict(os.environ, PGHOST="127.0.0.1", PGPORT="5433", PGUSER="pzopt", PGPASSWORD="pzopt", PGDATABASE="pzopt")
    if remote:
        cfg = {}
        for line in (Path.home() / ".config/pzopt/grafana-remote.env").read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                cfg[k.strip().removeprefix("export ").strip()] = v.strip().strip('"').strip("'")
        env.update(PGHOST=cfg.get("PZ_REMOTE_PGHOST", "127.0.0.1"), PGPORT=cfg.get("PZ_REMOTE_PGPORT", "15433"),
                   PGUSER=cfg.get("PZ_REMOTE_PGUSER", "pzopt"), PGPASSWORD=cfg.get("PZ_REMOTE_PGPASSWORD", ""),
                   PGDATABASE=cfg.get("PZ_REMOTE_PGDATABASE", "pzopt"), PGSSLMODE="disable")
    r = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-c", sql], env=env, capture_output=True, text=True)
    if r.returncode != 0:
        log(f"  db ({'remote' if remote else 'local'}): {r.stderr.strip()[:300]}")
    return r.returncode == 0


def record(rows):
    """rows: (run, object, bytes); one per run (its recording.mp4, else its largest video)."""
    if not rows:
        return
    vals = ",".join("('%s','%s','%s',%d)" % (r.replace("'", "''"), url_of(o).replace("'", "''"), o.replace("'", "''"), b) for r, o, b in rows)
    sql = ("CREATE TABLE IF NOT EXISTS run_videos (run text PRIMARY KEY, url text, object text, bytes bigint, uploaded timestamptz DEFAULT now());"
           f"INSERT INTO run_videos (run, url, object, bytes) VALUES {vals} ON CONFLICT (run) DO UPDATE SET url = EXCLUDED.url, "
           "object = EXCLUDED.object, bytes = EXCLUDED.bytes, uploaded = now();")
    for remote in (False, True):
        psql(sql, remote)


def primary_videos(files):
    """run folder -> its video (recording.mp4, else the largest)."""
    by = {}
    for f in files:
        run = f.parent
        if f.name == "recording.mp4" or run not in by or (by[run].name != "recording.mp4" and f.stat().st_size > by[run].stat().st_size):
            by[run] = f
    return by


def is_run_dir(d):
    return d.parent.name in ("runs",) or "archive" in d.parts


# ---- modes ----

def do_runs(dirs):
    STATE.mkdir(parents=True, exist_ok=True)
    done = set((STATE / "done-runs.txt").read_text().split()) if (STATE / "done-runs.txt").exists() else set()
    for root in dirs:
        root = Path(root).resolve()
        runs = [root] if (root / "console.txt").exists() else sorted(p for p in root.iterdir() if p.is_dir())  # (one run folder, or a runs dir)
        log(f"runs: {root} ({len(runs)} folders, {sum(1 for r in runs if str(r) in done)} done before)")
        for i, run in enumerate(runs):
            if str(run) in done:
                continue
            dst = f"gs://{BUCKET}/{object_of(run)}"
            r = subprocess.run([GCLOUD, "storage", "rsync", "-r", "-x", RAW, str(run), dst], capture_output=True, text=True)
            if r.returncode != 0:
                log(f"  FAILED {run.name}: {r.stderr.strip()[-300:]}")
                continue
            vids = [f for f in run.rglob("*") if f.is_file() and f.suffix.lower() in VIDEO_EXT]
            if vids:
                v = run / "recording.mp4" if (run / "recording.mp4").is_file() else max(vids, key=lambda f: f.stat().st_size)
                record([(run.name, object_of(v), v.stat().st_size)])
            with open(STATE / "done-runs.txt", "a") as f:
                f.write(f"{run}\n")
            done.add(str(run))
            if i % 20 == 0:
                log(f"  {i + 1}/{len(runs)} {run.name}")
        log(f"runs: {root} finished")


def git_tracked(path):
    root = tree_root(path)
    return subprocess.run(["git", "-C", str(root), "ls-files", "--error-unmatch", str(Path(path).resolve().relative_to(root))],
                          capture_output=True).returncode == 0


def do_videos(dirs, delete, min_age_h):
    now = time.time()
    files = []
    for d in dirs:
        for f in Path(d).resolve().rglob("*"):
            if f.is_file() and f.suffix.lower() in VIDEO_EXT and now - f.stat().st_mtime >= min_age_h * 3600:
                files.append(f)
    log(f"videos: {len(files)} files, {sum(f.stat().st_size for f in files) / 1e9:.1f} GB under {', '.join(map(str, dirs))}")
    have = listing(PREFIX)

    def up(f):
        o = object_of(f)
        if have.get(o) != f.stat().st_size:
            r = subprocess.run([GCLOUD, "storage", "cp", str(f), f"gs://{BUCKET}/{o}"], capture_output=True, text=True)
            if r.returncode != 0:
                return f, o, False, r.stderr.strip()[-200:]
        return f, o, True, ""

    ok = []
    with cf.ThreadPoolExecutor(4) as ex:
        for n, (f, o, good, err) in enumerate(ex.map(up, files), 1):
            if not good:
                log(f"  FAILED {f}: {err}")
                continue
            ok.append((f, o))
            if n % 25 == 0:
                log(f"  {n}/{len(files)} uploaded")
    have = listing(PREFIX)  # the bucket's word, not the upload's exit code
    rows, freed = [], 0
    runs = primary_videos([f for f, _ in ok if is_run_dir(f.parent)])
    for f, o in ok:
        size = f.stat().st_size
        if have.get(o) != size:
            log(f"  NOT IN BUCKET at its size, kept: {f}")
            continue
        if runs.get(f.parent) == f:
            rows.append((f.parent.name, o, size))
        if delete and not git_tracked(f):
            f.unlink()
            freed += size
    record(rows)
    log(f"videos: {len(ok)} in the bucket, {len(rows)} runs linked, {freed / 1e9:.1f} GB freed")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="mode", required=True)
    a = sub.add_parser("runs")
    a.add_argument("dirs", nargs="+")
    b = sub.add_parser("videos")
    b.add_argument("--delete", action="store_true")
    b.add_argument("--min-age-h", type=float, default=0.0)
    b.add_argument("dirs", nargs="+")
    c = sub.add_parser("link")
    c.add_argument("run")
    c.add_argument("object")
    args = ap.parse_args()
    if args.mode == "runs":
        do_runs(args.dirs)
    elif args.mode == "videos":
        do_videos(args.dirs, args.delete, args.min_age_h)
    else:
        record([(args.run, args.object, 0)])


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Jev directs the explore=lights scene (2026-09-27, light sources made floating orbs of light, two over the car).

The game (pzopt.LightWalk, flags explore=lights director=jev) writes the scene's facts every 0.3 s to
~/Zomboid/pzopt-lights-state.json; this loop asks TypeSafe's Jev what the character does next and writes "<seq> <action>"
to ~/Zomboid/pzopt-lights-cmd.txt, which the game carries out until the next command. Jev only chooses; the walking is the
game's own movement keys along a grid path. Every decision goes to the log with Jev's probabilities and latency.

    harness/lights/light-director.py [--log /tmp/light-director.log] [--wait 900]

Desktop only (the TypeSafe key lives here). Start it before the run; it exits when the state goes stale for 15 s.
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from typesafe_client import ask, choice  # noqa: E402

ZOMBOID = Path(os.environ.get("ZOMBOID", Path.home() / "Zomboid"))

QUESTION = choice(
    "You direct a character in Project Zomboid who inspects how light sources (the character's car with its headlights, "
    "then slightt lamps) look, one decision at a time, from the scene state. For every light, in this order: walk to it "
    "(next_light) until reached is true; then walk once round it (circle_light) until circled_once is true; then stand "
    "still and watch it (watch) until seconds_watched reaches seconds_to_watch; then go to the next light. When "
    "left_to_visit is 0 and the current light is finished, finish. Pick the character's next action.",
    {
        "next_light": "Walk to the next light. Right when left_to_visit is above 0 and either no light is chosen, or the current "
                     "light is not reached yet, or the current light is finished (circled_once true and seconds_watched >= "
                     "seconds_to_watch). Never when left_to_visit is 0 and no light is chosen.",
        "circle_light": "Walk once round the current light. Right when the current light is reached and circled_once is false.",
        "watch": "Stand still and watch the current light. Right when the current light is reached, circled_once is true "
                 "and seconds_watched is below seconds_to_watch.",
        "hold": "Stand still. Right only while nothing else applies.",
        "done": "Finish. Right when left_to_visit is 0 and either the current light is finished (circled and watched) or "
                "no light is chosen (chosen false): nothing is left to walk to.",
    },
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="/tmp/light-director.log")
    ap.add_argument("--wait", type=float, default=900, help="seconds to wait for the game's first state")
    a = ap.parse_args()
    state, cmd, t0 = ZOMBOID / "pzopt-lights-state.json", ZOMBOID / "pzopt-lights-cmd.txt", time.time()
    seq, last_t, decisions, st = 0, None, 0, None
    log = open(a.log, "a", buffering=1)
    log.write(f"# light director started {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
    while True:
        try:
            if state.exists() and state.stat().st_mtime > t0:
                st = json.loads(state.read_text())
        except (OSError, json.JSONDecodeError):
            pass
        now = time.time()
        if st is None:
            if now - t0 > a.wait:
                log.write("# no state from the game, giving up\n")
                return 1
            time.sleep(0.1)
            continue
        if now * 1000 - st["t"] > 15000:
            log.write(f"# state stale for 15 s: the run ended; {decisions} decisions\n")
            return 0
        if st["t"] == last_t:
            time.sleep(0.05)
            continue
        last_t = st["t"]
        lat = []
        try:
            ans = ask(st, {"action": QUESTION}, log=lat)["action"]
        except Exception as e:  # keep the last command; the next state gets another try
            log.write(f"{st['seconds_since_start']:6.1f}s  jev error: {e}\n")
            time.sleep(0.5)
            continue
        act = ans["choice"]
        seq += 1
        decisions += 1
        tmp = cmd.with_suffix(".tmp")
        tmp.write_text(f"{seq} {act}\n")
        tmp.replace(cmd)
        probs = " ".join(f"{k}={v:.2f}" for k, v in sorted(ans.get("probabilities", {}).items(), key=lambda kv: -kv[1]))
        tr, c = st["lights"], st["current_light"]
        log.write(f"{st['seconds_since_start']:6.1f}s  {act:<12} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                  f"lights {tr['visited']}/{tr['to_visit_total']} left {tr['left_to_visit']} | chosen {c['chosen']} reached {c['reached']} "
                  f"dist {c['distance_tiles']:.1f} circled {c['circled_once']} ({c['degrees_circled']:.0f} deg) watched "
                  f"{c['seconds_watched']:.1f}/{c['seconds_to_watch']:.0f} | {probs}\n")


if __name__ == "__main__":
    sys.exit(main())

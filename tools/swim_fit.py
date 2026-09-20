"""Decode swim packet logs and fit bot swim vertical constants.

The terminal fall velocities are treated as hard packet observations:

    monitored-packets-swim.log          no-key terminal   = 140 px/s
    monitored-packets-swim-upheld.log   UP-held terminal  = 42 px/s
    monitored-packets-swim-downheld.log DOWN-held terminal= 210 px/s

The fitted linear model is only used for the approach curve:

    dv/dt = g - thrust - k*v       (vy positive = down)

Run from repo root:

    py tools\swim_fit.py
"""

import os
import re
import struct
from collections import Counter

from scipy.optimize import minimize_scalar


LOG_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "logs")

NORMAL_TYPES = {0, 5, 17}
JUMP_TYPES = {1, 2, 6, 12, 13, 16, 18, 19, 20}

TERMINAL_FREE = 140.0
TERMINAL_UP = 42.0
TERMINAL_DOWN = 210.0


def parse_line(hex_str):
    data = bytes(int(x, 16) for x in hex_str.split())
    offset = 4  # client tick

    def read_i16():
        nonlocal offset
        value = struct.unpack_from("<h", data, offset)[0]
        offset += 2
        return value

    def read_u8():
        nonlocal offset
        value = data[offset]
        offset += 1
        return value

    origin_x = read_i16()
    origin_y = read_i16()
    count = read_u8()
    segments = []

    for _ in range(count):
        move_type = read_u8()
        if move_type in NORMAL_TYPES:
            x = read_i16()
            y = read_i16()
            vx = read_i16()
            vy = read_i16()
            foothold = read_i16()
            stance = read_u8()
            duration_ms = read_i16()
            segments.append({
                "kind": "normal",
                "move_type": move_type,
                "x": x,
                "y": y,
                "vx": vx,
                "vy": vy,
                "foothold": foothold,
                "stance": stance,
                "duration_ms": duration_ms,
            })
        elif move_type in JUMP_TYPES:
            vx = read_i16()
            vy = read_i16()
            stance = read_u8()
            duration_ms = read_i16()
            segments.append({
                "kind": "jump",
                "move_type": move_type,
                "vx": vx,
                "vy": vy,
                "stance": stance,
                "duration_ms": duration_ms,
            })
        else:
            return None

    return origin_x, origin_y, segments


def load_log(name):
    path = os.path.join(LOG_DIR, name)
    rows = []
    if not os.path.exists(path):
        return rows

    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            match = re.search(r"41\(0x29\)-\d{2}\s+([0-9A-F\s]+)$", line.strip())
            if not match:
                continue
            row = parse_line(match.group(1))
            if row:
                rows.append(row)
    return rows


def terminal_velocities(rows):
    values = []
    for _, _, segments in rows:
        if len(segments) != 1 or segments[0]["kind"] != "normal":
            continue
        segment = segments[0]
        if segment["duration_ms"] >= 400:
            values.append(segment["vy"])
    return values


def modal_value(values):
    if not values:
        return None
    return Counter(values).most_common(1)[0][0]


def burst_arcs(rows, label):
    arcs = []
    for _, _, segments in rows:
        active = False
        vy0 = 0
        elapsed_s = 0.0
        for segment in segments:
            if segment["kind"] == "jump":
                active = True
                vy0 = segment["vy"]
                elapsed_s = 0.0
                continue
            if active and segment["kind"] == "normal":
                elapsed_s += segment["duration_ms"] / 1000.0
                arcs.append((label, vy0, elapsed_s, segment["vy"]))
    return arcs


def simulate(vy0, k, label, duration_s, dt=0.001):
    gravity = TERMINAL_FREE * k
    up_thrust = (TERMINAL_FREE - TERMINAL_UP) * k
    down_thrust = (TERMINAL_DOWN - TERMINAL_FREE) * k
    thrust = up_thrust if label == "up" else -down_thrust if label == "down" else 0.0

    vy = float(vy0)
    for _ in range(round(duration_s / dt)):
        vy += (gravity - thrust - k * vy) * dt
    return vy


def main():
    primary = {
        "free": load_log("monitored-packets-swim.log"),
        "up": load_log("monitored-packets-swim-upheld.log"),
        "down": load_log("monitored-packets-swim-downheld.log"),
    }

    print("=== Terminal packet velocities ===")
    for label, rows in primary.items():
        values = terminal_velocities(rows)
        unique = sorted(set(values))
        mode = modal_value(values)
        print(f"{label:5s}: mode={mode}, unique={unique} ({len(values)} samples)")

    fit_logs = [
        ("monitored-packets-swim.log", "free"),
        ("monitored-packets-swim-upheld.log", "up"),
        ("monitored-packets-swim-jump.log", "free"),
        ("monitored-packets-swim-jump-upheld.log", "up"),
        ("monitored-packets-swim-toleft.log", "free"),
        ("monitored-packets-swim-toleft-upheld.log", "up"),
    ]

    arcs = []
    for name, label in fit_logs:
        extracted = burst_arcs(load_log(name), label)
        arcs.extend(extracted)
        print(f"{name:38s} {len(extracted):2d} burst arcs")

    def error(k):
        return sum((simulate(vy0, k, label, duration_s) - observed) ** 2
                   for label, vy0, duration_s, observed in arcs)

    result = minimize_scalar(error, bounds=(0.1, 10.0), method="bounded")
    k = result.x
    gravity = TERMINAL_FREE * k
    up_thrust = (TERMINAL_FREE - TERMINAL_UP) * k
    down_thrust = (TERMINAL_DOWN - TERMINAL_FREE) * k

    print("\n=== Fitted constants ===")
    print(f"SWIM_GRAVITY_PXS2      = {gravity:.1f}")
    print(f"SWIM_FRICTION_HZ       = {k:.3f}")
    print(f"SWIM_UP_THRUST_PXS2    = {up_thrust:.1f}")
    print(f"SWIM_DOWN_THRUST_PXS2  = {down_thrust:.1f}")
    print(f"SWIM_FREE_MAX_SINK_PXS = {TERMINAL_FREE:.0f}")
    print(f"SWIM_UP_MAX_SINK_PXS   = {TERMINAL_UP:.0f}")
    print(f"SWIM_DOWN_MAX_SPEED_PXS= {TERMINAL_DOWN:.0f}")
    print(f"residual SS            = {result.fun:.1f}")

    print("\n=== Sample arc errors ===")
    for label, vy0, duration_s, observed in arcs[:12]:
        fitted = simulate(vy0, k, label, duration_s)
        print(f"{label:4s} vy0={vy0:5d} t={duration_s*1000:4.0f}ms "
              f"fit={fitted:7.1f} obs={observed:5d} err={fitted-observed:+6.1f}")


if __name__ == "__main__":
    main()

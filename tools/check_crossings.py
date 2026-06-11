"""Reports relationship lines that pass through boundary frames they don't connect into.

Usage: python tools/check_crossings.py <svg-file> [...]
"""
import re
import sys


def seg_hits_rect(a, b, rect, shrink=2.0):
    rx, ry, rw, rh = rect
    minx, miny = rx + shrink, ry + shrink
    maxx, maxy = rx + rw - shrink, ry + rh - shrink
    if minx >= maxx or miny >= maxy:
        return False
    dx, dy = b[0] - a[0], b[1] - a[1]
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, a[0] - minx), (dx, maxx - a[0]),
                 (-dy, a[1] - miny), (dy, maxy - a[1])):
        if abs(p) < 1e-12:
            if q < 0:
                return False
        else:
            t = q / p
            if p < 0:
                t0 = max(t0, t)
            else:
                t1 = min(t1, t)
            if t0 > t1:
                return False
    return t1 - t0 > 1e-6


def point_in_rect(p, rect, pad=6.0):
    rx, ry, rw, rh = rect
    return rx - pad <= p[0] <= rx + rw + pad and ry - pad <= p[1] <= ry + rh + pad


def check(path):
    svg = open(path, encoding="utf-8").read()

    frames = []
    for m in re.finditer(r'<rect x="(-?\d+)" y="(-?\d+)" width="(\d+)" height="(\d+)" rx="0" fill="none"', svg):
        frames.append(tuple(float(g) for g in m.groups()))

    problems = 0
    for m in re.finditer(r'<path d="(M [^"]+)" fill="none"[^>]*marker-end', svg):
        d = m.group(1)
        if " Q " in d or " C " in d:
            continue  # curves and self-loops are sampled elsewhere
        nums = [float(x) for x in re.findall(r"-?\d+\.?\d*", d)]
        pts = list(zip(nums[::2], nums[1::2]))
        for rect in frames:
            if point_in_rect(pts[0], rect) or point_in_rect(pts[-1], rect):
                continue  # legitimately connects inside this frame
            for i in range(len(pts) - 1):
                if seg_hits_rect(pts[i], pts[i + 1], rect):
                    print(f"  PIERCE frame {rect}: {d[:110]}")
                    problems += 1
                    break
    return problems


total = 0
for f in sys.argv[1:]:
    print(f)
    total += check(f)
print(f"total pierces: {total}")
sys.exit(1 if total else 0)

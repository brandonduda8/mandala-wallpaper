#!/usr/bin/env python3
"""Pixel-diff gate for Mandala Eclipse Live.

Compares two emulator screenshots taken 8s apart. Excludes the status bar
(top 96px) which the wallpaper never paints. Passes only if the wallpaper
region actually changed — proving the wallpaper is animating, not a static
frame.

Usage: pixeldiff.py shot_a.png shot_b.png
"""
import struct
import sys
import zlib


def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, w, h, bitd, ctype, idat = 8, None, None, None, None, b""
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype_c = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype_c == b"IHDR":
            w, h, bitd, ctype = struct.unpack(">IIBB", chunk[:10])
        elif ctype_c == b"IDAT":
            idat += chunk
        pos += 12 + length
    assert bitd == 8 and ctype in (2, 6), f"unsupported PNG type {ctype}/{bitd}"
    ch = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * ch
    out = bytearray(w * h * ch)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if filt == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 0xFF
        elif filt == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filt == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif filt == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        elif filt != 0:
            raise ValueError(f"bad filter {filt}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, ch, out


def mean_abs_diff(a, b, w, h, ch, y0):
    total, n = 0, 0
    for y in range(y0, h):
        base = y * w * ch
        for x in range(w * ch):
            total += abs(a[base + x] - b[base + x])
            n += 1
    return total / n if n else 0.0


def main():
    if len(sys.argv) != 3:
        print("usage: pixeldiff.py shot_a.png shot_b.png", file=sys.stderr)
        return 2
    wa, ha, cha, pa = read_png(sys.argv[1])
    wb, hb, chb, pb = read_png(sys.argv[2])
    assert (wa, ha, cha) == (wb, hb, chb), "screenshot size mismatch"
    status_bar = 96
    full = mean_abs_diff(pa, pb, wa, ha, cha, status_bar)
    # center crop: the eclipse core lives here; must move on its own
    cx0, cx1 = wa // 4, wa * 3 // 4
    cy0, cy1 = ha // 4, ha * 3 // 4
    total, n = 0, 0
    for y in range(cy0, cy1):
        base = y * wa * cha
        for x in range(cx0 * cha, cx1 * cha):
            total += abs(pa[base + x] - pb[base + x])
            n += 1
    center = total / n if n else 0.0
    print(f"screenshots: {wa}x{ha}")
    print(f"mean abs diff (below status bar): {full:.3f}")
    print(f"mean abs diff (center crop):      {center:.3f}")
    ok = full > 0.75 and center > 0.5
    print("MOTION PROVEN — gate PASS" if ok else "NO MOTION — gate FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

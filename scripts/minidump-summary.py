#!/usr/bin/env python3
"""Where an emulator crash happened, from its minidumps, without symbols.

For each .dmp under the given directories: the signal, the crashing thread's instruction
pointer as module+offset, the addresses on its stack that fall in a module (a rough
backtrace), and any abort or check-failure message in the dump.
"""
import os
import re
import struct
import sys

THREAD_LIST, MODULE_LIST, EXCEPTION = 3, 4, 6


def summarize(path):
    data = open(path, "rb").read()
    if data[:4] != b"MDMP":
        print(f"{path}: not a minidump")
        return
    count, directory = struct.unpack_from("<II", data, 8)
    streams = {}
    for i in range(count):
        kind, size, rva = struct.unpack_from("<III", data, directory + 12 * i)
        streams[kind] = (size, rva)

    modules = []
    if MODULE_LIST in streams:
        rva = streams[MODULE_LIST][1]
        for i in range(struct.unpack_from("<I", data, rva)[0]):
            base, size, _, _, name_rva = struct.unpack_from("<QIIII", data, rva + 4 + 108 * i)
            length = struct.unpack_from("<I", data, name_rva)[0]
            name = data[name_rva + 4:name_rva + 4 + length].decode("utf-16-le", "replace")
            modules.append((base, base + size, os.path.basename(name)))

    def where(address):
        for start, end, name in modules:
            if start <= address < end:
                return f"{name}+{address - start:#x}"
        return None

    print(f"== {path} ({len(data)} bytes, {len(modules)} modules)")
    if EXCEPTION not in streams:
        print("no exception stream")
        return
    rva = streams[EXCEPTION][1]
    thread_id, = struct.unpack_from("<I", data, rva)
    code, _, _, address = struct.unpack_from("<IIQQ", data, rva + 8)
    _, context = struct.unpack_from("<II", data, rva + 160)
    rip, = struct.unpack_from("<Q", data, context + 0xF8)
    rsp, = struct.unpack_from("<Q", data, context + 0x98)
    print(f"signal/code {code:#x} at {address:#x} in thread {thread_id}")
    print(f"ip {rip:#x} = {where(rip) or '(no module)'}")

    if THREAD_LIST in streams:
        rva = streams[THREAD_LIST][1]
        for i in range(struct.unpack_from("<I", data, rva)[0]):
            entry = rva + 4 + 48 * i
            tid, = struct.unpack_from("<I", data, entry)
            if tid != thread_id:
                continue
            start, size, mem = struct.unpack_from("<QII", data, entry + 24)
            frames = []
            for offset in range(max(0, rsp - start) & ~7, size - 7, 8):
                hit = where(struct.unpack_from("<Q", data, mem + offset)[0])
                if hit and (not frames or frames[-1] != hit):
                    frames.append(hit)
                if len(frames) == 30:
                    break
            print("stack: " + "\n       ".join(frames) if frames else "stack: nothing in a module")

    messages = set()
    for text in re.findall(rb"[\x20-\x7e]{12,}", data):
        if re.search(rb"(?i)fatal|check failed|assert|abort|panic|error", text):
            messages.add(text.decode()[:200])
    for text in sorted(messages)[:20]:
        print(f"message: {text}")


dumps = [os.path.join(root, name) for top in sys.argv[1:] for root, _, names in os.walk(top) for name in names if name.endswith(".dmp")]
if not dumps:
    print("No minidumps in " + ", ".join(sys.argv[1:]))
for dump in dumps:
    try:
        summarize(dump)
    except Exception as e:  # A damaged dump shouldn't hide the others.
        print(f"{dump}: {e}")

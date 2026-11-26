#!/usr/bin/env python3
"""
Builds the small update file between two APKs of the launcher.

The new APK is cut into the parts of its zip: every file inside (header and data), the signing block,
and the zip directory. A part that is byte for byte in the old APK too is only written down as
"copy these bytes of the old APK". Only the parts that changed or are new are stored in the update file.
The launcher puts the new APK together on the phone from the APK it has installed plus the update file,
so the signature of the new APK stays valid, the result is the very same file that the build made.

File format (all numbers big endian):
  "NKD1", u64 new size, 32 bytes new sha256, 32 bytes base sha256, u64 base size
  then operations:  0 + u64 offset + u64 length   copy from the base APK
                    1 + u64 length + bytes        bytes that are new
                    2                             end

Usage: make_delta.py OLD.apk NEW.apk OUT.delta [--meta meta.json] [--max-ratio 0.8]
"""
import argparse
import hashlib
import json
import struct
import sys

MAGIC = b"NKD1"
OP_COPY, OP_ADD, OP_END = 0, 1, 2


def sha256(data):
    return hashlib.sha256(data).digest()


class Apk:
    """The parts of an APK: [(name, header_start, data_start, end)], the start of the signing block"""

    def __init__(self, path):
        with open(path, "rb") as f:
            self.data = f.read()
        data = self.data
        eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 66000))
        if eocd < 0:
            raise ValueError("%s is not a zip file" % path)
        (_, _, _, _, count, cd_size, cd_offset, _) = struct.unpack_from("<IHHHHIIH", data, eocd)
        if cd_offset == 0xFFFFFFFF or count == 0xFFFF:
            raise ValueError("zip64 files are not supported")
        self.cd_offset = cd_offset

        # The signing block sits right before the zip directory
        self.sign_start = cd_offset
        if cd_offset >= 32 and data[cd_offset - 16:cd_offset] == b"APK Sig Block 42":
            (block_size,) = struct.unpack_from("<Q", data, cd_offset - 24)
            start = cd_offset - (block_size + 8)
            if 0 <= start < cd_offset:
                self.sign_start = start

        headers = []
        pos = cd_offset
        for _ in range(count):
            if data[pos:pos + 4] != b"PK\x01\x02":
                raise ValueError("broken zip directory")
            name_len, extra_len, comment_len = struct.unpack_from("<HHH", data, pos + 28)
            (local_offset,) = struct.unpack_from("<I", data, pos + 42)
            name = data[pos + 46:pos + 46 + name_len].decode("utf-8", "replace")
            headers.append((local_offset, name))
            pos += 46 + name_len + extra_len + comment_len
        headers.sort()

        self.parts = []
        for i, (local_offset, name) in enumerate(headers):
            end = headers[i + 1][0] if i + 1 < len(headers) else self.sign_start
            if data[local_offset:local_offset + 4] != b"PK\x03\x04":
                raise ValueError("broken local header of " + name)
            name_len, extra_len = struct.unpack_from("<HH", data, local_offset + 26)
            data_start = local_offset + 30 + name_len + extra_len
            if not (local_offset < data_start <= end):
                raise ValueError("broken entry " + name)
            self.parts.append((name, local_offset, data_start, end))
        self.first = self.parts[0][1] if self.parts else self.sign_start


def build_ops(old, new):
    """The operations that make `new` out of `old`, copies and new bytes in the order of the new file"""
    # Every body of the old APK by its hash, so a moved or renamed file is found too
    old_by_name = {}
    old_bodies = {}
    for name, header_start, data_start, end in old.parts:
        old_by_name[name] = (header_start, data_start, end)
        body = old.data[data_start:end]
        if len(body) >= 64:
            old_bodies.setdefault(sha256(body), (data_start, end - data_start))

    ops = []  # ["copy", offset, length] or ["add", bytearray]

    def copy(offset, length):
        if length <= 0:
            return
        if ops and ops[-1][0] == "copy" and ops[-1][1] + ops[-1][2] == offset:
            ops[-1][2] += length
        else:
            ops.append(["copy", offset, length])

    def add(chunk):
        if not chunk:
            return
        if ops and ops[-1][0] == "add":
            ops[-1][1] += chunk
        else:
            ops.append(["add", bytearray(chunk)])

    add(new.data[:new.first])
    for name, header_start, data_start, end in new.parts:
        header = new.data[header_start:data_start]
        body = new.data[data_start:end]
        same = old_by_name.get(name)

        if same and old.data[same[0]:same[1]] == header:
            copy(same[0], len(header))
        else:
            add(header)

        found = None
        if same and old.data[same[1]:same[2]] == body:
            found = (same[1], len(body))
        elif len(body) >= 64:
            hit = old_bodies.get(sha256(body))
            if hit and old.data[hit[0]:hit[0] + hit[1]] == body:
                found = hit
        if found:
            copy(found[0], found[1])
        else:
            add(body)
    add(new.data[new.sign_start:])
    return ops


def write_delta(path, old, new, ops):
    with open(path, "wb") as out:
        out.write(MAGIC)
        out.write(struct.pack(">Q", len(new.data)))
        out.write(sha256(new.data))
        out.write(sha256(old.data))
        out.write(struct.pack(">Q", len(old.data)))
        for op in ops:
            if op[0] == "copy":
                out.write(struct.pack(">BQQ", OP_COPY, op[1], op[2]))
            else:
                out.write(struct.pack(">BQ", OP_ADD, len(op[1])))
                out.write(op[1])
        out.write(bytes([OP_END]))


def apply_delta(delta_path, old_data):
    """The same work as the launcher does, used here to check the update file before it is published"""
    with open(delta_path, "rb") as f:
        blob = f.read()
    if blob[:4] != MAGIC:
        raise ValueError("bad magic")
    (new_size,) = struct.unpack_from(">Q", blob, 4)
    new_sha = blob[12:44]
    pos = 44 + 32 + 8
    out = bytearray()
    while True:
        op = blob[pos]
        pos += 1
        if op == OP_END:
            break
        if op == OP_COPY:
            offset, length = struct.unpack_from(">QQ", blob, pos)
            pos += 16
            out += old_data[offset:offset + length]
        elif op == OP_ADD:
            (length,) = struct.unpack_from(">Q", blob, pos)
            pos += 8
            out += blob[pos:pos + length]
            pos += length
        else:
            raise ValueError("bad operation")
    if len(out) != new_size or sha256(bytes(out)) != new_sha:
        raise ValueError("the rebuilt file is not the new apk")
    return bytes(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("old")
    parser.add_argument("new")
    parser.add_argument("out")
    parser.add_argument("--meta", help="writes the numbers the update API needs as json")
    parser.add_argument("--max-ratio", type=float, default=0.8,
                        help="the update file is not worth it when it is bigger than this part of the new apk")
    args = parser.parse_args()

    old, new = Apk(args.old), Apk(args.new)
    ops = build_ops(old, new)
    write_delta(args.out, old, new, ops)

    # The file is checked by building the new apk from it, a wrong update file is never published
    if apply_delta(args.out, old.data) != new.data:
        print("self check failed", file=sys.stderr)
        return 2

    with open(args.out, "rb") as f:
        delta_bytes = f.read()
    copied = sum(op[2] for op in ops if op[0] == "copy")
    meta = {
        "base_sha256": sha256(old.data).hex(),
        "base_size": len(old.data),
        "new_sha256": sha256(new.data).hex(),
        "new_size": len(new.data),
        "delta_sha256": sha256(delta_bytes).hex(),
        "delta_size": len(delta_bytes),
        "worth_it": len(delta_bytes) <= len(new.data) * args.max_ratio,
    }
    print("new apk %.1f MB, update file %.1f MB, %.1f MB is taken from the old apk" % (
        len(new.data) / 1048576, len(delta_bytes) / 1048576, copied / 1048576))
    if args.meta:
        with open(args.meta, "w") as f:
            json.dump(meta, f)
    return 0


if __name__ == "__main__":
    sys.exit(main())

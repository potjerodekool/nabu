#!/usr/bin/env python3
"""
pgo_merge.py - Make an indexed profile (.profdata, version 12) that the LLVM of the Nabu backend (21) can read.

The llvm-profdata of a newer LLVM writes indexed version 13, which LLVM 21 refuses ("unsupported instrumentation
profile format version"). It still reads the raw profiles of LLVM 21's instrumentation, and writes them as text; this
script turns that text into indexed version 12, with the edge counters and the indirect-call targets (value profiling,
for the promotion of hot virtual and interface calls).

Usage:
  python pgo_merge.py <input.profraw | input.proftext> <output.profdata> [--llvm-profdata PATH]

A .profraw is first converted with `llvm-profdata merge --text` (PATH, $LLVM_PROFDATA, or the default LLVM install).
"""

import hashlib
import os
import struct
import subprocess
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

INDEXED_MAGIC = 0x8169666f72706cff
INDEXED_VERSION = 12
VARIANT_MASK_IR_PROF = 1 << 56          # the IR-instrumentation flag in the Version field
IPVK_INDIRECT_CALL_TARGET = 0
IPVK_LAST = 2
VALUE_KIND_NAMES = {"IPVK_IndirectCallTarget": 0, "IPVK_MemOPSize": 1, "IPVK_VTableTarget": 2}

INDEXED_HDR_FMT = "<9Q"   # Magic, Version, Unused, HashType, HashOffset, MemProfOffset, BinaryIdOffset,
INDEXED_HDR_SIZE = struct.calcsize(INDEXED_HDR_FMT)  # TemporalProfTracesOffset, VTableNamesOffset

SUMMARY_N_FIELDS = 6
SUMMARY_CUTOFFS = [
    10000, 100000, 200000, 300000, 400000, 500000,
    600000, 700000, 800000, 900000, 950000, 990000,
    999000, 999900, 999990, 999999,
]


@dataclass
class FuncRecord:
    name: str
    func_hash: int
    counters: List[int]
    # value kind -> per site a list of (target name, count)
    values: Dict[int, List[List[Tuple[str, int]]]] = field(default_factory=dict)


def llvm_md5_hash(name: str) -> int:
    """IndexedInstrProf::ComputeHash: the low 8 bytes of the MD5 of the name, little endian."""
    return struct.unpack_from("<Q", hashlib.md5(name.encode("utf-8")).digest())[0]


# ---------------------------------------------------------------------------
# Text profile reader (the format of `llvm-profdata merge --text`)
# ---------------------------------------------------------------------------

def read_text(path: str) -> List[FuncRecord]:
    lines = [l.rstrip("\r\n") for l in open(path, encoding="utf-8")]
    # Comment lines start with '#'; ":ir" and friends are header flags.
    items = [l for l in lines if not l.startswith("#")]
    pos = 0
    while pos < len(items) and (items[pos].startswith(":") or not items[pos].strip()):
        pos += 1
    records: List[FuncRecord] = []

    def next_line() -> str:
        nonlocal pos
        while pos < len(items) and not items[pos].strip():
            pos += 1
        line = items[pos]
        pos += 1
        return line

    while True:
        while pos < len(items) and not items[pos].strip():
            pos += 1
        if pos >= len(items):
            break
        name = next_line()
        func_hash = int(next_line())
        num_counters = int(next_line())
        counters = [int(next_line()) for _ in range(num_counters)]
        record = FuncRecord(name, func_hash, counters)
        # Optional value data: "# Num Value Kinds:" is a comment, so the next non-comment line is the number of kinds
        # only if the record has value data; a record without it is followed by an empty line and the next name.
        if pos < len(items) and items[pos].strip() and _is_int(items[pos]):
            num_kinds = int(next_line())
            for _ in range(num_kinds):
                kind = int(next_line())
                num_sites = int(next_line())
                sites = []
                for _ in range(num_sites):
                    num_values = int(next_line())
                    site = []
                    for _ in range(num_values):
                        target, count = next_line().rsplit(":", 1)
                        site.append((target, int(count)))
                    sites.append(site)
                record.values[kind] = sites
        records.append(record)
    return records


def _is_int(text: str) -> bool:
    try:
        int(text)
        return True
    except ValueError:
        return False


# ---------------------------------------------------------------------------
# Indexed profile writer (version 12)
# ---------------------------------------------------------------------------

def _value_prof_data(record: FuncRecord) -> bytes:
    """ValueProfData::serializeFrom: TotalSize, NumValueKinds, then a ValueProfRecord per kind with sites."""
    body = b""
    kinds = 0
    for kind in range(IPVK_LAST + 1):
        sites = record.values.get(kind)
        if not sites:
            continue
        kinds += 1
        header = struct.pack("<II", kind, len(sites)) + bytes(min(len(s), 255) for s in sites)
        header += b"\0" * (-len(header) % 8)
        data = b"".join(struct.pack("<QQ", llvm_md5_hash(target) if kind == IPVK_INDIRECT_CALL_TARGET
                                    else int(target), count)
                        for site in sites for target, count in site[:255])
        body += header + data
    return struct.pack("<II", 8 + len(body), kinds) + body


def write_indexed(path: str, records: List[FuncRecord]) -> None:
    entries: List[Tuple[bytes, bytes, int]] = []
    for rec in records:
        data = struct.pack("<QQ", rec.func_hash, len(rec.counters))
        data += struct.pack(f"<{len(rec.counters)}Q", *rec.counters)
        data += struct.pack("<Q", 0)                  # NumBitmapBytes (no MC/DC)
        data += _value_prof_data(rec)
        entries.append((rec.name.encode("utf-8"), data, llvm_md5_hash(rec.name)))

    summary = _build_summary(records)
    # Sections the reader of version >= 9 (binary ids) and >= 12 (vtable names) always reads at their offset: empty.
    binary_ids = struct.pack("<Q", 0)
    vtable_names = struct.pack("<Q", 0)
    base = INDEXED_HDR_SIZE + len(summary)
    table, meta_offset = _build_hash_table(entries, file_base=base)
    hash_offset = base + meta_offset
    binary_id_offset = base + len(table)
    vtable_names_offset = binary_id_offset + len(binary_ids)

    hdr = struct.pack(INDEXED_HDR_FMT,
                      INDEXED_MAGIC,
                      INDEXED_VERSION | VARIANT_MASK_IR_PROF,
                      0,                    # Unused
                      0,                    # HashType = MD5
                      hash_offset,
                      0,                    # MemProfOffset (only read with the memprof flag)
                      binary_id_offset,
                      0,                    # TemporalProfTracesOffset (only read with the temporal flag)
                      vtable_names_offset)
    with open(path, "wb") as f:
        f.write(hdr)
        f.write(summary)
        f.write(table)
        f.write(binary_ids)
        f.write(vtable_names)

    sites = sum(len(s) for r in records for s in r.values.values())
    targets = sum(len(site) for r in records for s in r.values.values() for site in s)
    print(f"wrote {path}: {len(records)} functions, {sum(len(r.counters) for r in records)} counters, "
          f"{sites} value sites with {targets} targets", file=sys.stderr)


def _build_hash_table(entries: List[Tuple[bytes, bytes, int]], file_base: int) -> Tuple[bytes, int]:
    """
    LLVM's OnDiskChainedHashTable with offset_type uint64: buckets of (uint16 count, then per item uint64 hash,
    uint64 key length, uint64 data length, key, data), then the table: uint64 buckets, uint64 entries and the absolute
    offset of every bucket (0 = empty). Returns (payload, offset of the table within it).
    """
    n = len(entries)
    num_buckets = 1
    while num_buckets < (n * 4 // 3 + 1):
        num_buckets <<= 1
    buckets: List[List[Tuple[bytes, bytes, int]]] = [[] for _ in range(num_buckets)]
    for key, data, h in entries:
        buckets[h & (num_buckets - 1)].append((key, data, h))

    payload = b"\x00"                 # offset 0 means "empty bucket", so no bucket may start there
    bucket_offsets = [0] * num_buckets
    for i, bucket in enumerate(buckets):
        if not bucket:
            continue
        bucket_offsets[i] = file_base + len(payload)
        payload += struct.pack("<H", len(bucket))
        for key, data, h in bucket:
            payload += struct.pack("<QQQ", h, len(key), len(data)) + key + data
    payload += b"\x00" * (-len(payload) % 8)
    meta_offset = len(payload)
    payload += struct.pack("<QQ", num_buckets, n)
    payload += b"".join(struct.pack("<Q", off) for off in bucket_offsets)
    return payload, meta_offset


def _build_summary(records: List[FuncRecord]) -> bytes:
    """IndexedInstrProf::Summary: field count, cutoff count, the 6 fields, then (cutoff, min count, blocks)."""
    all_counts = [c for r in records for c in r.counters]
    entry_counts = [r.counters[0] for r in records if r.counters]
    internal = [c for r in records for c in r.counters[1:]]
    total_count = sum(all_counts)
    out = struct.pack("<QQ", SUMMARY_N_FIELDS, len(SUMMARY_CUTOFFS))
    out += struct.pack("<6Q", len(records), len(all_counts), max(entry_counts, default=0),
                       max(all_counts, default=0), max(internal, default=0), total_count)
    ordered = sorted(all_counts, reverse=True)
    for cutoff in SUMMARY_CUTOFFS:
        # the smallest count such that the counts at or above it make up `cutoff` per million of the total
        needed = total_count * cutoff / 1_000_000
        acc, num, min_count = 0, 0, 0
        for c in ordered:
            if acc >= needed and num > 0:
                break
            acc += c
            num += 1
            min_count = c
        out += struct.pack("<QQQ", cutoff, min_count if ordered else 0, num if ordered else 0)
    return out


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def to_text(raw_path: str, llvm_profdata: str) -> str:
    text_path = os.path.splitext(raw_path)[0] + ".proftext"
    subprocess.run([llvm_profdata, "merge", "--text", "-o", text_path, raw_path], check=True)
    return text_path


def main() -> None:
    args = sys.argv[1:]
    llvm_profdata = os.environ.get("LLVM_PROFDATA", r"C:\Program Files\LLVM\bin\llvm-profdata.exe"
                                   if os.name == "nt" else "llvm-profdata")
    if "--llvm-profdata" in args:
        i = args.index("--llvm-profdata")
        llvm_profdata = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    in_path, out_path = args
    if not in_path.endswith(".proftext"):
        in_path = to_text(in_path, llvm_profdata)
    records = read_text(in_path)
    if not records:
        print("warning: no records found; the profile may be empty", file=sys.stderr)
    write_indexed(out_path, records)


if __name__ == "__main__":
    main()

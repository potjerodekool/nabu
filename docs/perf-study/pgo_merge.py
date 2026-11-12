#!/usr/bin/env python3
"""
pgo_merge.py - Convert LLVM 21 raw profile (.profraw) to indexed (.profdata).

Reads a .profraw file produced by an LLVM 21 pgo-instr-gen binary (linked
with nabu_profile_rt.c) and writes an indexed .profdata file that
pgo-instr-use in LLVM 21 can read.

Supported:
  - Raw profile version 10 (LLVM 21)
  - Indexed profile version 12 (counter data only; no value profiling)
  - On-disk chained hash table (offset_type = uint64)

Usage:
  python3 pgo_merge.py <input.profraw> <output.profdata>
"""

import struct
import sys
import hashlib
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

RAW_MAGIC   = 0x8172666f72706cff  # "\xff\x6c\x70\x72\x6f\x66\x72\x81"
INDEXED_MAGIC = 0x8169666f72706cff

RAW_SUPPORTED_VERSION = 10
INDEXED_VERSION = 12
IR_PROF_BIT   = 1 << 32          # variant flag in indexed Version field

# Raw header: 16 × uint64 = 128 bytes
RAW_HEADER_FMT  = "<16Q"
RAW_HEADER_SIZE = struct.calcsize(RAW_HEADER_FMT)   # 128

# Profile data record: 64 bytes  (see nabu_profile_rt.c for field layout)
PROF_DATA_FMT  = "<QQqqQQIHHHHI"
PROF_DATA_SIZE = struct.calcsize(PROF_DATA_FMT)     # 64

# Indexed file header: 9 × uint64 = 72 bytes  (all v12 fields)
INDEXED_HDR_FMT  = "<9Q"
INDEXED_HDR_SIZE = struct.calcsize(INDEXED_HDR_FMT)  # 72

# Summary field count and standard cutoff percentages (×1 000 000)
SUMMARY_N_FIELDS  = 6
SUMMARY_CUTOFFS   = [
    10000, 100000, 200000, 300000, 400000, 500000,
    600000, 700000, 800000, 900000, 950000, 990000,
    999000, 999900, 999990, 999999,
]
SUMMARY_N_CUTOFFS = len(SUMMARY_CUTOFFS)   # 16


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass
class RawHeader:
    magic: int
    version: int
    binary_ids_size: int
    num_data: int
    padding_before_counters: int
    num_counters: int
    padding_after_counters: int
    num_bitmap_bytes: int
    padding_after_bitmap: int
    names_size: int
    counters_delta: int
    bitmap_delta: int
    names_delta: int
    num_vtables: int
    vnames_size: int
    value_kind_last: int


@dataclass
class FuncRecord:
    name: str               # PGO function name
    func_hash: int
    counters: List[int]     # counter values


# ---------------------------------------------------------------------------
# MD5 hash (LLVM uses the first 8 bytes of MD5, little-endian)
# ---------------------------------------------------------------------------

def llvm_md5_hash(name: str) -> int:
    digest = hashlib.md5(name.encode("utf-8")).digest()
    return struct.unpack_from("<Q", digest)[0]


# ---------------------------------------------------------------------------
# Raw profile reader
# ---------------------------------------------------------------------------

def _to_int64(v: int) -> int:
    """Reinterpret a uint64 as int64."""
    return v if v < (1 << 63) else v - (1 << 64)


def read_raw(path: str) -> List[FuncRecord]:
    with open(path, "rb") as f:
        raw = f.read()

    if len(raw) < RAW_HEADER_SIZE:
        raise ValueError(f"File too small ({len(raw)} bytes)")

    fields = struct.unpack_from(RAW_HEADER_FMT, raw, 0)
    hdr = RawHeader(*fields)

    if hdr.magic != RAW_MAGIC:
        raise ValueError(f"Bad magic: 0x{hdr.magic:016x}")

    raw_ver = hdr.version & 0xFFFFFFFF  # strip variant flags
    if raw_ver != RAW_SUPPORTED_VERSION:
        raise ValueError(f"Unsupported raw version {raw_ver} (expected {RAW_SUPPORTED_VERSION})")

    if hdr.num_data == 0:
        return []

    # --- File layout offsets ---
    data_off  = RAW_HEADER_SIZE + hdr.binary_ids_size
    cnts_off  = data_off + hdr.num_data * PROF_DATA_SIZE + hdr.padding_before_counters
    names_off = cnts_off + hdr.num_counters * 8 + hdr.padding_after_counters + \
                hdr.num_bitmap_bytes + hdr.padding_after_bitmap

    # --- Parse function names ---
    names_blob = raw[names_off : names_off + hdr.names_size]
    names = _parse_names(names_blob)       # {md5_hash: name_string}

    # --- Parse data records and counters ---
    records: List[FuncRecord] = []
    for idx in range(hdr.num_data):
        rec_off = data_off + idx * PROF_DATA_SIZE
        (name_ref, func_hash, counter_ptr_raw, bitmap_ptr_raw,
         func_ptr, values, num_counters,
         nvs0, nvs1, nvs2, _pad, num_bitmap_bytes
         ) = struct.unpack_from(PROF_DATA_FMT, raw, rec_off)

        # Signed relative counter pointer
        counter_ptr = _to_int64(counter_ptr_raw)

        # Offset within __llvm_prf_cnts section.
        # Derivation (see nabu_profile_rt.c comments):
        #   counters_delta = counters_mem_base - data_mem_base
        #   offset_in_cnts = counter_ptr + idx*PROF_DATA_SIZE + 16 - counters_delta
        # (16 = offsetof(counter_ptr) in LLVMProfData)
        counters_delta = _to_int64(hdr.counters_delta)
        offset_in_cnts = counter_ptr + idx * PROF_DATA_SIZE + 16 - counters_delta

        if offset_in_cnts < 0 or offset_in_cnts + num_counters * 8 > hdr.num_counters * 8:
            # Relative pointer out of range; skip this record.
            print(f"  [warn] record {idx}: counter offset {offset_in_cnts} out of range, skipping",
                  file=sys.stderr)
            continue

        ctr_off = cnts_off + offset_in_cnts
        ctrs = list(struct.unpack_from(f"<{num_counters}Q", raw, ctr_off))

        name = names.get(name_ref, f"<unknown:{name_ref:#018x}>")
        records.append(FuncRecord(name=name, func_hash=func_hash, counters=ctrs))

    return records


def _parse_names(blob: bytes) -> Dict[int, str]:
    """Parse the __llvm_prf_names section: null/unit-separator separated strings."""
    result: Dict[int, str] = {}
    start = 0
    for i in range(len(blob) + 1):
        b = blob[i] if i < len(blob) else 0
        if b in (0, 1, ord('\x01')):  # name separator or end
            name_bytes = blob[start:i]
            if name_bytes:
                name = name_bytes.decode("utf-8", errors="replace")
                h = llvm_md5_hash(name)
                result[h] = name
                # Also add canonical name (strip module prefix)
                if ':' in name:
                    canon = name.split(':')[-1]
                    result[llvm_md5_hash(canon)] = canon
            start = i + 1
    return result


# ---------------------------------------------------------------------------
# Indexed profile writer
# ---------------------------------------------------------------------------

def write_indexed(path: str, records: List[FuncRecord]) -> None:
    # --- Build per-function data blobs (v12 format) ---
    # For each function: FuncHash(8) + NumCounters(8) + Counters + NumBitmapBytes(8)=0
    # Key = function name bytes; hash = llvm_md5_hash(name)
    entries: List[Tuple[bytes, bytes, int]] = []
    for rec in records:
        key   = rec.name.encode("utf-8")
        n     = len(rec.counters)
        # Data: FuncHash | NumCounters | counters... | NumBitmapBytes=0
        data  = struct.pack("<QQ", rec.func_hash, n)
        data += struct.pack(f"<{n}Q", *rec.counters)
        data += struct.pack("<Q", 0)   # NumBitmapBytes = 0 (no MC/DC)
        h     = llvm_md5_hash(rec.name)
        entries.append((key, data, h))

    # --- Compute summary ---
    summary_bytes = _build_summary(records)

    # --- Build on-disk hash table ---
    table_payload, table_meta_offset = _build_hash_table(
        entries, file_base=INDEXED_HDR_SIZE + len(summary_bytes)
    )

    # HashOffset = absolute file offset of the hash table METADATA
    hash_offset = INDEXED_HDR_SIZE + len(summary_bytes) + table_meta_offset

    # --- Indexed header (9 × uint64) ---
    version_field = INDEXED_VERSION | IR_PROF_BIT
    hdr = struct.pack(INDEXED_HDR_FMT,
        INDEXED_MAGIC,
        version_field,
        0,              # Unused (was MaxFunctionCount in v1-v3)
        0,              # HashType = MD5
        hash_offset,    # HashOffset
        0,              # MemProfOffset   (no memprof data)
        0,              # BinaryIdOffset  (no binary IDs)
        0,              # TemporalProfTracesOffset
        0,              # VTableNamesOffset
    )
    assert len(hdr) == INDEXED_HDR_SIZE

    with open(path, "wb") as f:
        f.write(hdr)
        f.write(summary_bytes)
        f.write(table_payload)

    print(f"wrote {path}: {len(records)} functions, "
          f"{sum(len(r.counters) for r in records)} counters",
          file=sys.stderr)


# ---------------------------------------------------------------------------
# On-disk chained hash table (matches LLVM's OnDiskChainedHashTableGenerator)
# ---------------------------------------------------------------------------
# offset_type = uint64 for InstrProfLookupTrait
# bucket layout per bucket:
#   uint16_t  num_items
#   for each item:
#     uint64_t  hash_val
#     uint64_t  key_len
#     uint64_t  data_len
#     key_len bytes: key
#     data_len bytes: data
# table metadata at hash_offset:
#   uint64_t  num_buckets
#   uint64_t  num_entries
#   uint64_t  bucket_offsets[num_buckets]  (absolute file offsets; 0 = empty)

def _build_hash_table(
    entries: List[Tuple[bytes, bytes, int]],
    file_base: int,
) -> Tuple[bytes, int]:
    """
    Build an on-disk chained hash table payload.

    Returns (payload_bytes, meta_offset_within_payload).
    The absolute file offset of the metadata is file_base + meta_offset.
    """
    n = len(entries)
    if n == 0:
        num_buckets = 1
    elif n <= 2:
        num_buckets = 1
    else:
        # Smallest power-of-2 with load ≤ 3/4
        num_buckets = 1
        while num_buckets < (n * 4 // 3 + 1):
            num_buckets <<= 1

    # Assign entries to buckets
    buckets: List[List[Tuple[bytes, bytes, int]]] = [[] for _ in range(num_buckets)]
    for key, data, h in entries:
        buckets[h & (num_buckets - 1)].append((key, data, h))

    # --- Payload (bucket data) ---
    # OnDiskChainedHashTable requires that bucket offset 0 is never valid
    # (0 means "empty bucket").  We start with one padding byte.
    payload = b"\x00"
    bucket_offsets = [0] * num_buckets  # 0 = empty

    for i, bucket in enumerate(buckets):
        if not bucket:
            continue
        # Absolute file offset of this bucket's data
        bucket_offsets[i] = file_base + len(payload)

        # uint16_t: number of items in bucket
        payload += struct.pack("<H", len(bucket))
        for key, data, h in bucket:
            payload += struct.pack("<Q", h)              # hash_val (uint64)
            payload += struct.pack("<QQ", len(key), len(data))  # key_len, data_len
            payload += key
            payload += data

    # Pad payload to uint64 alignment before metadata
    while len(payload) % 8 != 0:
        payload += b"\x00"

    meta_offset = len(payload)

    # Table metadata
    payload += struct.pack("<Q", num_buckets)
    payload += struct.pack("<Q", n)             # num_entries
    for off in bucket_offsets:
        payload += struct.pack("<Q", off)

    return payload, meta_offset


# ---------------------------------------------------------------------------
# Profile summary (required since indexed v4)
# ---------------------------------------------------------------------------

def _build_summary(records: List[FuncRecord]) -> bytes:
    """Build IndexedInstrProf::Summary bytes for v12."""
    # Gather all counter values
    all_counts: List[int] = []
    entry_counts: List[int] = []
    for rec in records:
        if rec.counters:
            entry_counts.append(rec.counters[0])
            all_counts.extend(rec.counters)

    total_funcs  = len(records)
    total_blocks = sum(len(r.counters) for r in records)
    total_count  = sum(all_counts)
    max_block    = max(all_counts) if all_counts else 0
    max_func     = max(entry_counts) if entry_counts else 0
    # MaxInternalBlockCount: max non-entry counter
    internal = [c for rec in records for c in rec.counters[1:]]
    max_internal = max(internal) if internal else 0

    # Summary header: NumSummaryFields, NumCutoffEntries
    out = struct.pack("<QQ", SUMMARY_N_FIELDS, SUMMARY_N_CUTOFFS)
    # 6 summary fields (must match SummaryFieldKind enum order)
    out += struct.pack("<6Q",
        total_funcs,    # TotalNumFunctions = 0
        total_blocks,   # TotalNumBlocks    = 1
        max_func,       # MaxFunctionCount  = 2
        max_block,      # MaxBlockCount     = 3
        max_internal,   # MaxInternalBlockCount = 4
        total_count,    # TotalBlockCount   = 5
    )
    # Cutoff entries: (Cutoff, MinBlockCount, NumBlocks) per percentile
    sorted_counts = sorted(all_counts, reverse=True)
    for cutoff_ppm in SUMMARY_CUTOFFS:
        if not sorted_counts:
            out += struct.pack("<QQQ", cutoff_ppm, 0, 0)
            continue
        # Number of blocks at or above this percentile
        threshold = int(total_count * cutoff_ppm / 1_000_000)
        num_above = sum(1 for c in sorted_counts if c >= threshold) if threshold else 0
        min_count = sorted_counts[num_above - 1] if num_above else 0
        out += struct.pack("<QQQ", cutoff_ppm, min_count, num_above)

    return out


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} <input.profraw> <output.profdata>", file=sys.stderr)
        sys.exit(1)

    in_path, out_path = sys.argv[1], sys.argv[2]
    print(f"reading {in_path} ...", file=sys.stderr)
    records = read_raw(in_path)
    print(f"  {len(records)} function records", file=sys.stderr)
    if not records:
        print("warning: no records found; profile may be empty", file=sys.stderr)
    write_indexed(out_path, records)


if __name__ == "__main__":
    main()

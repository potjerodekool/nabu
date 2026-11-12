/*
 * nabu_profile_rt.c - Lightweight LLVM 21 raw profile runtime for PGO.
 *
 * Writes a .profraw file (version 10, IR-profile variant) on program exit.
 * Implements the section-based approach: reads __llvm_prf_data,
 * __llvm_prf_cnts, and __llvm_prf_names sections written by pgo-instr-gen.
 *
 * Usage: link this file into the instrumented binary (see nabu-native.sh
 * --pgo-instrument).  Set LLVM_PROFILE_FILE=path/to/out.profraw before
 * running to control the output path (default: "default.profraw").
 *
 * After the run, convert with:
 *   python3 docs/perf-study/pgo_merge.py default.profraw profile.profdata
 * Then rebuild with:
 *   ./nabu-native.sh --pgo-use profile.profdata
 *
 * Value profiling (indirect call promotion) is intentionally NOT supported:
 * this runtime only records edge counters, which still enables the main PGO
 * benefits (inlining, branch prediction, loop unrolling).
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ---- LLVM profile constants -------------------------------------------- */
#define INSTR_PROF_RAW_MAGIC_64  UINT64_C(0x8172666f72706cff)
#define INSTR_PROF_RAW_VERSION   UINT64_C(10)
/* Bit 56 set: this is an IR-instrumented profile (pgo-instr-gen output). */
#define VARIANT_MASK_IR_PROF     (UINT64_C(1) << 56)
/* IPVK_Last = 2 (IndirectCallTarget=0, MemOPSize=1, VTableTarget=2). */
#define IPVK_LAST                UINT64_C(2)

/* ---- ELF section bounds (provided by the linker) ----------------------- */
extern char __start___llvm_prf_data  __attribute__((weak));
extern char __stop___llvm_prf_data   __attribute__((weak));
extern char __start___llvm_prf_cnts  __attribute__((weak));
extern char __stop___llvm_prf_cnts   __attribute__((weak));
extern char __start___llvm_prf_names __attribute__((weak));
extern char __stop___llvm_prf_names  __attribute__((weak));

/* ---- __llvm_profile_data record (64 bytes for 64-bit targets) ----------
 *
 * Layout from InstrProfData.inc (INSTR_PROF_DATA macro expansion, 64-bit):
 *   uint64_t  NameRef           offset  0
 *   uint64_t  FuncHash          offset  8
 *   int64_t   CounterPtr        offset 16  (relative to &this->CounterPtr)
 *   int64_t   BitmapPtr         offset 24  (relative, 0 when no MC/DC)
 *   uintptr_t FunctionPointer   offset 32
 *   uintptr_t Values            offset 40  (value-prof linked list, ignored here)
 *   uint32_t  NumCounters       offset 48
 *   uint16_t  NumValueSites[3]  offset 52
 *   uint16_t  _pad              offset 58
 *   uint32_t  NumBitmapBytes    offset 60
 *                               total: 64
 */
typedef struct {
    uint64_t  name_ref;
    uint64_t  func_hash;
    int64_t   counter_ptr;      /* relative to &this->counter_ptr */
    int64_t   bitmap_ptr;
    uintptr_t func_ptr;
    uintptr_t values;
    uint32_t  num_counters;
    uint16_t  num_value_sites[3];
    uint16_t  _pad;
    uint32_t  num_bitmap_bytes;
} __attribute__((aligned(8))) LLVMProfData;

/* ---- Raw profile file header (16 × uint64 = 128 bytes) -----------------
 * Fields from InstrProfData.inc INSTR_PROF_RAW_HEADER macro expansion.
 */
typedef struct {
    uint64_t magic;
    uint64_t version;
    uint64_t binary_ids_size;
    uint64_t num_data;
    uint64_t padding_before_counters;
    uint64_t num_counters;
    uint64_t padding_after_counters;
    uint64_t num_bitmap_bytes;
    uint64_t padding_after_bitmap;
    uint64_t names_size;
    uint64_t counters_delta;    /* = counters_mem_base - data_mem_base */
    uint64_t bitmap_delta;      /* = bitmap_mem_base  - data_mem_base, 0 here */
    uint64_t names_delta;       /* = (uintptr_t)names_begin (absolute) */
    uint64_t num_vtables;
    uint64_t vnames_size;
    uint64_t value_kind_last;
} __attribute__((packed)) LLVMProfHeader;

/* ---- Public runtime API ------------------------------------------------ */

/* This symbol is read by the instrumented code to know the raw format version. Defined weakly so the
 * instrumented binary's own definition (from the IR) wins when both are present. */
__attribute__((weak))
uint64_t __llvm_profile_raw_version =
    INSTR_PROF_RAW_VERSION | VARIANT_MASK_IR_PROF;

static const char *g_profile_filename = "default.profraw";

void __llvm_profile_set_filename(const char *name) {
    if (name && name[0])
        g_profile_filename = name;
}

/* Value-profiling stubs: we record nothing, keeping the binary lean. */
void __llvm_profile_instrument_target(uint64_t v, void *d, uint32_t i) {
    (void)v; (void)d; (void)i;
}
void __llvm_profile_instrument_target_value(uint64_t v, void *d, uint32_t i) {
    (void)v; (void)d; (void)i;
}
void __llvm_profile_instrument_memop(uint64_t v, void *d, uint32_t i) {
    (void)v; (void)d; (void)i;
}

/* Additional registration stubs some LLVM versions may reference. */
void __llvm_profile_register_function(void *data)      { (void)data; }
void __llvm_profile_register_names_function(void *names, uint64_t sz) {
    (void)names; (void)sz;
}

/* ---- Profile writer ---------------------------------------------------- */

static uint64_t align8(uint64_t n) { return (n + 7u) & ~UINT64_C(7); }

void __llvm_profile_write_file(void) {
    /* Guard against missing sections (e.g., non-instrumented build). */
    if (!&__start___llvm_prf_data || !&__start___llvm_prf_cnts)
        return;

    const char *data_beg  = &__start___llvm_prf_data;
    const char *data_end  = &__stop___llvm_prf_data;
    const char *cnts_beg  = &__start___llvm_prf_cnts;
    const char *cnts_end  = &__stop___llvm_prf_cnts;
    const char *names_beg = &__start___llvm_prf_names;
    const char *names_end = &__stop___llvm_prf_names;

    uint64_t data_sz  = (uint64_t)(data_end  - data_beg);
    uint64_t cnts_sz  = (uint64_t)(cnts_end  - cnts_beg);
    uint64_t names_sz = (uint64_t)(names_end - names_beg);
    uint64_t num_data = data_sz  / sizeof(LLVMProfData);
    uint64_t num_cnts = cnts_sz  / sizeof(uint64_t);

    /* File layout: header | data records | [pad] | counters | [pad] | names */
    uint64_t padded_data = align8(data_sz);
    uint64_t pad_before  = padded_data - data_sz;
    uint64_t padded_cnts = align8(cnts_sz);
    uint64_t pad_after   = padded_cnts - cnts_sz;

    /* CountersDelta lets pgo_merge.py translate relative counter pointers to
     * file offsets.  See: docs/perf-study/pgo_merge.py for the derivation. */
    uint64_t counters_delta = (uint64_t)((uintptr_t)cnts_beg - (uintptr_t)data_beg);

    /* Honour LLVM_PROFILE_FILE env override. */
    const char *env = getenv("LLVM_PROFILE_FILE");
    if (env && env[0])
        g_profile_filename = env;

    FILE *f = fopen(g_profile_filename, "wb");
    if (!f) {
        fprintf(stderr, "[nabu_profile_rt] cannot open %s\n", g_profile_filename);
        return;
    }

    LLVMProfHeader hdr = {
        .magic                 = INSTR_PROF_RAW_MAGIC_64,
        .version               = INSTR_PROF_RAW_VERSION | VARIANT_MASK_IR_PROF,
        .binary_ids_size       = 0,
        .num_data              = num_data,
        .padding_before_counters = pad_before,
        .num_counters          = num_cnts,
        .padding_after_counters  = pad_after,
        .num_bitmap_bytes      = 0,
        .padding_after_bitmap  = 0,
        .names_size            = names_sz,
        .counters_delta        = counters_delta,
        .bitmap_delta          = 0,
        .names_delta           = (uint64_t)(uintptr_t)names_beg,
        .num_vtables           = 0,
        .vnames_size           = 0,
        .value_kind_last       = IPVK_LAST,
    };

    static const char zeros[8] = { 0 };

    fwrite(&hdr,     sizeof(hdr), 1, f);
    fwrite(data_beg, data_sz,     1, f);
    if (pad_before) fwrite(zeros, pad_before, 1, f);
    fwrite(cnts_beg, cnts_sz,     1, f);
    if (pad_after)  fwrite(zeros, pad_after,  1, f);
    fwrite(names_beg, names_sz,   1, f);

    fclose(f);
    fprintf(stderr, "[nabu_profile_rt] wrote %s (%lu functions)\n",
            g_profile_filename, (unsigned long)num_data);
}

/* ---- Lifecycle --------------------------------------------------------- */

void __llvm_profile_init(void) {
    atexit(__llvm_profile_write_file);
}

__attribute__((constructor))
static void nabu_profile_ctor(void) {
    __llvm_profile_init();
}

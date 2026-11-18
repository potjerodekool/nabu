/*
 * nabu_profile_rt.c - Lightweight LLVM raw profile runtime for PGO (instrumented builds only).
 *
 * Writes a .profraw file (raw version 10, IR-profile variant) when the program ends: the edge counters, and the
 * targets of indirect calls (value profiling, IPVK_IndirectCallTarget), so that a build with the profile can promote
 * a hot virtual or interface call to a direct call that LLVM may inline.
 *
 * The instrumented code (pgo-instr-gen) puts its records in sections: on ELF __llvm_prf_data/_cnts/_names, found
 * through the __start_/__stop_ symbols the linker makes; on Windows (COFF) .lprfd$M, .lprfc$M, .lprfn$M and .lprfv$M,
 * found through markers of our own in the $A and $Z parts of the same sections (the linker sorts the parts of a
 * grouped section by the text after the $), as compiler-rt does.
 *
 * Usage: build with -Dnabu.pgo.pipeline=pgo-instr-gen,default<O2> (the Linker then links this file), run the program
 * (LLVM_PROFILE_FILE sets the output, default default.profraw), then merge with llvm-profdata or
 * docs/perf-study/pgo_merge.py and build with -Dnabu.pgo.pipeline=pgo-instr-use<profile.profdata>,default<O2>.
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- LLVM profile constants -------------------------------------------- */
/* The bytes "rfoprlÿ" read as a little-endian uint64 (the value LLVM writes; the byte-reversed one made
 * llvm-profdata take the file for big-endian and read "raw profile format version = 1"). */
#define INSTR_PROF_RAW_MAGIC_64  UINT64_C(0xff6c70726f667281)
#define INSTR_PROF_RAW_VERSION   UINT64_C(10)
/* Bit 56 set: this is an IR-instrumented profile (pgo-instr-gen output). */
#define VARIANT_MASK_IR_PROF     (UINT64_C(1) << 56)
/* IPVK_Last = 2 (IndirectCallTarget=0, MemOPSize=1, VTableTarget=2). */
#define IPVK_LAST                2
/* Targets kept per call site (compiler-rt keeps up to 255; the promotion only looks at the hottest few). */
#define MAX_VALUES_PER_SITE      16

/* ---- Section bounds ---------------------------------------------------- */
#ifdef _WIN32
/* The linker merges the .lprfd$M, .lprfc$M and .lprfn$M parts of all objects into the sections .lprfd, .lprfc and
 * .lprfn of the executable: their bounds are read from the section table of the executable itself. (Markers of our own
 * in the $A and $Z parts did not work: a marker with other flags than LLVM's read-only names ends up in a section of its
 * own, and the names had no bounds.) */
#include <windows.h>
typedef struct { const char *begin, *end; } prf_range;
static prf_range prf_section(const char *name) {
    prf_range r = { NULL, NULL };
    const char *base = (const char *) GetModuleHandleA(NULL);
    const IMAGE_DOS_HEADER *dos = (const IMAGE_DOS_HEADER *) base;
    const IMAGE_NT_HEADERS *nt = (const IMAGE_NT_HEADERS *) (base + dos->e_lfanew);
    const IMAGE_SECTION_HEADER *s = IMAGE_FIRST_SECTION(nt);
    for (unsigned i = 0; i < nt->FileHeader.NumberOfSections; i++, s++) {
        if (strncmp((const char *) s->Name, name, IMAGE_SIZEOF_SHORT_NAME) != 0) continue;
        /* the largest one if there are several (an object with other flags gets a section of its own) */
        if (r.begin == NULL || s->Misc.VirtualSize > (DWORD) (r.end - r.begin)) {
            r.begin = base + s->VirtualAddress;
            r.end = r.begin + s->Misc.VirtualSize;
        }
    }
    return r;
}
#define DATA_BEGIN   (prf_section(".lprfd").begin)
#define DATA_END     (prf_section(".lprfd").end)
#define CNTS_BEGIN   (prf_section(".lprfc").begin)
#define CNTS_END     (prf_section(".lprfc").end)
#define NAMES_BEGIN  (prf_section(".lprfn").begin)
#define NAMES_END    (prf_section(".lprfn").end)
#else
extern char __start___llvm_prf_data  __attribute__((weak));
extern char __stop___llvm_prf_data   __attribute__((weak));
extern char __start___llvm_prf_cnts  __attribute__((weak));
extern char __stop___llvm_prf_cnts   __attribute__((weak));
extern char __start___llvm_prf_names __attribute__((weak));
extern char __stop___llvm_prf_names  __attribute__((weak));
#define DATA_BEGIN   (&__start___llvm_prf_data)
#define DATA_END     (&__stop___llvm_prf_data)
#define CNTS_BEGIN   (&__start___llvm_prf_cnts)
#define CNTS_END     (&__stop___llvm_prf_cnts)
#define NAMES_BEGIN  (&__start___llvm_prf_names)
#define NAMES_END    (&__stop___llvm_prf_names)
#endif

/* ---- __llvm_profile_data record (64 bytes for 64-bit targets) ----------
 *   uint64_t  NameRef           offset  0   (MD5 of the PGO name)
 *   uint64_t  FuncHash          offset  8
 *   int64_t   CounterPtr        offset 16  (relative to the record)
 *   int64_t   BitmapPtr         offset 24
 *   uintptr_t FunctionPointer   offset 32  (the function: targets of indirect calls are matched against it)
 *   uintptr_t Values            offset 40  (one slot per value site of the function, for the runtime's lists)
 *   uint32_t  NumCounters       offset 48
 *   uint16_t  NumValueSites[3]  offset 52
 *   uint16_t  _pad              offset 58
 *   uint32_t  NumBitmapBytes    offset 60
 */
typedef struct {
    uint64_t  name_ref;
    uint64_t  func_hash;
    int64_t   counter_ptr;
    int64_t   bitmap_ptr;
    uintptr_t func_ptr;
    uintptr_t values;
    uint32_t  num_counters;
    uint16_t  num_value_sites[3];
    uint16_t  _pad;
    uint32_t  num_bitmap_bytes;
} __attribute__((aligned(8))) LLVMProfData;

/* ---- Raw profile file header (16 x uint64 = 128 bytes) ----------------- */
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
    uint64_t bitmap_delta;
    uint64_t names_delta;       /* = (uintptr_t)names_begin (absolute) */
    uint64_t num_vtables;
    uint64_t vnames_size;
    uint64_t value_kind_last;
} __attribute__((packed)) LLVMProfHeader;

/* ---- Value profiling --------------------------------------------------- */

/* The targets seen at one value site: a list hung in the function's Values slot of the site. */
typedef struct prof_value_node {
    uint64_t value;
    uint64_t count;
    struct prof_value_node *next;
} prof_value_node;

static volatile int prof_lock = 0;
static void prof_acquire(void) { while (__atomic_exchange_n(&prof_lock, 1, __ATOMIC_ACQUIRE)) { } }
static void prof_release(void) { __atomic_store_n(&prof_lock, 0, __ATOMIC_RELEASE); }

/* The slot of site `index` (counted over all value kinds before it) of a function's record. */
static prof_value_node **prof_site_slot(LLVMProfData *data, uint32_t kind, uint32_t index) {
    if (data == NULL || data->values == 0) return NULL;
    uint32_t base = 0;
    for (uint32_t k = 0; k < kind; k++) base += data->num_value_sites[k];
    if (index >= data->num_value_sites[kind]) return NULL;
    return (prof_value_node **) data->values + base + index;
}

static void prof_record(uint64_t value, void *record, uint32_t kind, uint32_t index) {
    prof_value_node **slot = prof_site_slot((LLVMProfData *) record, kind, index);
    if (slot == NULL) return;
    prof_acquire();
    int n = 0;
    for (prof_value_node *node = *slot; node != NULL; node = node->next, n++) {
        if (node->value == value) {
            node->count++;
            prof_release();
            return;
        }
    }
    if (n < MAX_VALUES_PER_SITE) {
        prof_value_node *node = (prof_value_node *) malloc(sizeof *node);
        if (node != NULL) {
            node->value = value;
            node->count = 1;
            node->next = *slot;
            *slot = node;
        }
    }
    prof_release();
}

void __llvm_profile_instrument_target(uint64_t value, void *data, uint32_t index) {
    prof_record(value, data, 0, index);
}
void __llvm_profile_instrument_target_value(uint64_t value, void *data, uint32_t index, uint64_t step) {
    (void) step;
    prof_record(value, data, 0, index);
}
/* Sizes of memcpy/memset (IPVK_MemOPSize) are not kept: the reader takes sites without values. */
void __llvm_profile_instrument_memop(uint64_t value, void *data, uint32_t index) {
    (void) value; (void) data; (void) index;
}

/* Registration functions some LLVM versions reference. */
void __llvm_profile_register_function(void *data)      { (void)data; }
void __llvm_profile_register_names_function(void *names, uint64_t sz) { (void)names; (void)sz; }

/* The instrumented code reads this to know the raw format version; weak, so a definition in the IR wins. */
__attribute__((weak))
uint64_t __llvm_profile_raw_version = INSTR_PROF_RAW_VERSION | VARIANT_MASK_IR_PROF;
/* Referenced by the instrumented code to pull in the runtime. */
int __llvm_profile_runtime = 0;

static const char *g_profile_filename = "default.profraw";

void __llvm_profile_set_filename(const char *name) {
    if (name && name[0]) g_profile_filename = name;
}

/* ---- Profile writer ---------------------------------------------------- */

static uint64_t align8(uint64_t n) { return (n + 7u) & ~UINT64_C(7); }

static void write_zeros(FILE *f, uint64_t n) {
    static const char zeros[8] = { 0 };
    while (n > 0) {
        const uint64_t chunk = n < 8 ? n : 8;
        fwrite(zeros, (size_t) chunk, 1, f);
        n -= chunk;
    }
}

/*
 * The value data of one function, as LLVM's ValueProfData: uint32 TotalSize, uint32 NumValueKinds, then for each
 * kind that has sites a ValueProfRecord: uint32 Kind, uint32 NumValueSites, uint8 SiteCountArray[NumValueSites]
 * padded to 8 bytes (counted from the record), then {uint64 Value, uint64 Count} per value, site after site.
 */
static void write_value_data(FILE *f, LLVMProfData *d) {
    uint32_t kinds = 0;
    uint64_t total = 8;
    for (uint32_t k = 0; k <= IPVK_LAST; k++) {
        const uint32_t sites = d->num_value_sites[k];
        if (sites == 0) continue;
        kinds++;
        uint64_t values = 0;
        for (uint32_t s = 0; s < sites; s++) {
            uint32_t n = 0;
            prof_value_node **slot = prof_site_slot(d, k, s);
            for (prof_value_node *node = slot != NULL ? *slot : NULL; node != NULL; node = node->next) n++;
            values += n;
        }
        total += align8(8 + sites) + values * 16;
    }
    const uint32_t total32 = (uint32_t) total;
    fwrite(&total32, 4, 1, f);
    fwrite(&kinds, 4, 1, f);
    for (uint32_t k = 0; k <= IPVK_LAST; k++) {
        const uint32_t sites = d->num_value_sites[k];
        if (sites == 0) continue;
        fwrite(&k, 4, 1, f);
        fwrite(&sites, 4, 1, f);
        for (uint32_t s = 0; s < sites; s++) {
            uint32_t n = 0;
            prof_value_node **slot = prof_site_slot(d, k, s);
            for (prof_value_node *node = slot != NULL ? *slot : NULL; node != NULL; node = node->next) n++;
            const uint8_t count = (uint8_t) (n > 255 ? 255 : n);
            fwrite(&count, 1, 1, f);
        }
        write_zeros(f, align8(8 + sites) - (8 + sites));
        for (uint32_t s = 0; s < sites; s++) {
            prof_value_node **slot = prof_site_slot(d, k, s);
            for (prof_value_node *node = slot != NULL ? *slot : NULL; node != NULL; node = node->next) {
                fwrite(&node->value, 8, 1, f);
                fwrite(&node->count, 8, 1, f);
            }
        }
    }
}

void __llvm_profile_write_file(void) {
#ifndef _WIN32
    if (!&__start___llvm_prf_data || !&__start___llvm_prf_cnts) return;   /* not an instrumented build */
#endif
    const char *data_beg  = DATA_BEGIN;
    const char *data_end  = DATA_END;
    const char *cnts_beg  = CNTS_BEGIN;
    const char *cnts_end  = CNTS_END;
    const char *names_beg = NAMES_BEGIN;
    const char *names_end = NAMES_END;
    if (data_end <= data_beg || cnts_end <= cnts_beg) return;

    uint64_t data_sz  = (uint64_t)(data_end  - data_beg);
    uint64_t cnts_sz  = (uint64_t)(cnts_end  - cnts_beg);
    uint64_t names_sz = (uint64_t)(names_end - names_beg);
    uint64_t num_data = data_sz  / sizeof(LLVMProfData);
    uint64_t num_cnts = cnts_sz  / sizeof(uint64_t);

    /* File layout: header | data records | [pad] | counters | [pad] | names | [pad] | value data */
    uint64_t pad_before  = align8(data_sz) - data_sz;
    uint64_t pad_after   = align8(cnts_sz) - cnts_sz;
    uint64_t counters_delta = (uint64_t)((uintptr_t)cnts_beg - (uintptr_t)data_beg);

    const char *env = getenv("LLVM_PROFILE_FILE");
    if (env && env[0]) g_profile_filename = env;

    FILE *f = fopen(g_profile_filename, "wb");
    if (!f) {
        fprintf(stderr, "[nabu_profile_rt] cannot open %s\n", g_profile_filename);
        return;
    }

    LLVMProfHeader hdr = {
        .magic                   = INSTR_PROF_RAW_MAGIC_64,
        .version                 = INSTR_PROF_RAW_VERSION | VARIANT_MASK_IR_PROF,
        .binary_ids_size         = 0,
        .num_data                = num_data,
        .padding_before_counters = pad_before,
        .num_counters            = num_cnts,
        .padding_after_counters  = pad_after,
        .num_bitmap_bytes        = 0,
        .padding_after_bitmap    = 0,
        .names_size              = names_sz,
        .counters_delta          = counters_delta,
        .bitmap_delta            = 0,
        .names_delta             = (uint64_t)(uintptr_t)names_beg,
        .num_vtables             = 0,
        .vnames_size             = 0,
        .value_kind_last         = IPVK_LAST,
    };

#ifdef _WIN32
    /* CounterPtr (counters minus record) is filled in through a 32-bit relocation on COFF, without sign extension:
     * when the linker puts the counters before the records (GNU ld does) the upper half is 0 instead of all ones, and
     * llvm-profdata saw "counter offset 4294967296". Sign extending is a no-op for a field that is right already. */
    for (uint64_t i = 0; i < num_data; i++) {
        LLVMProfData *d = (LLVMProfData *) data_beg + i;
        d->counter_ptr = (int64_t) (int32_t) (uint32_t) (uint64_t) d->counter_ptr;
    }
#endif
    prof_acquire();
    fwrite(&hdr,     sizeof(hdr), 1, f);
    fwrite(data_beg, (size_t) data_sz, 1, f);
    write_zeros(f, pad_before);
    fwrite(cnts_beg, (size_t) cnts_sz, 1, f);
    write_zeros(f, pad_after);
    fwrite(names_beg, (size_t) names_sz, 1, f);
    write_zeros(f, align8(names_sz) - names_sz);
    uint64_t with_values = 0;
    for (uint64_t i = 0; i < num_data; i++) {
        LLVMProfData *d = (LLVMProfData *) data_beg + i;
        if (d->num_value_sites[0] + d->num_value_sites[1] + d->num_value_sites[2] == 0) continue;
        write_value_data(f, d);
        with_values++;
    }
    prof_release();

    fclose(f);
    /* Silent unless NABU_PROFILE_VERBOSE is set: a program under test keeps its own output. */
    if (getenv("NABU_PROFILE_VERBOSE") != NULL) {
        fprintf(stderr, "[nabu_profile_rt] wrote %s (%lu functions, %lu with value sites)\n",
                g_profile_filename, (unsigned long) num_data, (unsigned long) with_values);
    }
}

/* ---- Lifecycle --------------------------------------------------------- */

/* NABU_PROFILE_WRITE_EVERY=<seconds>: also write the file every so many seconds, for a program that does not end by
 * itself (a server that is killed after its training load never reaches atexit). */
#ifdef _WIN32
#include <windows.h>
static DWORD WINAPI prof_writer(LPVOID arg) {
    const DWORD ms = (DWORD) (uintptr_t) arg;
    for (;;) { Sleep(ms); __llvm_profile_write_file(); }
    return 0;
}
#else
#include <pthread.h>
#include <unistd.h>
static void *prof_writer(void *arg) {
    const unsigned seconds = (unsigned) ((uintptr_t) arg / 1000);
    for (;;) { sleep(seconds > 0 ? seconds : 1); __llvm_profile_write_file(); }
    return NULL;
}
#endif

void __llvm_profile_init(void) {
    atexit(__llvm_profile_write_file);
    const char *every = getenv("NABU_PROFILE_WRITE_EVERY");
    const long seconds = every != NULL ? strtol(every, NULL, 10) : 0;
    if (seconds > 0) {
#ifdef _WIN32
        CreateThread(NULL, 0, prof_writer, (LPVOID) (uintptr_t) (seconds * 1000), 0, NULL);
#else
        pthread_t t;
        pthread_create(&t, NULL, prof_writer, (void *) (uintptr_t) (seconds * 1000));
#endif
    }
}

__attribute__((constructor))
static void nabu_profile_ctor(void) {
    __llvm_profile_init();
}

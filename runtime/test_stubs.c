/* Stubs for symbols normally provided by the compiled module. */
#include "nabu_runtime.h"

const char *const nabu_array_type_names[] = { NULL };
const int32_t nabu_array_type_count = 1;

typedef struct nabu_exception_entry { const char *name; void *type; int64_t size; void (*init)(void *, void *); } nabu_exception_entry;
const nabu_exception_entry nabu_exception_table[] = { { NULL, NULL, 0, NULL } };
const int32_t nabu_exception_count = 0;

nabu_string_meta_t nabu_string_meta = { NULL, NULL, NULL };

/* weak so they don't conflict if defined elsewhere */
__attribute__((weak)) int nabu_class_is_annotation(const char *internal) { (void)internal; return 0; }
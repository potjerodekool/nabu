#define _XOPEN_SOURCE 600
#define _DARWIN_C_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <ucontext.h>
#include <sys/mman.h>

static size_t resident(void *p, size_t size) {
    size_t page = (size_t)sysconf(_SC_PAGESIZE), n = size / page, r = 0;
    char *vec = malloc(n);
    if (mincore(p, size, vec) != 0) { free(vec); return 9999; }
    for (size_t i = 0; i < n; i++) r += vec[i] & 1;
    free(vec);
    return r;
}

static void body(void) {}

int main(void) {
    size_t page = (size_t)sysconf(_SC_PAGESIZE), size = (size_t)4 << 20;
    int flags[2] = { MAP_PRIVATE | MAP_ANONYMOUS, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE };
    for (int f = 0; f < 2; f++) {
        void *m = mmap(NULL, size, PROT_READ | PROT_WRITE, flags[f], -1, 0);
        printf("flags %d: after mmap %zu of %zu pages\n", f, resident(m, size), size / page);
        mprotect(m, page, PROT_NONE);
        printf("  after mprotect %zu\n", resident(m, size));
        ucontext_t uc;
        getcontext(&uc);
        printf("  after getcontext %zu\n", resident(m, size));
        uc.uc_stack.ss_sp = (char *)m + page;
        uc.uc_stack.ss_size = size - page;
        uc.uc_link = NULL;
        makecontext(&uc, body, 0);
        printf("  after makecontext %zu\n", resident(m, size));
        /* a read of the top page */
        volatile char c = ((char *)m)[size - 1];
        (void)c;
        printf("  after read of last byte %zu\n", resident(m, size));
        ((char *)m)[size - 1] = 1;
        printf("  after write of last byte %zu\n", resident(m, size));
    }
    return 0;
}

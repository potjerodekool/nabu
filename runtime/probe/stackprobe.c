#define _XOPEN_SOURCE 600
#define _DARWIN_C_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

static ucontext_t main_uc, uc;
static int depth_result;

static int deep(int n) {
    volatile char buf[8192];
    memset((void *)buf, n, sizeof buf);
    return n == 0 ? buf[0] : deep(n - 1) + buf[100];
}

static void body(void) {
    depth_result = deep(100); /* about 800 KB of stack */
}

int main(void) {
    size_t page = (size_t)sysconf(_SC_PAGESIZE), size = (size_t)4 << 20;
    void *m = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    mprotect(m, page, PROT_NONE);
    getcontext(&uc);
    /* makecontext only gets the top window of the stack */
    uc.uc_stack.ss_sp = (char *)m + size - 2 * page;
    uc.uc_stack.ss_size = 2 * page;
    uc.uc_link = &main_uc;
    makecontext(&uc, body, 0);
    printf("window: after makecontext %zu of %zu pages resident\n", resident(m, size), size / page);
    swapcontext(&main_uc, &uc);
    printf("window: ran deep(100) = %d, resident now %zu\n", depth_result, resident(m, size));
    return 0;
}

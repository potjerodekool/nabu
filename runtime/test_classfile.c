#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nabu_interpreter.h"
#include "nabu_runtime.h"

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/tmp/opencode/jtest/Hello.class";
    nabu_interp_init();
    /* Load helper first, then main. */
    if (nabu_classfile_load("/tmp/opencode/jtest/Helper.class") != 0) {
        fprintf(stderr, "failed to load Helper\n"); return 1;
    }
    if (nabu_classfile_load(path) != 0) {
        fprintf(stderr, "failed to load %s\n", path); return 1;
    }
    /* find main method */
    nabu_interp_method_t *m = nabu_interp_find_method("Hello", "main", "([Ljava/lang/String;)V");
    printf("main: code_len=%d\n", m->code_len);
    for (int i = 0; i < m->code_len; i++) {
        printf("%02x ", m->code[i]);
        if ((i+1)%16==0) printf("\n");
    }
    printf("\n");
    /* dump all methods names */
    /* invoke main */
    void *r = nabu_interp_invoke_by_name("Hello", "main", "([Ljava/lang/String;)V", NULL, 0);
    printf("main returned %p\n", r);
    void *args[2];
    args[0] = (void *)(intptr_t)3; args[1] = (void *)(intptr_t)4;
    void *a = nabu_interp_invoke_by_name("Hello", "add", "(II)I", args, 2);
    printf("add(3,4) = %d\n", (int)(intptr_t)a);
    return 0;
}
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nabu_interpreter.h"
#include "nabu_runtime.h"

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/tmp/opencode/jtest/LambdaTest.class";
    nabu_interp_init();
    if (nabu_classfile_load(path) != 0) { fprintf(stderr, "load fail\n"); return 1; }
    void *r = nabu_interp_invoke_by_name("LambdaTest", "main", "([Ljava/lang/String;)V", NULL, 0);
    printf("main returned %p\n", r);
    return 0;
}
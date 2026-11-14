#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nabu_interpreter.h"
#include "nabu_runtime.h"

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/tmp/opencode/jtest/StrConcat.class";
    nabu_interp_init();
    int rc = nabu_classfile_load(path);
    printf("load rc=%d methods=%d\n", rc, nabu_interp_method_count());
    return 0;
}
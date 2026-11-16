/* Generic driver: test_run <file.class> <ClassName> — loads the class file and runs ClassName.main(String[]). */
#include <stdio.h>
#include <stdlib.h>
#include "nabu_interpreter.h"
#include "nabu_runtime.h"

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s <file.class> <ClassName>\n", argv[0]); return 2; }
    nabu_interp_init();
    if (nabu_classfile_load(argv[1]) != 0) { fprintf(stderr, "load fail\n"); return 1; }
    nabu_interp_invoke_by_name(argv[2], "main", "([Ljava/lang/String;)V", NULL, 0);
    return 0;
}

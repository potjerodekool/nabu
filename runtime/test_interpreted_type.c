#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nabu_runtime.h"

static int failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "FAIL: %s\n", msg); failures++; } \
    else { printf("PASS: %s\n", msg); } \
} while (0)

int main(void) {
    /* Registreer een kleine hiërarchie. */
    nabu_type_info *obj = nabu_register_interpreted_type("java/lang/Object", NULL, 0, NULL, NULL);
    CHECK(obj != NULL, "register Object");
    CHECK(obj->super_type == NULL, "Object has no super");

    void **foo_vt = (void **)calloc(4, sizeof(void *));
    nabu_type_info *foo = nabu_register_interpreted_type("test/Foo", "java/lang/Object", 32, foo_vt, NULL);
    CHECK(foo != NULL, "register Foo");
    CHECK(foo->super_type == obj, "Foo super is Object");
    CHECK(foo->vtable == foo_vt, "Foo vtable set");

    nabu_type_info *bar = nabu_register_interpreted_type("test/Bar", "test/Foo", 48, NULL, NULL);
    CHECK(bar != NULL, "register Bar");
    CHECK(bar->super_type == foo, "Bar super is Foo");

    /* Idempotent: opnieuw registreren geeft dezelfde pointer terug. */
    nabu_type_info *foo2 = nabu_register_interpreted_type("test/Foo", NULL, 0, NULL, NULL);
    CHECK(foo2 == foo, "re-register returns same type");

    /* Lookups. */
    CHECK(nabu_interpreted_type_for("test/Foo") == foo, "interpreted lookup Foo");
    CHECK(nabu_interpreted_type_for("nope") == NULL, "interpreted lookup missing");
    CHECK(nabu_compiled_type_for("test/Bar") == bar, "compiled_type_for fallback to interpreted");
    CHECK(nabu_interpreted_type_count() == 3, "count == 3");

    /* Object-instanceof. */
    size_t sz = sizeof(nabu_object) + sizeof(void *);
    nabu_object *o = (nabu_object *)calloc(1, sz);
    o->type = bar;
    CHECK(nabu_jcl_is_object(o) == 1, "interpreted object recognized");
    CHECK(nabu_instanceof(o, "test/Bar") == 1, "instanceof Bar");
    CHECK(nabu_instanceof(o, "test/Foo") == 1, "instanceof Foo (super)");
    CHECK(nabu_instanceof(o, "java/lang/Object") == 1, "instanceof Object (super-super)");
    CHECK(nabu_instanceof(o, "test/Baz") == 0, "instanceof Baz == 0");

    /* compiled_type_name fallback. */
    CHECK(strcmp(nabu_compiled_type_name(o), "test/Bar") == 0, "compiled_type_name returns interpreted name");

    free(o);
    printf("\n%d failures\n", failures);
    return failures == 0 ? 0 : 1;
}
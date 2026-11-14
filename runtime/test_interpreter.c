#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nabu_interpreter.h"

static int failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "FAIL: %s\n", msg); failures++; } \
    else { printf("PASS: %s\n", msg); } \
} while (0)

int main(void) {
    nabu_interp_init();

    /* Methode "add(a,b) = a+b" */
    nabu_interp_method_t *add = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
    add->class_name = strdup("test/Math");
    add->name = strdup("add");
    add->desc = strdup("(II)I");
    add->max_locals = 4;
    add->max_stack = 4;
    unsigned char add_code[] = { 0x1a, 0x1b, 0x60, 0xac }; /* iload_0 iload_1 iadd ireturn */
    add->code = (unsigned char *)malloc(sizeof(add_code));
    memcpy(add->code, add_code, sizeof(add_code));
    add->code_len = sizeof(add_code);
    int add_id = nabu_interp_register_method(add);
    CHECK(add_id >= 0, "register add");

    /* Methode "factorial(n)" recursief. */
    /* factorial(0)=1; factorial(n)=n*factorial(n-1) */
    /* Bytecode:
       iload_0
       ifne L1 (offset?)
       iconst_1
       ireturn
       L1: iload_0
       iload_0
       iconst_1
       isub
       invokestatic factorial
       imul
       ireturn
     */
    nabu_interp_method_t *fact = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
    fact->class_name = strdup("test/Math");
    fact->name = strdup("factorial");
    fact->desc = strdup("(I)I");
    fact->max_locals = 4;
    fact->max_stack = 8;
    /* Build bytecode with placeholder for invokestatic index (filled after registration? need id first).
       We'll register a dummy first to get id, but id is assigned at register time.
       Strategy: build code with 0x0000 invokestatic, register, then patch? id known only after register.
       Instead, register fact first with empty code, then build code using fact->id, then set code. */
    fact->code = NULL;
    fact->code_len = 0;
    int fact_id = nabu_interp_register_method(fact);
    /* Now build bytecode. */
    unsigned char fc[64];
    int n = 0;
    fc[n++] = 0x1a;          /* iload_0 */
    fc[n++] = 0x9a;          /* ifne */
    /* offset to L1: will compute. placeholder 2 bytes */
    int ifne_pos = n; n += 2;
    fc[n++] = 0x04;          /* iconst_1 */
    fc[n++] = 0xac;          /* ireturn */
    /* L1: */
    int l1 = n;
    fc[n++] = 0x1a;          /* iload_0 */
    fc[n++] = 0x1a;          /* iload_0 */
    fc[n++] = 0x04;          /* iconst_1 */
    fc[n++] = 0x64;          /* isub */
    fc[n++] = 0xb8;          /* invokestatic */
    fc[n++] = (fact_id >> 8) & 0xff;
    fc[n++] = fact_id & 0xff;
    fc[n++] = 0x68;          /* imul */
    fc[n++] = 0xac;          /* ireturn */
    /* Fix ifne offset: target = ifne_opcode_addr + offset. ifne opcode at ifne_pos-2? 
       Let's compute: ifne opcode is at index (ifne_pos-2)? Actually we did fc[n++]=0x9a then n points to ifne_pos (operand start). 
       opcode address (relative) = ifne_pos - 1. operand at ifne_pos, ifne_pos+1. target L1. offset = L1 - (ifne_pos-1) = L1 - ifne_pos + 1. */
    int off = l1 - (ifne_pos - 1);
    fc[ifne_pos] = (off >> 8) & 0xff;
    fc[ifne_pos+1] = off & 0xff;
    fact->code = (unsigned char *)malloc(n);
    memcpy(fact->code, fc, n);
    fact->code_len = n;

    /* Methode "main" that calls factorial(5) and returns. */
    nabu_interp_method_t *mainm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
    mainm->class_name = strdup("test/Math");
    mainm->name = strdup("main");
    mainm->desc = strdup("()I");
    mainm->max_locals = 4;
    mainm->max_stack = 4;
    unsigned char main_code[16];
    int mn = 0;
    main_code[mn++] = 0x08;          /* iconst_5 */
    main_code[mn++] = 0xb8;          /* invokestatic */
    main_code[mn++] = (fact_id >> 8) & 0xff;
    main_code[mn++] = fact_id & 0xff;
    main_code[mn++] = 0xac;          /* ireturn */
    mainm->code = (unsigned char *)malloc(mn);
    memcpy(mainm->code, main_code, mn);
    mainm->code_len = mn;
    nabu_interp_register_method(mainm);

    /* Methode "add" test via invokestatic. */
    nabu_interp_method_t *main2 = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
    main2->class_name = strdup("test/Math");
    main2->name = strdup("main2");
    main2->desc = strdup("()I");
    main2->max_locals = 4;
    main2->max_stack = 8;
    unsigned char m2[16];
    int m2n = 0;
    m2[m2n++] = 0x06; /* iconst_3 */
    m2[m2n++] = 0x07; /* iconst_4 */
    m2[m2n++] = 0xb8; /* invokestatic add */
    m2[m2n++] = (add_id >> 8) & 0xff;
    m2[m2n++] = add_id & 0xff;
    m2[m2n++] = 0xac; /* ireturn */
    main2->code = (unsigned char *)malloc(m2n);
    memcpy(main2->code, m2, m2n);
    main2->code_len = m2n;
    nabu_interp_register_method(main2);

    /* Run main2 -> should be 7. */
    void *r2 = nabu_interp_invoke(main2, NULL, 0);
    CHECK((int)(intptr_t)r2 == 7, "main2 returns 3+4=7");

    /* Run main (factorial 5) -> 120. */
    void *r = nabu_interp_invoke(mainm, NULL, 0);
    CHECK((int)(intptr_t)r == 120, "factorial(5) == 120");

    /* Test control flow: if/else via a method. */
    nabu_interp_method_t *cmp = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
    cmp->class_name = strdup("test/Math");
    cmp->name = strdup("max");
    cmp->desc = strdup("(II)I");
    cmp->max_locals = 4;
    cmp->max_stack = 4;
    /* max(a,b): if a>b return a else return b */
    unsigned char cc[32];
    int cn = 0;
    cc[cn++] = 0x1a; /* iload_0 */
    cc[cn++] = 0x1b; /* iload_1 */
    cc[cn++] = 0xa3; /* if_icmpgt */
    int ifgt_pos = cn; cn += 2;
    cc[cn++] = 0x1b; /* iload_1 (else) */
    cc[cn++] = 0xac; /* ireturn */
    int else_target = cn;
    /* fix offset */
    int off2 = else_target - (ifgt_pos - 1);
    cc[ifgt_pos] = (off2 >> 8) & 0xff;
    cc[ifgt_pos+1] = off2 & 0xff;
    /* then branch: need to restructure. Actually above is wrong order. Let me redo. */
    /* Redo properly below by overwriting. */
    /* We'll instead build a simpler version: */
    cn = 0;
    cc[cn++] = 0x1a; /* iload_0 */
    cc[cn++] = 0x1b; /* iload_1 */
    cc[cn++] = 0xa3; /* if_icmpgt -> skip to 'then' (push a) ... hmm */
    /* Let me just do: if a > b goto THEN (return a) else return b */
    /* if_icmpgt pops a,b and branches if a>b. target THEN. */
    int ifgt_pos2 = cn; cn += 2;
    /* else: return b */
    cc[cn++] = 0x1b; /* iload_1 */
    cc[cn++] = 0xac; /* ireturn */
    int then_pos = cn;
    /* then: return a */
    cc[cn++] = 0x1a; /* iload_0 */
    cc[cn++] = 0xac; /* ireturn */
    int off3 = then_pos - (ifgt_pos2 - 1);
    cc[ifgt_pos2] = (off3 >> 8) & 0xff;
    cc[ifgt_pos2+1] = off3 & 0xff;
    cmp->code = (unsigned char *)malloc(cn);
    memcpy(cmp->code, cc, cn);
    cmp->code_len = cn;
    nabu_interp_register_method(cmp);

    void *args2[2];
    args2[0] = (void *)(intptr_t)10;
    args2[1] = (void *)(intptr_t)3;
    void *mx = nabu_interp_invoke(cmp, args2, 2);
    CHECK((int)(intptr_t)mx == 10, "max(10,3)==10");

    args2[0] = (void *)(intptr_t)2;
    args2[1] = (void *)(intptr_t)9;
    mx = nabu_interp_invoke(cmp, args2, 2);
    CHECK((int)(intptr_t)mx == 9, "max(2,9)==9");

    printf("\n%d failures\n", failures);
    return failures == 0 ? 0 : 1;
}
---
layout: page
title: LLVM Backend Internals
---

This page explains how the LLVM backend is built, for people who want to work on it. For using the backend, see the [LLVM backend](/backends/llvm/) page.

## Where the code lives

| Location | Content |
|---|---|
| `native-backend/…/backend/native_llvm/` | the backend: IR → LLVM IR → object file → executable |
| `compiler-api/…/backend/nativeimage/` | reading GraalVM reflection metadata (`reflect-config.json`, `reachability-metadata.json`, …) |
| `compiler/…/backend/bytecode/` | compiling JDK bytecode to IR (shared by any backend, used by the LLVM backend) |
| `compiler-api/…/backend/ir/` | the IR, IR generation from source, SSA and the optimizer |
| `runtime/nabu_runtime.c`, `nabu_runtime.h` | the C runtime: object model, exceptions, GC hooks, strings, reflection |
| `runtime/jcl_lite.c` | C implementations of the VM-bound parts of the class library |

The runtime sources are bundled into the plugin jar under `nabu/runtime/` and compiled into every executable at link time.

## Pipeline

`NativeLLVMBackend.compileAll` receives every IR module of the compilation:

1. **IR modules from source.** The frontend generates one module per class, as for the JVM backends.
2. **IR modules from library bytecode.** The backend reports `compilesLibraryBytecode()`, so `NabuCompiler` runs the `BytecodeReachabilityCompiler` first (see below) and adds the modules it produces. The JVM backends return `false` and skip this step.
3. **Reflection metadata.** `NativeImageConfigScanner` reads the GraalVM configuration under `META-INF/native-image/` from the output directory and from every directory and jar on the classpath into a `ReflectionRegistry`.
4. **LLVM module emission** (`LLVMModuleEmitter.emitAll`), in phases:
   1. function signatures for every module, so calls can be emitted in any order;
   2. ad-hoc declarations for call targets that no module defines;
   3. the object model (`ClassLayouts.registerAll`): struct layouts, type information, vtables and itables, parents before children;
   4. constant tables: string metadata, enum constants, constructors, reflection info (`ReflectionInfoEmitter`);
   5. module constructors (`@llvm.global_ctors`, see [Start-up](#start-up));
   6. function bodies (`FunctionEmitter`, `InstructionEmitter`).
5. **Unresolved symbols.** Every function that is still only declared gets a stub that prints the name and exits with code 127. The names are written to `<name>.unresolved.txt`.
6. **Validation and optimization.** The module is verified, optimized with LLVM's pass pipeline at the `--opt` level and written as `.ll` and `.o`.
7. **Linking** (`Linker`) with `gcc` or `clang`, compiling `nabu_runtime.c` and `jcl_lite.c` into the executable, plus `-lgc -lpthread` for `--gc=BOEHM`. This step only runs if there is a `main` function.

## Compiling the JDK from bytecode

The backend has no hand-written Java class library. Instead, `BytecodeReachabilityCompiler` compiles the **real bytecode** of everything the program can reach, from the JDK and from the libraries on the classpath:

- It reads class files from the JDK the compiler runs on (or `--bytecode-jdk-home`), and otherwise from the classpath, with ASM's tree API.
- It keeps a **worklist of methods**. It starts from every call in the source modules and from the classes in the GraalVM reflection configuration (their constructors and methods as the configuration lists them), lowers each method to IR (`BytecodeMethodLowering`), and adds the methods that method calls.
- For every class it touches it builds the **shape**: superclass, interfaces, fields and static fields. Static final constants get their `ConstantValue` attribute as initializer (`javac` does not initialize those in `<clinit>`).
- **Virtual calls** are the hard part: a call through `List.iterator()` can end up in any implementation. The compiler records every virtual signature called on a class or interface and lowers the matching override in every subtype it knows about, including when the subtype is loaded later. An implementation inherited from a superclass that does not implement the interface itself (for example `LinkedHashMap$LinkedKeyIterator`, whose `hasNext()` lives in the abstract `LinkedHashIterator`) counts too.
- **`invokedynamic`**: lambdas are turned into real classes ahead of time (`LambdaClassSynthesizer`, the equivalent of what `LambdaMetafactory` does at run time), and string concatenation with `StringConcatFactory` is lowered directly.
- Classes that are also compiled from source are skipped, so source always wins.
- Methods that cannot be compiled (`native` methods, signature-polymorphic methods such as `MethodHandle.invoke`, classes that do not exist) stay unresolved and end up in `<name>.unresolved.txt`.

### The role of `jcl_lite.c`

Some things cannot come from bytecode because the JVM itself implements them: `Class` objects, `System.arraycopy`, `Unsafe`, `Object.hashCode`, reflection objects, and so on. `jcl_lite.c` implements those in C and binds each implementation to the IR symbol name of the Java method with a `BIND(…, "java.lang.Class_getName", …)` macro (an `__asm__` label).

The policy is to keep `jcl_lite.c` **minimal and VM-bound**. If the JDK has bytecode for a method, compile the bytecode instead of adding a C version. C replacements tend to drift from the real behavior; for example, a C boxing helper created `Boolean` objects with the vtable of `Object`, so `toString()` printed `java.lang.Boolean@…`.

## Object model

Every object starts with a header that `ClassLayouts.objectHeaderType()` and `struct nabu_object` in `nabu_runtime.h` must agree on:

| Offset | Field | Meaning |
|---|---|---|
| 0 | `type` | pointer to the class's `nabu_type_info` (name, size, superclass, interfaces, fields) |
| 8 | `vtable` | virtual method table |
| 16 | `itable` | interface method table |
| 24 | `ref_count` | used by `--gc=REFCOUNT` |
| 28 | `monitor_count` | `synchronized` |
| 32 | `thread_id` | `synchronized` |

Fields follow the header.

- **vtables** have one slot per virtual method *and overload*. A subclass reuses its parent's slots for overrides and appends new ones. Private methods get no slot and are called directly.
- **itables** are indexed by a global interface-method slot number, so an interface call is a single load from the receiver's itable.
- **Function names** are `<owner>_<method>`, with an overload suffix (`$I32`, `$P5036ee31`, …) derived from the parameter types (`FnKeys`). Constructors are `<owner>_init`, static initializers `<owner>_<clinit>`.
- **Types.** References are `ptr`. `boolean` is `i1`; `byte`, `short` and `char` are `i8`/`i16`. `char` is marked *unsigned* in the IR (`IRType.CHAR`), so it widens with zero extension (JLS 5.1.2) while `short` sign-extends.
- **Strings** use the JDK's own `String` layout (a byte array plus a `coder`, Latin-1 or UTF-16), so compiled JDK code operates on them directly. String literals are emitted as constant `String` objects.

## Start-up

Module constructors in `@llvm.global_ctors` run before `main`:

1. `nabu_object_vtable_init` runs first (priority 101). It registers the vtable of `java.lang.Object` and the library itable (the runtime gives its own objects these tables), the vtable slot of `toString()` (for string concatenation with objects), the compiled `valueOf` method of each wrapper class (so the runtime creates real `Integer`/`Boolean` objects when it boxes), and the type table used by `Class.forName`.
2. The others (default priority) run in the order they were added: reflection registrations for the classes in the native-image configuration, `GC_init` (with `--gc=BOEHM`), the bootstrap initializer, and the static initializers. Each static initializer sits behind a `.clinit.once` guard so it runs at most once (JLS 12.4); most are also triggered lazily on first use.

## Exceptions

The backend emits LLVM `invoke`/`landingpad` instructions with the personality function **`nabu_seh_personality`**, and declares that function itself in the emitted IR. The runtime implements it: it reads the LSDA (from `.xdata` on Windows, `.gcc_except_table` on ELF) and installs the landing pad. `nabu_itanium_personality` does the same for the ELF/Itanium variant. Matching an exception against a `catch` type happens in the IR (`nabu_can_catch`), not in the personality.

On Windows x64 SEH, a catch clause that does not match must not use `_Unwind_Resume`: that jumps back into the same landing pad, the personality is not consulted again, and the program loops. `nabu_resume` therefore raises a fresh exception from the landing pad instead, so the search continues in an outer frame. An exception nobody catches ends with `abort()` and the message `nabu: unhandled exception`.

MSVC targets are rejected: they use SEH scope tables with `__C_specific_handler` instead of DWARF/LSDA, which would need a different code generation and unwind strategy.

## Garbage collection

See [Choosing a GC strategy](/backends/llvm/#choosing-a-gc-strategy) for the user view.

- **BOEHM**: every object, including unknown library classes, goes through `GC_malloc`; the runtime only fills the header (`nabu_init_object`). Fields the backend reads are written explicitly, because the test suite verifies against a GC double that poisons memory instead of zeroing it.
- **REFCOUNT**: one `nabu_retain` at allocation and one `nabu_release` when the owning reference dies (slot overwrite or function exit). Objects that escape the frame (return value, field, call argument, `throw`) are deliberately not counted and leak; cycles are not collected. See `RefCountEmitter`.
- **NONE**: objects are `alloca`'d in the frame of the function that creates them.

## Tests

- `native-backend` unit tests check the emitted IR and object model without linking.
- Tests named `*E2eIT` compile a small Java program with the LLVM backend, link it, run it and compare the output. They are skipped when no `gcc` is found (`NativeToolchain.assumeGcc`). Use them as the template for a new regression test: they take a few minutes each because they compile the JDK closure.

Running one test:

```bash
mvn test -pl native-backend -Dtest=BoxedToStringE2eIT -Dsurefire.failIfNoSpecifiedTests=false
```

Some of them (for example `BoxedToStringE2eIT`) accept `-Dnabu.keepExe=<path>.exe` to keep the executable and its `.ll` for inspection.

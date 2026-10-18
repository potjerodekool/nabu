---
layout: page
title: LLVM Troubleshooting
---

Common problems with the LLVM backend, and how to investigate a native executable that crashes or hangs.

## Build problems

### "Linking for an MSVC target is not supported yet"

Nabu did not find a MinGW `gcc` on Windows, so it fell back to LLVM's default MSVC target. Point Nabu at your MinGW `bin` directory:

```powershell
setx NABU_TOOLCHAIN_BIN "C:\programs\mingw64\mingw64\bin"
```

See [how Nabu finds GCC](/backends/llvm/installation/#4-tell-nabu-where-gcc-is).

### `gc.h: No such file or directory` or `cannot find -lgc`

The Boehm GC is missing for `--gc=BOEHM`. Install it as described in [Boehm GC (libgc)](/backends/llvm/installation/#3-boehm-gc-libgc) and check with `gcc -print-file-name=libgc.a`.

### `mvn install` fails on `native-backend.jar` being in use

A Maven daemon (`mvnd`) or an IDE still has the previous plugin jar in `~/.nabu/plugins` open. Stop the daemon (`mvnd --stop`) and build with plain Maven.

### The build takes very long

That is expected for now. The backend compiles every JDK class the program can reach (a few thousand) and LLVM optimizes the whole program as one module. A small program takes about a minute, the [picocli example](/examples/picocli-demo/) about one and a half. Almost all of that time is spent in the backend (LLVM code generation, optimization and linking); the frontend and the bytecode compilation take seconds. `--opt=NONE` turns off LLVM optimization, at the cost of a slower executable; how much build time it saves has not been measured.

## Problems when running the executable

### "call to a JDK function without an implementation: …" (exit code 127)

The program reached a function that the backend could not compile. All such functions are listed in `<name>.unresolved.txt` next to the executable.

- If a class is only **created or called through reflection** (`Class.forName`, `getDeclaredConstructor().newInstance()`), no code calls it directly, so the backend does not compile it. List it in a `reflect-config.json` under `META-INF/native-image/`, as you would for GraalVM `native-image`.
- If a **specific** function is missing, it is usually a `native` JDK method or a signature-polymorphic method (`MethodHandle.invoke…`) that needs support in the C runtime. See [Known limitations](/limitations/).

### "unhandled exception"

An exception reached the top of the program. The runtime prints the exception class and, for exceptions compiled from Java, the message:

```
nabu: unhandled exception
nabu: exception class: java/lang/IllegalStateException
nabu: message: …
```

then calls `abort()`.

### A `NullPointerException` you cannot place

Native code has no stack traces. Set `NABU_TRACE_NPE` to make the runtime print where each null check failed:

```powershell
$env:NABU_TRACE_NPE = "1"
.\greet.exe
# nabu: NullPointerException: <called method> in <calling function>
```

This is more reliable than a debugger for NPEs, because LLVM merges the null-check branches and the machine code no longer shows which call it was.

### "The code execution cannot proceed because libwinpthread-1.dll was not found"

Copy `libwinpthread-1.dll` from the MinGW `bin` directory next to the executable, or put that directory on the `PATH`.

### Colors in the output

Programs that decide on ANSI colors from the environment (picocli does) see `TERM=xterm` in Git Bash and turn colors on, even when output goes to a pipe. That is the program's behavior, not the backend's; the JVM version does the same. Run from PowerShell or `cmd`, or unset `TERM`.

## Debugging a crash on Windows

There is no `gdb` in a typical MinGW install, and LLDB needs extra Python libraries. The Windows debugger `cdb` (from the Windows SDK "Debugging Tools for Windows") works well together with `llvm-nm` (from an LLVM installation) to map addresses to function names.

1. **Run under cdb and get the faulting address and stack:**

   ```powershell
   cdb -G -c "g; r; k 30; q" C:\full\path\to\greet.exe --help
   ```

   Pass the executable as a **full Windows path**; a bare name fails with Win32 error 2. Some crashes only happen when the output is redirected, so also try `> out.txt 2>&1`. First-chance exceptions with code `0x20474343` are normal: that is how GCC/Nabu exceptions are thrown.

2. **Map addresses to functions.** The executable has an image base of `0x140000000`. List the symbols sorted by address:

   ```powershell
   llvm-nm -n greet.exe > syms.txt
   ```

   For an address `A` in a stack frame, the function is the last symbol in `syms.txt` with an address at or below `A` (an RVA `R` corresponds to `0x140000000 + R`).

3. **Breakpoints.** cdb breakpoints by module name (`greet+0x…`) do not resolve for these executables. Use a command file instead, and compute addresses from the entry point:

   ```
   g @$exentry
   r $t9 = @$exentry - 0x105F
   bp @$t9+0x5c67b7 "k 25; gc"
   g
   q
   ```

   `0x105F` is the `AddressOfEntryPoint` from `llvm-readobj --file-headers greet.exe`; `0x5c67b7` is the RVA of the function to stop in (from `syms.txt`). A breakpoint on `nabu_throw` with `k 25` shows where every exception is thrown. Pass the file with `cdb -cf commands.txt …`. In PowerShell, inline `-c` strings with quotes get mangled, so a command file is more reliable.

## Debugging a hang

Attach to the running process and dump all thread stacks:

```powershell
cdb -pv -p <pid> -c "~*k 30; q"
```

Symbolize the addresses as above. LLVM often turns self-recursion into a loop, so the stack can look flat; disassemble the looping function to see what it does:

```powershell
llvm-objdump -d --start-address=0x140xxxxxx --stop-address=0x140yyyyyy greet.exe
```

Kill the process afterwards: a runaway native program can use a lot of memory quickly.

## Reading the generated IR

The `<name>.ll` file next to the executable is the complete program in LLVM IR, after Nabu's own lowering but before LLVM optimization. It is large (the picocli example produces about 150 MB) but searchable:

- Functions are named `<owner>_<method>` (for example `CommandSpec_addMixin`, `java.util.HashSet_init`), with an overload suffix such as `$I32` or `$P5036ee31`.
- vtables are globals named `_ZNabu<class>Evtable`, type information `_ZNabu<class>Etype_info`.
- `grep -c undef <name>.ll` counts places where the backend had no value (for example fields of classes without a layout); a sudden increase points at a regression.

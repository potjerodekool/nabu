---
layout: page
title: Open world (experimental)
---

A native executable made by the [LLVM backend]({{ site.baseurl }}/backends/llvm/) is a **closed world**: it contains the classes the build could reach from `main`, and nothing else. A plugin that is dropped next to the program after the build cannot be in it.

The **open world** mode closes that gap in a small way. The runtime then also contains a **bytecode interpreter**. A class that was not part of the build, but that exists as a `.class` file or in a jar on a search path, is loaded at run time and executed by the interpreter.

<div class="callout callout-warning" markdown="1">
**Experimental, and narrow.** The bridge between the compiled program and the interpreter only passes `int` values: a compiled program can call a static `int method(int, int)` of a class it never saw. The interpreter has its own representation of strings and objects, not the object model of the compiled program, so objects do not cross the border. What the interpreter itself can run is described in [Bytecode interpreter and JIT]({{ site.baseurl }}/backends/llvm/interpreter/).
</div>

## Building a program with the interpreter

Set the system property `nabu.interpreter=true` in the JVM that runs the compiler. The linker then adds `nabu_interpreter.c` and `nabu_classfile.c` to the runtime of the executable. With `nabu-native-image` that is an option of the compiler's JVM:

```
NABU_JAVA_OPTS=-Dnabu.interpreter=true nabu-native-image -cp app.jar com.acme.Main
```

With Maven, put it in `MAVEN_OPTS` (or in the `argLine` of the build). Without the property nothing of this is linked, and `Class.forName` of a class that is not in the program throws `ClassNotFoundException`, as before.

## Calling a class that is not there at build time

The program declares a class with a `native` method that the runtime binds. The package and the name must be exactly these:

```java
package org.nabu.interp;

public class Interp {
    /** Calls the static method "int method(int, int)" of a class found on NABU_CLASSPATH. */
    public static native int callStaticInt(String className, String method, int a, int b);
}
```

```java
public class Main {
    public static void main(String[] args) throws Exception {
        // plugin.Adder was compiled separately and is not known to the build
        System.out.println(org.nabu.interp.Interp.callStaticInt("plugin.Adder", "combine", 5, 4));
        System.out.println(Class.forName("plugin.Adder").getName());
        try {
            Class.forName("plugin.Missing");
        } catch (ClassNotFoundException e) {
            System.out.println("missing");
        }
    }
}
```

At run time, tell the executable where to look:

```
NABU_CLASSPATH=/path/to/plugin-classes ./main      # Linux and macOS
set NABU_CLASSPATH=C:\path\to\plugin-classes & main.exe   # Windows: entries are separated by ;
```

`NABU_CLASSPATH` is a list of **directories and jars** (separator `:`, on Windows `;`). Classes are read from the directories and from the entries of the jars (stored or deflated; the runtime has its own zip reader). `META-INF/services/<interface>` files in directories and in jars are read for `ServiceLoader`.

What happens:

| Call | Result |
|---|---|
| `Class.forName("plugin.Adder")` | the class is loaded from `NABU_CLASSPATH` and the call returns its `Class` object |
| `Interp.callStaticInt(class, method, a, b)` | the class is loaded and the method is executed by the interpreter; the static initializer of the class runs at its first call |
| a name that is not in the program and not on `NABU_CLASSPATH` | `ClassNotFoundException` |
| a method that does not exist or has another signature | `NoSuchMethodException` |

The example is `OpenWorldInterpreterE2eIT` in the `native-backend` module: it prints `46`, `missing` and `plugin.Adder`.

## Limitations

- Only **static `(int, int) → int`** methods can be called from the compiled program. Strings, arrays and objects cannot be passed in or out.
- The interpreter runs the **program of the plugin**, not the JDK: for the JDK it has its own natives (`String`, `StringBuilder`, `Math`, `Integer`, collections and so on, see [the interpreter page]({{ site.baseurl }}/backends/llvm/interpreter/#the-jdk-in-the-interpreter)). Code that needs more of the JDK than that fails with `interp: unknown method …` on standard error.
- The [JIT]({{ site.baseurl }}/backends/llvm/interpreter/#the-jit) only exists on Linux and macOS, and only when `libnabu_jit` is found at run time.
- Interpreted code is much slower than compiled code (the JIT narrows that for numeric loops).

# picocli 4.7.6 source (test fixture)

`picocli/CommandLine.java` and `package-info.java` from `picocli-4.7.6-sources.jar`
(Apache License 2.0), used by the compiler tests that compile picocli's source with
Nabu's Java frontend (`PicocliVendorBatchTest`, `PicocliCliDemoJvmRunTest`,
`JavaCompilerVisitorTest`).

`CommandLine.java` is patched: the ASM backend does not yet produce bytecode as compact
as `javac`, and two methods would exceed the JVM's 64 KB method limit. Parts of them
are moved into new methods, without changing behavior:

- `Interpreter.registerBuiltInConverters` → `registerExtraConverters`;
- the optional-value prelude of `Interpreter.applyValueToSingleValuedField` →
  `selectSingleValuedPreamble`;
- its value conversion → `evaluateConvertedSingleValue`.

The native cli-demo does not use this source: the LLVM backend compiles picocli from
the bytecode in its jar.

# Phases
* [Parse](#parse-phase)
* [Enter](#enter-phase)
* [Resolve](#resolve-phase)
* [Check](#check-phase)
* [Lambda](#lambda-phase)
* [Lower](#lower-phase)
* [IR](#ir-phase)
* [Bytecode](#bytecode-phase)

# Parse phase
Parse source files.

# Enter phase
Enter the compilation units and add the classes to the symbol table
so they can be resolved later if they are referenced by other classes.

# Resolve phase
Resolve classes, methods, fields, etc.

# Transform phase
Run the transformers (io.github.potjerodekool.nabu.compiler.transform.CodeTransformer)
which are present on the classpath of the compiler.

After this phase is complete the resolve phase will be executed again.

# Check phase
Check the compilation units for unresolved symbols(methods, fields, etc.) and types.

# Lambda phase
Create methods for the lambda expressions

# Lower phase
Add additional code like:

* Generate code to init fields (both in constructor and client init).
* Generate code for enum and record classes.
* Add boxing and unboxing code.
* Add code to enhanced for statements.

# IR phase

Create intermediate representations.

# Bytecode phase

Generate bytecode for the JVM.
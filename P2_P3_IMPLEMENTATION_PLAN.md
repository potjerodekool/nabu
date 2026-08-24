# P2/P3 Implementation Plan

## P2 - Java 20 parity
1. Type patterns in switch labels: resolution, binding scoping, null/default handling, guarded patterns
2. Record patterns (decomposition + recursive patterns) behind a flag
3. Switch-expression exhaustiveness analysis
4. Strengthen generic-method inference with a targeted conformance test suite

## P3 - Platform & ecosystem
1. Annotation-processing interop: implement union/intersection mapping in ToNabuMapper
2. Module-mode compilation E2E test (compile a small multi-module project against real java.base jmod)
3. Decide the fate of JavaBackend and native LLVM backend
4. Grow the daemon/client into the incremental-build story

These require more extensive implementation beyond the scope of current capabilities.
package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.compiler.backend.Backend;
import io.github.potjerodekool.nabu.compiler.backend.CompileException;
import io.github.potjerodekool.nabu.compiler.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;

import java.io.File;
import java.io.IOException;
//import java.lang.classfile.ClassFile;
//import java.lang.constant.ClassDesc;
//import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;

public class JavaBackend implements Backend {
    @Override
    public void compile(final IRModule module,
                        final CompileOptions opts,
                        final Path output) throws CompileException {

        boolean helloWorld = false;

        final byte[] classBytes;

        if (helloWorld) {
            /*
            classBytes = ClassFile.of().build(ClassDesc.of("HelloWorld"), classBuilder -> {
                classBuilder.withMethod("main",
                        MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        methodBuilder -> {
                            methodBuilder.withCode(code -> {
                                code.getstatic(ClassDesc.of("java.lang.System"), "out", ClassDesc.of("java.io.PrintStream"));
                                code.ldc("Hello, World!");
                                code.invokevirtual(ClassDesc.of("java.io.PrintStream"), "println", MethodTypeDesc.of(
                                        ClassDesc.of("V"),
                                        ClassDesc.of("java.lang.String")));
                                code.return_();
                            });
                        });
            });*/
        } else {
/*
            classBytes = ClassFile.of().build(ClassDesc.of(module.name), classBuilder -> {

            });
            */
        }

        /*
        try {
            final var classFileName = module.name.replace('.', File.separatorChar) + ".class";
            final var path = output.resolve(classFileName);
            Files.write(path, classBytes);
        } catch (IOException e) {
            throw new CompileException("", e);
        }
        */

    }
}

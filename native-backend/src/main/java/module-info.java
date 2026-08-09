module io.github.potjerodekool.nabu.nativebackend {
    requires org.bytedeco.llvm;
    requires org.bytedeco.javacpp;
    requires io.github.potjerodekool.nabu.compiler;

    opens io.github.potjerodekool.nabu.compiler.backend.native_llvm to org.junit.platform.commons;
}
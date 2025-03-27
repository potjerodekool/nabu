package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOption;

public class ClassPathLocationHandler extends SimpleLocationHandler {

    public ClassPathLocationHandler() {
        super(StandardLocation.CLASS_PATH, CompilerOption.CLASS_PATH);
    }

}

package io.github.potjerodekool.nabu.compiler.io.impl;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.StandardLocation;

public class ClassPathLocationHandler extends SimpleLocationHandler {

    public ClassPathLocationHandler() {
        super(StandardLocation.CLASS_PATH, CompilerOption.CLASS_PATH);
    }

}

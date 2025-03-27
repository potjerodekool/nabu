package io.github.potjerodekool.nabu.compiler.io;

public enum StandardLocation implements FileManager.Location {

    SOURCE_OUTPUT,
    CLASS_OUTPUT,
    MODULE_SOURCE_PATH,
    UPGRADE_MODULE_PATH,
    SOURCE_PATH,
    CLASS_PATH,
    SYSTEM_MODULES,
    MODULE_PATH,
    NATIVE_HEADER_OUTPUT,
    ANNOTATION_PROCESSOR_MODULE_PATH,
    PATCH_MODULE_PATH;

    @Override
    public String getName() {
        return name();
    }

    @Override
    public boolean isOutputLocation() {
        return switch (this) {
            case CLASS_OUTPUT,
                 SOURCE_OUTPUT,
                NATIVE_HEADER_OUTPUT -> true;
            default -> false;
        };
    }

    @Override
    public boolean isModuleOrientedLocation() {
        return switch (this) {
            case MODULE_SOURCE_PATH,
                 ANNOTATION_PROCESSOR_MODULE_PATH,
                 UPGRADE_MODULE_PATH,
                 SYSTEM_MODULES,
                 MODULE_PATH,
                 PATCH_MODULE_PATH -> true;
            default -> false;
        };
    }
}

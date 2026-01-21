package io.github.potjerodekool.nabu.tools;

/**
 * Enumeration of standard locations for files.
 */
public enum StandardLocation implements FileManager.Location {

    /** Location for source output */
    SOURCE_OUTPUT,
    /** Location for class output */
    CLASS_OUTPUT,
    /** Location for modular source */
    MODULE_SOURCE_PATH,
    UPGRADE_MODULE_PATH,
    SOURCE_PATH,
    CLASS_PATH,
    SYSTEM_MODULES,
    MODULE_PATH,
    NATIVE_HEADER_OUTPUT,
    ANNOTATION_PROCESSOR_PATH,
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

    @Override
    public boolean isClassLocation() {
        return false;
    }

    @Override
    public boolean isSourceLocation() {
        return false;
    }
}

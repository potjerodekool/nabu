package io.github.potjerodekool.tools;

import com.electronwill.nightconfig.core.file.FileConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class ToolTest {

    @Test
    void test() {
        final var path = Paths.get("project.toml");
        try (var config = FileConfig.of(path)) {
            config.load();

            config.get("");

            System.out.println(config);
        }
    }
}
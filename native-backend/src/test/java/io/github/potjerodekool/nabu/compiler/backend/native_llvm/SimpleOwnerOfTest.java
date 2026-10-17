package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * De frontend noemt functies naar de simple eigenaar ({@code Ansi_init}),
 * maar een anonieme klasse naar haar volledige geneste naam
 * ({@code CommandLine$Help$ColorScheme$1_init}). Met alleen "1" vond de
 * call-site {@code picocli_CommandLine$Help$ColorScheme$1_init} geen definitie
 * en faalde het linken.
 */
class SimpleOwnerOfTest {

    @Test
    void memberClassUsesItsSimpleName() {
        assertEquals("Ansi", ClassLayouts.simpleOwnerOf("picocli/CommandLine$Help$Ansi"));
        assertEquals("Outer", ClassLayouts.simpleOwnerOf("mini/Outer"));
    }

    @Test
    void anonymousClassKeepsItsNestedName() {
        assertEquals("CommandLine$Help$ColorScheme$1",
                ClassLayouts.simpleOwnerOf("picocli/CommandLine$Help$ColorScheme$1"));
        assertEquals("CommandLine$3", ClassLayouts.simpleOwnerOf("picocli.CommandLine$3"));
    }
}

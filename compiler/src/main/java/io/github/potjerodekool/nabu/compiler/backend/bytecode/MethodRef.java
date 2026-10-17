package io.github.potjerodekool.nabu.compiler.backend.bytecode;

/**
 * Eén "deze methode moet gelowered worden"-taak voor
 * {@link BytecodeReachabilityCompiler}'s methode-niveau worklist.
 *
 * <p>{@code ownerInternalName} is de STATISCH gedeclareerde eigenaar zoals
 * die in de aanroepende instructie staat (bv. het {@code owner}-veld van een
 * ASM {@code MethodInsnNode}) - dit is NIET noodzakelijk de klasse die de
 * methode daadwerkelijk implementeert (bv. een aanroep op een interface- of
 * abstracte superklassetype); {@code BytecodeReachabilityCompiler} loopt
 * zelf de superklasse-keten af om de werkelijke implementatie te vinden,
 * exact zoals {@code ClassLayouts.resolveFunction} dat ook al doet voor de
 * Nabu-source-route.
 *
 * <p>{@code desc} is {@code null} voor de speciale "seed-klasse, alleen een
 * methodenaam bekend"-taak (zie {@code BytecodeReachabilityCompiler}'s
 * seed-expansie via scannen van reeds-gecompileerde Nabu-source-aanroepen,
 * die geen descriptor kennen omdat Nabu's eigen aanroepnaamconventie geen
 * descriptor-suffix gebruikt) - in dat geval worden ALLE overloads met die
 * naam in de eigenaarklasse gelowered. Anders is het de exacte JVM-
 * methodedescriptor van de aanroepende instructie.
 *
 * <p>{@code virtual} markeert of dit een INVOKEVIRTUAL/INVOKEINTERFACE-
 * aanroep was (dus override-propagatie naar subklassen nodig heeft) i.p.v.
 * INVOKESTATIC/INVOKESPECIAL (exacte, niet-virtuele binding).
 */
record MethodRef(String ownerInternalName, String name, String desc, boolean virtual) {
}

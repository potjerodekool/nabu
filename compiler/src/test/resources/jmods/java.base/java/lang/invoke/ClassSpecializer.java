package java.lang.invoke;

abstract class ClassSpecializer<T,K,S extends ClassSpecializer<T,K,S>.SpeciesData> {

    class Factory {

    }

    abstract class SpeciesData {

    }
}
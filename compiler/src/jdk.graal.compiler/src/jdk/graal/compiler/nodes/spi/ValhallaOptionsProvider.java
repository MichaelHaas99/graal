package jdk.graal.compiler.nodes.spi;

public interface ValhallaOptionsProvider {

    default boolean valhallaEnabled() {
        return false;
    }

    default boolean useArrayFlattening() {
        return false;
    }

    default boolean useFieldFlattening() {
        return false;
    }

    /**
     * Automatically disabled if EnableValhalla is disabled see.
     * {@code //Disable calling convention optimizations if inline types are not supported.} in
     * arguments.cpp
     */
    default boolean returnConventionEnabled() {
        return false;
    }

    default boolean callingConventionEnabled() {
        return false;
    }

    default boolean useACmpProfile() {
        return false;
    }

}

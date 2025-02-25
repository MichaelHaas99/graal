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

    default boolean returnCallingConventionEnabled() {
        return false;
    }

}

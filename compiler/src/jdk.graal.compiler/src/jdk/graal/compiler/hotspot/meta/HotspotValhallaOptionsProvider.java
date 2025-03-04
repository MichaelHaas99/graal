package jdk.graal.compiler.hotspot.meta;

import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;

public class HotspotValhallaOptionsProvider implements ValhallaOptionsProvider {

    private final boolean valhallaEnabled;
    private final boolean useArrayFlattening;
    private final boolean useFieldFlattening;
    private final boolean returnConventionEnabled;

    public HotspotValhallaOptionsProvider(GraalHotSpotVMConfig config) {
        valhallaEnabled = config.valhallaEnabled;
        useArrayFlattening = config.useArrayFlattening;
        useFieldFlattening = config.useFieldFlattening;
        returnConventionEnabled = config.returnConventionEnabled;
    }

    public HotspotValhallaOptionsProvider() {
        valhallaEnabled = false;
        useArrayFlattening = false;
        useFieldFlattening = false;
        returnConventionEnabled = false;
    }

    @Override
    public boolean valhallaEnabled() {
        return valhallaEnabled;
    }

    @Override
    public boolean useArrayFlattening() {
        return useArrayFlattening;
    }

    @Override
    public boolean useFieldFlattening() {
        return useFieldFlattening;
    }

    @Override
    public boolean returnCallingConventionEnabled() {
        return returnConventionEnabled;
    }
}

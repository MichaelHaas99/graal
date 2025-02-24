package jdk.graal.compiler.hotspot.meta;

import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;

public class HotspotValhallaOptionsProvider implements ValhallaOptionsProvider {

    private final boolean useArrayFlatteing;
    private final boolean useFieldFlattening;
    private final boolean returnConventionEnabled;

    public HotspotValhallaOptionsProvider(GraalHotSpotVMConfig config) {
        useArrayFlatteing = config.useArrayFlattening;
        useFieldFlattening = config.useFieldFlattening;
        returnConventionEnabled = config.returnConventionEnabled;
    }

    @Override
    public boolean useArrayFlattening() {
        return useArrayFlatteing;
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

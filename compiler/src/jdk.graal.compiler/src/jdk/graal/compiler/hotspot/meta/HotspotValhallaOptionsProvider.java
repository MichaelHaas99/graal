package jdk.graal.compiler.hotspot.meta;

import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;

public class HotspotValhallaOptionsProvider implements ValhallaOptionsProvider {

    private final boolean valhallaEnabled;
    private final boolean useArrayFlattening;
    private final boolean useFieldFlattening;
    private final boolean returnConventionEnabled;
    private final boolean callingConventionEnabled;
    private final boolean useACmpProfile;

    public HotspotValhallaOptionsProvider(GraalHotSpotVMConfig config) {
        valhallaEnabled = config.valhallaEnabled;
        useArrayFlattening = config.useArrayFlattening;
        useFieldFlattening = config.useFieldFlattening;
        returnConventionEnabled = config.returnConventionEnabled;
        callingConventionEnabled = config.callingConventionEnabled;
        useACmpProfile = config.useACmpProfile;
    }

    public HotspotValhallaOptionsProvider() {
        valhallaEnabled = false;
        useArrayFlattening = false;
        useFieldFlattening = false;
        returnConventionEnabled = false;
        callingConventionEnabled = false;
        useACmpProfile = false;
    }

    @Override
    public boolean valhallaEnabled() {
        return valhallaEnabled;
    }

    @Override
    public boolean useArrayFlattening() {
        return valhallaEnabled && useArrayFlattening;
    }

    @Override
    public boolean useFieldFlattening() {
        return valhallaEnabled && useFieldFlattening;
    }

    @Override
    public boolean returnConventionEnabled() {
        return returnConventionEnabled;
    }

    @Override
    public boolean callingConventionEnabled() {
        return callingConventionEnabled;
    }

    @Override
    public boolean useACmpProfile() {
        return useACmpProfile;
    }
}

package jdk.graal.compiler.lir.amd64;

import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class AMD64EntryPointFrameMap extends AMD64FrameMap {

    private final int stackIncrement;

    public AMD64EntryPointFrameMap(CodeCacheProvider codeCache, RegisterConfig registerConfig, ResolvedJavaMethod targetMethod, ReferenceMapBuilderFactory referenceMapFactory,
                    ValueKindFactory<?> valueKindFactory) {
        super(codeCache, registerConfig, referenceMapFactory, false);
        this.initialSpillSize = returnAddressSize();
        this.spillSize = initialSpillSize;

        JavaType[] parameterTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true).toArray(new JavaType[0]);
        CallingConvention callingConvention = registerConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes,
                        valueKindFactory);
        int spInc = (callingConvention.getStackSize() + getTarget().arch.getReturnAddressSize());
        int stackAlignment = getTarget().stackAlignment;
        stackIncrement = spInc % stackAlignment == 0 ? spInc : ((spInc / stackAlignment) + 1) * stackAlignment;

    }

    @Override
    public int offsetForStackSlot(StackSlot slot) {
        int returnAddressSize = getTarget().arch.getReturnAddressSize();
        if (slot.isOldArgument()) {
            return slot.getRawOffset() + stackIncrement + returnAddressSize;
        }
        if (slot.isNewArgument()) {
            return slot.getRawOffset() + returnAddressSize;
        }
        // the raw offset of the RA is -8, we want it to be at 0
        int offset = slot.getRawOffset() + initialSpillSize;
        return offset;
    }
}

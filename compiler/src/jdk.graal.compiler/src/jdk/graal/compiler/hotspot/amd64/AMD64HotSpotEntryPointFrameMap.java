package jdk.graal.compiler.hotspot.amd64;

import jdk.graal.compiler.hotspot.HotSpotEntryPointFrameMap;
import jdk.graal.compiler.lir.amd64.AMD64FrameMap;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 *
 * <pre>
 *   Base       Contents
 *
 *            :                                :
 *   caller   | incoming overflow argument n   |
 *   frame    :     ...                        :
 *            | incoming overflow argument 0   |
 *   ---------+--------------------------------+                             -----
 *            | return address                 |                               ^
 *            +--------------------------------+                               |
 *            :                                :                               |
 *            | incoming overflow argument n   |                               |
 *            :     ...                        :                               | oldArgumentsStartOffset
 *            | incoming overflow argument 0   |                               |
 *            +--------------------------------+    ^                          |
 *            | return address                 |    |  newArgumentsStartOffset |
 *    %sp--&gt;  +--------------------------------+---------------------------
 *
 * </pre>
 */
public class AMD64HotSpotEntryPointFrameMap extends AMD64FrameMap implements HotSpotEntryPointFrameMap {

    private final int stackIncrement;
    private final int oldArgumentsStartOffset;
    private final int newArgumentsStartOffset;

    public AMD64HotSpotEntryPointFrameMap(CodeCacheProvider codeCache, RegisterConfig registerConfig, ResolvedJavaMethod targetMethod, ReferenceMapBuilderFactory referenceMapFactory,
                    ValueKindFactory<?> valueKindFactory) {
        super(codeCache, registerConfig, referenceMapFactory, false);
        this.initialSpillSize = returnAddressSize();
        this.spillSize = initialSpillSize;

        JavaType[] parameterTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true).toArray(new JavaType[0]);
        CallingConvention callingConvention = registerConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes,
                        valueKindFactory);
        int returnAddressSize = getTarget().arch.getReturnAddressSize();
        int spInc = (callingConvention.getStackSize() + returnAddressSize);
        int stackAlignment = getTarget().stackAlignment;
        stackIncrement = spInc % stackAlignment == 0 ? spInc : ((spInc / stackAlignment) + 1) * stackAlignment;
        oldArgumentsStartOffset = stackIncrement + returnAddressSize;
        newArgumentsStartOffset = returnAddressSize;

    }

    @Override
    public int offsetForStackSlot(StackSlot slot) {
        return getOffsetForStackSlot(slot);
    }

    @Override
    public int getOldArgumentsStartOffset() {
        return oldArgumentsStartOffset;
    }

    @Override
    public int getNewArgumentsStartOffset() {
        return newArgumentsStartOffset;
    }
}

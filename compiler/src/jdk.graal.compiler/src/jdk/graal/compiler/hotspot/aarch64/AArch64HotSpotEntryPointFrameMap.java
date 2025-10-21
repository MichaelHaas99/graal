package jdk.graal.compiler.hotspot.aarch64;

import jdk.graal.compiler.hotspot.HotSpotEntryPointFrameMap;
import jdk.graal.compiler.lir.aarch64.AArch64FrameMap;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueKindFactory;
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
 *            | LR                             |                               ^
 *            | FP                             |                               |
 *            +--------------------------------+                               |
 *            :                                :                               |
 *            | incoming overflow argument n   |                               |
 *            :     ...                        :                               | oldArgumentsStartOffset
 *            | incoming overflow argument 0   |                               |
 *            +--------------------------------+    ^                          |
 *            | LR                             |    |  newArgumentsStartOffset |
 *            | FP                             |    |                          |
 *  %sp--&gt;  +--------------------------------+---------------------------
 *
 * </pre>
 */
public class AArch64HotSpotEntryPointFrameMap extends AArch64FrameMap implements HotSpotEntryPointFrameMap {

    private final int stackIncrement;
    private final int oldArgumentsStartOffset;
    private final int newArgumentsStartOffset;

    public AArch64HotSpotEntryPointFrameMap(CodeCacheProvider codeCache, RegisterConfig registerConfig, ResolvedJavaMethod targetMethod, ReferenceMapBuilderFactory referenceMapFactory,
                    ValueKindFactory<?> valueKindFactory) {
        super(codeCache, registerConfig, referenceMapFactory);
        this.initialSpillSize = getTarget().arch.getWordSize() * 2;
        this.spillSize = initialSpillSize;
        stackIncrement = HotSpotEntryPointFrameMap.getStackIncrement(targetMethod, registerConfig, getTarget(), valueKindFactory, initialSpillSize);
        oldArgumentsStartOffset = stackIncrement + initialSpillSize;
        newArgumentsStartOffset = initialSpillSize;

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

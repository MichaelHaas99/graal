package jdk.graal.compiler.hotspot;

import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public interface HotSpotEntryPointFrameMap {

    int getOldArgumentsStartOffset();

    int getNewArgumentsStartOffset();

    default int getOffsetForStackSlot(StackSlot slot) {
        if (slot.isOldArgument()) {
            return slot.getRawOffset() + getOldArgumentsStartOffset();
        }
        /*
         * The stack pointer does not point to the new arguments directly, we may e.g. have the
         * return address before. As the raw offset of the arguments is 0 we move everything up.
         *
         */
        return slot.getRawOffset() + getNewArgumentsStartOffset();
    }

    static int getStackIncrement(ResolvedJavaMethod targetMethod, RegisterConfig registerConfig, TargetDescription targetDescription, ValueKindFactory<?> valueKindFactory, int initialSpillSize) {
        JavaType[] parameterTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true).toArray(new JavaType[0]);
        CallingConvention callingConvention = registerConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes,
                        valueKindFactory);
        int spInc = (callingConvention.getStackSize() + initialSpillSize);
        int stackAlignment = targetDescription.stackAlignment;
        return spInc % stackAlignment == 0 ? spInc : ((spInc / stackAlignment) + 1) * stackAlignment;
    }
}

package jdk.graal.compiler.hotspot;

import jdk.vm.ci.code.StackSlot;

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
}

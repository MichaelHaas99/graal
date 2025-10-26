/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.hotspot;

import jdk.vm.ci.code.StackSlot;

public interface HotSpotEntryPointFrameMap extends HotSpotFrameMap {

    default boolean entryPointNeedsStackExtension() {
        return getStackIncrement() > 0;
    }

    int getOldArgumentsStartOffset();

    int getNewArgumentsStartOffset();

    int frameSize();

    default int getOffsetForStackSlot(StackSlot slot) {
        /*
         * The stack pointer does not point to the new arguments directly, we may e.g. have the
         * return address before. As the raw offset of the arguments is 0 we move everything up. We
         * also add the frame size as we may have some calls e.g. for gc barriers.
         *
         */
        if (slot.isOldArgument()) {
            return slot.getOffset(frameSize() + getOldArgumentsStartOffset());
        }

        if (slot.isNewArgument()) {
            return slot.getOffset(frameSize() + getNewArgumentsStartOffset());
        }

        return slot.getOffset(frameSize());
    }
}

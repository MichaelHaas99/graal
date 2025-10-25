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
package jdk.graal.compiler.hotspot.aarch64;

import jdk.graal.compiler.hotspot.HotSpotEntryPointFrameMap;
import jdk.graal.compiler.hotspot.HotSpotFrameMap;
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
 *            :     ...                        :                               | oldArgumentsStartOffset = stackIncrement
 *            | incoming overflow argument 0   | newArgumentsStartOffset = 0   |
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
        this.initialSpillSize = 0;
        this.spillSize = initialSpillSize;
        stackIncrement = HotSpotFrameMap.computeStackIncrement(targetMethod, registerConfig, getTarget(), valueKindFactory, initialSpillSize);
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

    @Override
    public int outgoingSize() {
        return outgoingSize;
    }

    @Override
    public int getStackIncrement() {
        return stackIncrement;
    }

    @Override
    public boolean frameLeaveNeedsStackRepair() {
        return false;
    }
}

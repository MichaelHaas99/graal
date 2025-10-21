/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
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

import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.hotspot.HotSpotFrameMap;
import jdk.graal.compiler.lir.aarch64.AArch64FrameMap;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class AArch64HotSpotFrameMap extends AArch64FrameMap implements HotSpotFrameMap {
    /**
     * The stack increment used for stack repair.
     */
    private StackSlot stackIncrementSlot;
    private final boolean frameLeaveNeedsStackRepair;
    private int stackIncrement;

    public AArch64HotSpotFrameMap(CodeCacheProvider codeCache, RegisterConfig registerConfig, ReferenceMapBuilderFactory referenceMapFactory, ResolvedJavaMethod targetMethod,
                    ValhallaOptionsProvider valhallaOptionsProvider, ValueKindFactory<?> valueKindFactory) {
        super(codeCache, registerConfig, referenceMapFactory);
        frameLeaveNeedsStackRepair = HotSpotFrameMap.checkFrameLeaveNeedsStackRepair(targetMethod, codeCache, valhallaOptionsProvider, valueKindFactory);
        if (frameLeaveNeedsStackRepair) {
            // stack increment needs to be located directly under rbp
            stackIncrementSlot = allocateSpillSlot(LIRKind.value(AArch64Kind.QWORD));
            stackIncrement = HotSpotFrameMap.computeStackIncrement(targetMethod, registerConfig, getTarget(), valueKindFactory,
                            initialSpillSize);
        }
    }

    public StackSlot getStackIncrementSlot() {
        assert stackIncrementSlot != null;
        return stackIncrementSlot;
    }

    @Override
    public int getStackIncrement() {
        return stackIncrement;
    }

    @Override
    public boolean frameLeaveNeedsStackRepair() {
        return frameLeaveNeedsStackRepair;
    }
}

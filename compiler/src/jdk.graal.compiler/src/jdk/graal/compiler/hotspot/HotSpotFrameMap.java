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

import static jdk.vm.ci.code.CodeUtil.getCallingConvention;

import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public interface HotSpotFrameMap {

    int getStackIncrement();

    static int computeStackIncrement(ResolvedJavaMethod targetMethod, RegisterConfig registerConfig, TargetDescription targetDescription, ValueKindFactory<?> valueKindFactory,
                    int preservedSlotsSize) {
        JavaType[] parameterTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true).toArray(new JavaType[0]);
        CallingConvention callingConvention = registerConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes,
                        valueKindFactory);
        int spInc = (callingConvention.getStackSize() + preservedSlotsSize);
        int stackAlignment = targetDescription.stackAlignment;
        return spInc % stackAlignment == 0 ? spInc : ((spInc / stackAlignment) + 1) * stackAlignment;
    }

    boolean frameLeaveNeedsStackRepair();

    static boolean checkFrameLeaveNeedsStackRepair(ResolvedJavaMethod targetMethod, CodeCacheProvider codeCache, ValhallaOptionsProvider valhallaOptionsProvider,
                    ValueKindFactory<?> valueKindFactory) {
        if (targetMethod == null || !valhallaOptionsProvider.callingConventionEnabled()) {
            return false;
        }
        CallingConvention cc = getCallingConvention(codeCache, HotSpotCallingConventionType.JavaCallee, targetMethod, valueKindFactory);
        CallingConvention ccScalarized = GraalValhallaServices.getValhallaCallingConvention(codeCache, HotSpotCallingConventionType.JavaCallee, targetMethod, valueKindFactory, true);
        CallingConvention ccScalarizedWithoutReceiver = GraalValhallaServices.getValhallaCallingConvention(codeCache, HotSpotCallingConventionType.JavaCallee, targetMethod, valueKindFactory, false);

        return ccScalarized.getStackSize() > cc.getStackSize() || ccScalarized.getStackSize() > ccScalarizedWithoutReceiver.getStackSize();
    }
}

/*
 * Copyright (c) 2007, 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.jtt.bytecode;

import java.util.EnumSet;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.options.OptionValues;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.internal.value.ValueClass;
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.meta.DeoptimizationReason;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class BC_aastore_flattened extends JTTTest {

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class Value0 {
        long l;
        int i;
        short s;
        byte b;

        Value0() {
            l = 0;
            i = 0;
            s = 0;
            b = 0;
        }

        Value0(long l0, int i0, short s0, byte b0) {
            l = l0;
            i = i0;
            s = s0;
            b = b0;
        }
    }

    static class Container0 {
        Value0 val;
        long l;
        int i;
    }

    // Container0 had a external null marker located just between two Java fields,
    // and test0 checks that updating the null marker doesn't corrupt
    // the surrounding fields and vice-versa.
    static Value0 test() {
        va[1] = new Value0(2,3,(short)4,(byte)5);
        return va[0];
    }

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);
    private static Value0[] va = (Value0[])ValueClass.newNullRestrictedArray(Value0.class, 2);
    //private static final Value0[] va = new Value0[2];


    @Test
    public void run0() throws Throwable {
        //va[1] = new Value0(1,1,(short)1,(byte)1);
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test");
    }

    @Test
    public void run1() throws Throwable {
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test");
    }

}

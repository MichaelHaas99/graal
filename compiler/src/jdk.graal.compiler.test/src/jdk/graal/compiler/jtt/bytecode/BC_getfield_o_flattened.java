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
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.meta.DeoptimizationReason;

@AddExports("java.base/jdk.internal.vm.annotation")
public class BC_getfield_o_flattened extends JTTTest {

    @ImplicitlyConstructible
    @LooselyConsistentValue
    public static value class MyValue2Inline {
        double d;
        long l;

        public MyValue2Inline(double d, long l) {
            this.d = d;
            this.l = l;
        }

        public static MyValue2Inline createDefault() {
            return new MyValue2Inline(3, 4);
        }

    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    public static value class MyValue2 {
        int x;
        byte y;
        @NullRestricted MyValue2Inline v;

        public MyValue2(int x, byte y, MyValue2Inline v) {
            this.x = x;
            this.y = y;
            this.v = v;
        }

        public static MyValue2 createDefaultInline() {
            return new MyValue2(1, (byte) 2, MyValue2Inline.createDefault());
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    public static value class MyValue1 {
        int x;
        long y;
        short z;
        Integer o;
        int[] oa;
        @NullRestricted MyValue2 v1;
        @NullRestricted MyValue2 v2;
        @NullRestricted MyValue2 v5;
        int c;

        public MyValue1(int x, long y, short z, Integer o, int[] oa, MyValue2 v1, MyValue2 v2, MyValue2 v5, int c) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.o = o;
            this.oa = oa;
            this.v1 = v1;
            this.v2 = v2;
            this.v5 = v5;
            this.c = c;
        }

        static MyValue1 createDefaultInline() {
            return new MyValue1(0, 0, (short) 0, null, null, MyValue2.createDefaultInline(), MyValue2.createDefaultInline(), MyValue2.createDefaultInline(), 0);
        }
    }

    public static long test(MyValue2 object) {
        return object.v.l;
    }

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);

    @Test
    public void run0() throws Throwable {
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test", MyValue2.createDefaultInline());
    }

    @Test
    public void run1() throws Throwable {
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test", MyValue2.createDefaultInline());
    }

}

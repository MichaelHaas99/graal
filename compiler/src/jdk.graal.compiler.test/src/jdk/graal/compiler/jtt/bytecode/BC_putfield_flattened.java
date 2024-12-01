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

/*
 */
@AddExports("java.base/jdk.internal.vm.annotation")
public class BC_putfield_flattened extends JTTTest {

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

    public static class MyValue2 {
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

    public static Object test(MyValue2 arg) {
        arg.v = new MyValue2Inline(4, 5);
        return arg.v;
    }

    static class MyObject {
        long val = Integer.MAX_VALUE;
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class ManyOops {
        MyObject o1 = new MyObject();
        MyObject o2 = new MyObject();
        MyObject o3 = new MyObject();
        MyObject o4 = new MyObject();

        long hash() {
            return o1.val + o2.val + o3.val + o4.val;
        }
    }

    static class ManyOopsWrapper{
        @NullRestricted ManyOops o1 = new ManyOops();
    }
    public static ManyOopsWrapper globalMany= new ManyOopsWrapper();

    public static Object test2() {
        ManyOops newValue = new ManyOops();
        globalMany.o1 = newValue;
        return globalMany;
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

    @Test
    public void run2() throws Throwable {
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test2");
    }

    @Test
    public void run3() throws Throwable {
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test2");
    }

}

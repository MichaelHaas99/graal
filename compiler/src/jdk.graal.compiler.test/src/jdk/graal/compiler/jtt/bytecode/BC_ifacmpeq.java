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

import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;

/*
 */
public class BC_ifacmpeq extends JTTTest {

    private static value class InlineTypeTestClass {
        final int x;
        final double y;
        final Object z;

        InlineTypeTestClass(int x, double y, Object z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        InlineTypeTestClass() {
            this(2, 3.0, null);
        }

        InlineTypeTestClass(Object z) {
            this(2, 3.0, z);
        }
    }

    public static boolean test(int arg) {
        InlineTypeTestClass inlineTypeObject1 = new InlineTypeTestClass();
        InlineTypeTestClass inlineTypeObject2 = new InlineTypeTestClass();
// InlineTypeTestClass inlineTypeObject3 = new InlineTypeTestClass(inlineTypeObject1);
// InlineTypeTestClass inlineTypeObject4 = new InlineTypeTestClass(inlineTypeObject1);
// InlineTypeTestClass inlineTypeObject5 = new InlineTypeTestClass(inlineTypeObject2);

// boolean result;
// if (arg == 0) {
// result = inlineTypeObject1 == inlineTypeObject2;
// } else if (arg == 1) {
// result = inlineTypeObject3 == inlineTypeObject4;
// } else {
// result = inlineTypeObject4 == inlineTypeObject5;
// }
// return result;
        return inlineTypeObject1 == inlineTypeObject2;
    }

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, GraalOptions.PrintProfilingInformation, true);

    @Test
    public void run0() throws Throwable {
        runTest(WITHOUT_PEA, "test", 0);
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", 0);
    }

    @Test
    public void run2() throws Throwable {
        runTest(WITHOUT_PEA, "test", 1);
    }

    @Test
    public void run3() throws Throwable {
        runTest("test", 1);
    }

    @Test
    public void run4() throws Throwable {
        runTest(WITHOUT_PEA, "test", 2);
    }

    @Test
    public void run5() throws Throwable {
        runTest("test", 2);
    }

}

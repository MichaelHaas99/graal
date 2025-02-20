package jdk.graal.compiler.valhalla;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.test.AddExports;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.EnumSet;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.phases.HighTier;
import jdk.graal.compiler.hotspot.replacements.HotspotSnippetsOptions;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.UseTrappingNullChecksPhase;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.vm.ci.meta.DeoptimizationReason;

@AddExports({"java.base/jdk.internal.vm.annotation", "java.base/jdk.internal.value"})
public class TestC2CCalls2 extends JTTTest {

    static value class OtherVal {
        public int x;

        private OtherVal(int x) {
            this.x = x;
        }
    }

    static interface MyInterface1 {
        public MyInterface1 test1(OtherVal other, int y);
        public MyInterface1 test2(OtherVal other1, OtherVal other2, int y);
        public MyInterface1 test3(OtherVal other1, OtherVal other2, int y, boolean deopt);
        public MyInterface1 test4(OtherVal other1, OtherVal other2, int y);
        public MyInterface1 test5(OtherVal other1, OtherVal other2, int y);
        public MyInterface1 test6();
        public MyInterface1 test7(int i1, int i2, int i3, int i4, int i5, int i6);
        public MyInterface1 test8(int i1, int i2, int i3, int i4, int i5, int i6, int i7);
        public MyInterface1 test9(MyValue3 other, int i1, int i2, int i3, int i4, int i5, int i6);
        public MyInterface1 test10(MyValue4 other, int i1, int i2, int i3, int i4, int i5, int i6);

        public int getValue();
    }

    static value class MyValue1 implements MyInterface1 {
        public int x;

        private MyValue1(int x) {
            this.x = x;
        }

        @Override
        public int getValue() {
            return x;
        }

        @Override
        public MyValue1 test1(OtherVal other, int y) {
            return new MyValue1(x + other.x + y);
        }

        @Override
        public MyValue1 test2(OtherVal other1, OtherVal other2, int y) {
            return new MyValue1(x + other1.x + other2.x + y);
        }

        @Override
        public MyValue1 test3(OtherVal other1, OtherVal other2, int y, boolean deopt) {
            if (!deopt) {
                return new MyValue1(x + other1.x + other2.x + y);
            } else {
                // Uncommon trap
                return test1(other1, y);
            }
        }

        @Override
        public MyValue1 test4(OtherVal other1, OtherVal other2, int y) {
            return new MyValue1(x + other1.x + other2.x + y);
        }

        @Override
        public MyValue1 test5(OtherVal other1, OtherVal other2, int y) {
            return new MyValue1(x + other1.x + other2.x + y);
        }

        @Override
        public MyValue1 test6() {
            return this;
        }

        @Override
        public MyValue1 test7(int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue1(x + i1 + i2 + i3 + i4 + i5 + i6);
        }

        @Override
        public MyValue1 test8(int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            return new MyValue1(x + i1 + i2 + i3 + i4 + i5 + i6 + i7);
        }

        public MyValue1 test9(MyValue3 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue1(x + (int)(other.d1 + other.d2 + other.d3 + other.d4) + i1 + i2 + i3 + i4 + i5 + i6);
        }

        public MyValue1 test10(MyValue4 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue1(x + other.x1 + other.x2 + other.x3 + other.x4 + i1 + i2 + i3 + i4 + i5 + i6);
        }
    }

    static value class MyValue2 implements MyInterface1 {
        public int x;

        private MyValue2(int x) {
            this.x = x;
        }

        @Override
        public int getValue() {
            return x;
        }

        @Override
        public MyValue2 test1(OtherVal other, int y) {
            return new MyValue2(x + other.x + y);
        }

        @Override
        public MyValue2 test2(OtherVal other1, OtherVal other2, int y) {
            return new MyValue2(x + other1.x + other2.x + y);
        }

        @Override
        public MyValue2 test3(OtherVal other1, OtherVal other2, int y, boolean deopt) {
            if (!deopt) {
                return new MyValue2(x + other1.x + other2.x + y);
            } else {
                // Uncommon trap
                return test1(other1, y);
            }
        }

        @Override
        public MyValue2 test4(OtherVal other1, OtherVal other2, int y) {
            return new MyValue2(x + other1.x + other2.x + y);
        }

        @Override
        public MyValue2 test5(OtherVal other1, OtherVal other2, int y) {
            return new MyValue2(x + other1.x + other2.x + y);
        }

        @Override
        public MyValue2 test6() {
            return this;
        }

        @Override
        public MyValue2 test7(int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue2(x + i1 + i2 + i3 + i4 + i5 + i6);
        }

        @Override
        public MyValue2 test8(int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            return new MyValue2(x + i1 + i2 + i3 + i4 + i5 + i6 + i7);
        }

        public MyValue2 test9(MyValue3 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue2(x + (int)(other.d1 + other.d2 + other.d3 + other.d4) + i1 + i2 + i3 + i4 + i5 + i6);
        }

        public MyValue2 test10(MyValue4 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue2(x + other.x1 + other.x2 + other.x3 + other.x4 + i1 + i2 + i3 + i4 + i5 + i6);
        }
    }

    static value class MyValue3 implements MyInterface1 {
        public double d1;
        public double d2;
        public double d3;
        public double d4;

        private MyValue3(double d) {
            this.d1 = d;
            this.d2 = d;
            this.d3 = d;
            this.d4 = d;
        }

        @Override
        public int getValue() {
            return (int)d4;
        }

        @Override
        public MyValue3 test1(OtherVal other, int y) { return new MyValue3(0); }
        @Override
        public MyValue3 test2(OtherVal other1, OtherVal other2, int y)  { return new MyValue3(0); }
        @Override
        public MyValue3 test3(OtherVal other1, OtherVal other2, int y, boolean deopt)  { return new MyValue3(0); }
        @Override
        public MyValue3 test4(OtherVal other1, OtherVal other2, int y)  { return new MyValue3(0); }
        @Override
        public MyValue3 test5(OtherVal other1, OtherVal other2, int y)  { return new MyValue3(0); }
        @Override
        public MyValue3 test6()  { return new MyValue3(0); }

        @Override
        public MyValue3 test7(int i1, int i2, int i3, int i4, int i5, int i6)  {
            return new MyValue3(d1 + d2 + d3 + d4 + i1 + i2 + i3 + i4 + i5 + i6);
        }

        @Override
        public MyValue3 test8(int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            return new MyValue3(d1 + d2 + d3 + d4 + i1 + i2 + i3 + i4 + i5 + i6 + i7);
        }

        public MyValue3 test9(MyValue3 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue3(d1 + d2 + d3 + d4 + other.d1 + other.d2 + other.d3 + other.d4 + i1 + i2 + i3 + i4 + i5 + i6);
        }

        public MyValue3 test10(MyValue4 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue3(d1 + d2 + d3 + d4 + other.x1 + other.x2 + other.x3 + other.x4 + i1 + i2 + i3 + i4 + i5 + i6);
        }
    }

    static value class MyValue4 implements MyInterface1 {
        public int x1;
        public int x2;
        public int x3;
        public int x4;

        private MyValue4(int i) {
            this.x1 = i;
            this.x2 = i;
            this.x3 = i;
            this.x4 = i;
        }

        @Override
        public int getValue() {
            return x4;
        }

        @Override
        public MyValue4 test1(OtherVal other, int y) { return new MyValue4(0); }
        @Override
        public MyValue4 test2(OtherVal other1, OtherVal other2, int y)  { return new MyValue4(0); }
        @Override
        public MyValue4 test3(OtherVal other1, OtherVal other2, int y, boolean deopt)  { return new MyValue4(0); }
        @Override
        public MyValue4 test4(OtherVal other1, OtherVal other2, int y)  { return new MyValue4(0); }
        @Override
        public MyValue4 test5(OtherVal other1, OtherVal other2, int y)  { return new MyValue4(0); }
        @Override
        public MyValue4 test6()  { return new MyValue4(0); }

        @Override
        public MyValue4 test7(int i1, int i2, int i3, int i4, int i5, int i6)  {
            return new MyValue4(x1 + x2 + x3 + x4 + i1 + i2 + i3 + i4 + i5 + i6);
        }

        @Override
        public MyValue4 test8(int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            return new MyValue4(x1 + x2 + x3 + x4 + i1 + i2 + i3 + i4 + i5 + i6 + i7);
        }

        public MyValue4 test9(MyValue3 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue4(x1 + x2 + x3 + x4 + (int)(other.d1 + other.d2 + other.d3 + other.d4) + i1 + i2 + i3 + i4 + i5 + i6);
        }

        public MyValue4 test10(MyValue4 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyValue4(x1 + x2 + x3 + x4 + other.x1 + other.x2 + other.x3 + other.x4 + i1 + i2 + i3 + i4 + i5 + i6);
        }
    }

    static class MyObject implements MyInterface1 {
        private final int x;

        private MyObject(int x) {
            this.x = x;
        }

        @Override
        public int getValue() {
            return x;
        }

        @Override
        public MyObject test1(OtherVal other, int y) {
            return new MyObject(x + other.x + y);
        }

        @Override
        public MyObject test2(OtherVal other1, OtherVal other2, int y) {
            return new MyObject(x + other1.x + other2.x + y);
        }

        @Override
        public MyObject test3(OtherVal other1, OtherVal other2, int y, boolean deopt) {
            if (!deopt) {
                return new MyObject(x + other1.x + other2.x + y);
            } else {
                // Uncommon trap
                return test1(other1, y);
            }
        }

        @Override
        public MyObject test4(OtherVal other1, OtherVal other2, int y) {
            return new MyObject(x + other1.x + other2.x + y);
        }

        @Override
        public MyObject test5(OtherVal other1, OtherVal other2, int y) {
            return new MyObject(x + other1.x + other2.x + y);
        }

        @Override
        public MyObject test6() {
            return this;
        }

        @Override
        public MyObject test7(int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyObject(x + i1 + i2 + i3 + i4 + i5 + i6);
        }

        @Override
        public MyObject test8(int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            return new MyObject(x + i1 + i2 + i3 + i4 + i5 + i6 + i7);
        }

        public MyObject test9(MyValue3 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyObject(x + (int)(other.d1 + other.d2 + other.d3 + other.d4) + i1 + i2 + i3 + i4 + i5 + i6);
        }

        public MyObject test10(MyValue4 other, int i1, int i2, int i3, int i4, int i5, int i6) {
            return new MyObject(x + other.x1 + other.x2 + other.x3 + other.x4 + i1 + i2 + i3 + i4 + i5 + i6);
        }
    }

    // Test calling methods with value class arguments through an interface
    public static int test1(MyInterface1 intf, OtherVal other, int y) {
        return intf.test1(other, y).getValue();
    }

    public static int test2(MyInterface1 intf, OtherVal other, int y) {
        return intf.test2(other, other, y).getValue();
    }

    // Test mixing null-tolerant and null-free value class arguments
    public static int test3(MyValue1 vt, OtherVal other, int y) {
        return vt.test2(other, other, y).getValue();
    }

    public static int test4(MyObject obj, OtherVal other, int y) {
        return obj.test2(other, other, y).getValue();
    }

    // Optimized interface call with value class receiver
    public static int test5(MyInterface1 intf, OtherVal other, int y) {
        return intf.test1(other, y).getValue();
    }

    public static int test6(MyInterface1 intf, OtherVal other, int y) {
        return intf.test2(other, other, y).getValue();
    }

    // Optimized interface call with object receiver
    public static int test7(MyInterface1 intf, OtherVal other, int y) {
        return intf.test1(other, y).getValue();
    }

    public static int test8(MyInterface1 intf, OtherVal other, int y) {
        return intf.test2(other, other, y).getValue();
    }

    // Interface calls with deoptimized callee
    public static int test9(MyInterface1 intf, OtherVal other, int y, boolean deopt) {
        return intf.test3(other, other, y, deopt).getValue();
    }

    public static int test10(MyInterface1 intf, OtherVal other, int y, boolean deopt) {
        return intf.test3(other, other, y, deopt).getValue();
    }

    // Optimized interface calls with deoptimized callee
    public static int test11(MyInterface1 intf, OtherVal other, int y, boolean deopt) {
        return intf.test3(other, other, y, deopt).getValue();
    }

    public static int test12(MyInterface1 intf, OtherVal other, int y, boolean deopt) {
        return intf.test3(other, other, y, deopt).getValue();
    }

    public static int test13(MyInterface1 intf, OtherVal other, int y, boolean deopt) {
        return intf.test3(other, other, y, deopt).getValue();
    }

    public static int test14(MyInterface1 intf, OtherVal other, int y, boolean deopt) {
        return intf.test3(other, other, y, deopt).getValue();
    }

    // Interface calls without warmed up / compiled callees
    public static int test15(MyInterface1 intf, OtherVal other, int y) {
        return intf.test4(other, other, y).getValue();
    }

    public static int test16(MyInterface1 intf, OtherVal other, int y) {
        return intf.test5(other, other, y).getValue();
    }

    // Interface call with no arguments
    public static int test17(MyInterface1 intf) {
        return intf.test6().getValue();
    }

    // Calls that require stack extension
    public static int test18(MyInterface1 intf, int y) {
        return intf.test7(y, y, y, y, y, y).getValue();
    }

    public static int test19(MyInterface1 intf, int y) {
        return intf.test8(y, y, y, y, y, y, y).getValue();
    }

    public static int test20(MyInterface1 intf, MyValue3 v, int y) {
        return intf.test9(v, y, y, y, y, y, y).getValue();
    }

    public static int test21(MyInterface1 intf, MyValue4 v, int y) {
        return intf.test10(v, y, y, y, y, y, y).getValue();
    }
    public static final int rI = 1;

    @Test
    public void run0() throws InvalidInstalledCodeException {
        // Sometimes, exclude some methods from compilation with C2 to stress test the calling convention


        MyValue1 val1 = new MyValue1(rI);
        MyValue2 val2 = new MyValue2(rI+1);
        MyValue3 val3 = new MyValue3(rI+2);
        MyValue4 val4 = new MyValue4(rI+3);
        OtherVal other = new OtherVal(rI+4);
        MyObject obj = new MyObject(rI+5);

        // Make sure callee methods are compiled
        for (int i = 0; i < 10; ++i) {
            getCode(getResolvedJavaMethod(MyValue1.class, "test1"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod(MyValue2.class, "test1"), null, false, true, getInitialOptions()).executeVarargs(val2, other, rI);
            getCode(getResolvedJavaMethod(MyObject.class, "test1"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);

            getCode(getResolvedJavaMethod(MyValue1.class, "test2"), null, false, true, getInitialOptions()).executeVarargs(val1, other, other, rI);
            getCode(getResolvedJavaMethod(MyValue2.class, "test2"), null, false, true, getInitialOptions()).executeVarargs(val2, other, other, rI);
            getCode(getResolvedJavaMethod(MyObject.class, "test2"), null, false, true, getInitialOptions()).executeVarargs(obj, other, other, rI);

            getCode(getResolvedJavaMethod(MyValue1.class, "test3"), null, false, true, getInitialOptions()).executeVarargs(val1, other, other, rI, false);
            getCode(getResolvedJavaMethod(MyValue2.class, "test3"), null, false, true, getInitialOptions()).executeVarargs(val2, other, other, rI, false);
            getCode(getResolvedJavaMethod(MyObject.class, "test3"), null, false, true, getInitialOptions()).executeVarargs(obj, other, other, rI, false);

            getCode(getResolvedJavaMethod(MyValue1.class, "test7"), null, false, true, getInitialOptions()).executeVarargs(val1, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue2.class, "test7"), null, false, true, getInitialOptions()).executeVarargs(val2, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue3.class, "test7"), null, false, true, getInitialOptions()).executeVarargs(val3, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue4.class, "test7"), null, false, true, getInitialOptions()).executeVarargs(val4, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyObject.class, "test7"), null, false, true, getInitialOptions()).executeVarargs(obj, rI, rI, rI, rI, rI, rI);

            getCode(getResolvedJavaMethod(MyValue1.class, "test8"), null, false, true, getInitialOptions()).executeVarargs(val1, rI, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue2.class, "test8"), null, false, true, getInitialOptions()).executeVarargs(val2, rI, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue3.class, "test8"), null, false, true, getInitialOptions()).executeVarargs(val3, rI, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue4.class, "test8"), null, false, true, getInitialOptions()).executeVarargs(val4, rI, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyObject.class, "test8"), null, false, true, getInitialOptions()).executeVarargs(obj, rI, rI, rI, rI, rI, rI, rI);

            getCode(getResolvedJavaMethod(MyValue1.class, "test9"), null, false, true, getInitialOptions()).executeVarargs(val1, val3, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue2.class, "test9"), null, false, true, getInitialOptions()).executeVarargs(val2, val3, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue3.class, "test9"), null, false, true, getInitialOptions()).executeVarargs(val3, val3, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue4.class, "test9"), null, false, true, getInitialOptions()).executeVarargs(val4, val3, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyObject.class, "test9"), null, false, true, getInitialOptions()).executeVarargs(obj, val3, rI, rI, rI, rI, rI, rI);

            getCode(getResolvedJavaMethod(MyValue1.class, "test10"), null, false, true, getInitialOptions()).executeVarargs(val1, val4, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue2.class, "test10"), null, false, true, getInitialOptions()).executeVarargs(val2, val4, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue3.class, "test10"), null, false, true, getInitialOptions()).executeVarargs(val3, val4, rI, rI, rI, rI, rI, rI);
            getCode(getResolvedJavaMethod(MyValue4.class, "test10"), null, false, true, getInitialOptions()).executeVarargs(val4, val4, rI, rI, rI, rI, rI, rI);
            //test(getResolvedJavaMethod(MyObject.class, "test10"), obj, val4, rI, rI, rI, rI, rI, rI);
        }

        // Polute call profile
        for (int i = 0; i < 10; ++i) {
            getCode(getResolvedJavaMethod("test15"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod("test16"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test17"), null, false, true, getInitialOptions()).executeVarargs(obj);
        }

        // Trigger compilation of caller methods
        for (int i = 0; i < 10; ++i) {
            val1 = new MyValue1(rI+i);
            val2 = new MyValue2(rI+i+1);
            val3 = new MyValue3(rI+i+2);
            val4 = new MyValue4(rI+i+3);
            other = new OtherVal(rI+i+4);
            obj = new MyObject(rI+i+5);

            getCode(getResolvedJavaMethod("test1"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test2"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test2"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod("test3"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod("test4"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test5"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod("test6"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod("test7"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test8"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test9"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI, false);
            getCode(getResolvedJavaMethod("test9"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI, false);
            getCode(getResolvedJavaMethod("test10"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI, false);
            getCode(getResolvedJavaMethod("test10"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI, false);
            getCode(getResolvedJavaMethod("test11"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI, false);
            getCode(getResolvedJavaMethod("test12"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI, false);
            getCode(getResolvedJavaMethod("test13"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI, false);
            getCode(getResolvedJavaMethod("test14"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI, false);
            getCode(getResolvedJavaMethod("test15"), null, false, true, getInitialOptions()).executeVarargs(obj, other, rI);
            getCode(getResolvedJavaMethod("test16"), null, false, true, getInitialOptions()).executeVarargs(val1, other, rI);
            getCode(getResolvedJavaMethod("test17"), null, false, true, getInitialOptions()).executeVarargs(val1);
            getCode(getResolvedJavaMethod("test18"), null, false, true, getInitialOptions()).executeVarargs(val1, rI);
            getCode(getResolvedJavaMethod("test18"), null, false, true, getInitialOptions()).executeVarargs(val2, rI);
            getCode(getResolvedJavaMethod("test18"), null, false, true, getInitialOptions()).executeVarargs(val3, rI);
            getCode(getResolvedJavaMethod("test18"), null, false, true, getInitialOptions()).executeVarargs(val4, rI);
            getCode(getResolvedJavaMethod("test18"), null, false, true, getInitialOptions()).executeVarargs(obj, rI);
            getCode(getResolvedJavaMethod("test19"), null, false, true, getInitialOptions()).executeVarargs(val1, rI);
            getCode(getResolvedJavaMethod("test19"), null, false, true, getInitialOptions()).executeVarargs(val2, rI);
            getCode(getResolvedJavaMethod("test19"), null, false, true, getInitialOptions()).executeVarargs(val3, rI);
            getCode(getResolvedJavaMethod("test19"), null, false, true, getInitialOptions()).executeVarargs(val4, rI);
            getCode(getResolvedJavaMethod("test19"), null, false, true, getInitialOptions()).executeVarargs(obj, rI);
            getCode(getResolvedJavaMethod("test20"), null, false, true, getInitialOptions()).executeVarargs(val1, val3, rI);
            getCode(getResolvedJavaMethod("test20"), null, false, true, getInitialOptions()).executeVarargs(val2, val3, rI);
            getCode(getResolvedJavaMethod("test20"), null, false, true, getInitialOptions()).executeVarargs(val3, val3, rI);
            getCode(getResolvedJavaMethod("test20"), null, false, true, getInitialOptions()).executeVarargs(val4, val3, rI);
            getCode(getResolvedJavaMethod("test20"), null, false, true, getInitialOptions()).executeVarargs(obj, val3, rI);
            getCode(getResolvedJavaMethod("test21"), null, false, true, getInitialOptions()).executeVarargs(val1, val4, rI);
            getCode(getResolvedJavaMethod("test21"), null, false, true, getInitialOptions()).executeVarargs(val2, val4, rI);
            getCode(getResolvedJavaMethod("test21"), null, false, true, getInitialOptions()).executeVarargs(val3, val4, rI);
            getCode(getResolvedJavaMethod("test21"), null, false, true, getInitialOptions()).executeVarargs(val4, val4, rI);
            getCode(getResolvedJavaMethod("test21"), null, false, true, getInitialOptions()).executeVarargs(obj, val4, rI);
        }

        // Trigger deoptimization
        Assert.assertEquals(val1.test3(other, other, rI, true).getValue(), val1.x + other.x + rI);
        Assert.assertEquals(obj.test3(other, other, rI, true).getValue(), obj.x + other.x + rI);

        // Check results of methods still calling the deoptimized methods
        Assert.assertEquals(test9(val1, other, rI, false), val1.x + 2*other.x + rI);
        Assert.assertEquals(test9(obj, other, rI, false), obj.x + 2*other.x + rI);
        Assert.assertEquals(test10(obj, other, rI, false), obj.x + 2*other.x + rI);
        Assert.assertEquals(test10(val1, other, rI, false), val1.x + 2*other.x + rI);
        Assert.assertEquals(test11(val1, other, rI, false), val1.x + 2*other.x + rI);
        Assert.assertEquals(test11(obj, other, rI, false), obj.x + 2*other.x + rI);
        Assert.assertEquals(test12(obj, other, rI, false), obj.x + 2*other.x + rI);
        Assert.assertEquals(test12(val1, other, rI, false), val1.x + 2*other.x + rI);
        Assert.assertEquals(test13(val1, other, rI, false), val1.x + 2*other.x + rI);
        Assert.assertEquals(test13(obj, other, rI, false), obj.x + 2*other.x + rI);
        Assert.assertEquals(test14(obj, other, rI, false), obj.x + 2*other.x + rI);
        Assert.assertEquals(test14(val1, other, rI, false), val1.x + 2*other.x + rI);

        // Check with unexpected arguments
        Assert.assertEquals(test1(val2, other, rI), val2.x + other.x + rI);
        Assert.assertEquals(test2(val2, other, rI), val2.x + 2*other.x + rI);
        Assert.assertEquals(test5(val2, other, rI), val2.x + other.x + rI);
        Assert.assertEquals(test6(val2, other, rI), val2.x + 2*other.x + rI);
        Assert.assertEquals(test7(val1, other, rI), val1.x + other.x + rI);
        Assert.assertEquals(test8(val1, other, rI), val1.x + 2*other.x + rI);
        Assert.assertEquals(test15(val1, other, rI), val1.x + 2*other.x + rI);
        Assert.assertEquals(test16(obj, other, rI), obj.x + 2*other.x + rI);
        Assert.assertEquals(test17(obj), obj.x);

//        runTest("test20", val1, val3, rI);
//        runTest("test20", val2, val3, rI);
//        runTest("test20", val3, val3, rI);
//        runTest("test20", val4, val3, rI);
        //runTest("test20", obj, val3, rI);
        //runTest("test11", val1, other, rI, false);
        //test(getResolvedJavaMethod(TestC2CCalls.MyValue1.class, "test8"), val1, rI, rI, rI, rI, rI, rI, rI );
        //runTest("test11", obj, other, rI, false);
        //runTest("test1", val2, other, rI);
        //runTest("test3",val1, other, rI);
        //test(getResolvedJavaMethod(TestC2CCalls.MyValue2.class, "test10"), val2,val4, rI, rI, rI, rI, rI, rI );
        //test(getResolvedJavaMethod(TestC2CCalls.MyValue1.class, "test1"),val1, other, rI);
        //test(getResolvedJavaMethod(TestC2CCalls.MyValue2.class, "test2"),val2, other, other, rI);
        //test(getResolvedJavaMethod(TestC2CCalls.MyValue3.class, "test7"), val3, rI, rI, rI, rI, rI, rI );
        runTest("test10", val1, other, rI, false);
        //getCode(getResolvedJavaMethod("test10"));

    }

    public static void testMethodHandle(MethodHandle incrementAndCheck_mh, MyInterface1 object){

        try {
//            MethodHandles.Lookup lookup = MethodHandles.lookup();
//            MethodType mt_1 = MethodType.methodType(MyInterface1.class, OtherVal.class, int.class);
//            MethodHandle incrementAndCheck_mh_1 = lookup.findVirtual(MyValue1.class, "test1", mt_1);
//            MyValue1 v = (MyValue1) incrementAndCheck_mh_1.invokeExact(object, new OtherVal(3), 3);
//            Class<?> clazz = MyValue1.class;
            Assert.assertEquals(incrementAndCheck_mh.invoke(object, new OtherVal(3), 3), new MyValue1(9));

//            MethodType mt_2 = MethodType.methodType(MyValue2.class);
//            MethodHandle incrementAndCheck_mh_2 = lookup.findVirtual(clazz, "test1", mt_2);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
            throw new RuntimeException("Method handle lookup failed");
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void run1() throws NoSuchMethodException, IllegalAccessException, InvalidInstalledCodeException {
        getCode(getResolvedJavaMethod(MyValue1.class, "test1"), null, true, true, getInitialOptions());

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        MethodType mt_1 = MethodType.methodType(MyInterface1.class, OtherVal.class, int.class);
        MethodHandle incrementAndCheck_mh_1 = lookup.findVirtual(MyValue1.class, "test1", mt_1);
        getCode(getResolvedJavaMethod("testMethodHandle"), null, true, true, getInitialOptions()).executeVarargs(incrementAndCheck_mh_1, new MyValue1(3));
    }
}

package jdk.graal.compiler.jtt.bytecode;

import java.util.EnumSet;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.internal.value.ValueClass;
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.meta.DeoptimizationReason;
import java.util.Arrays;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class BC_array_copy_with_oop extends JTTTest{

    static final int LEN = 200;

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

    static ManyOops[] createValueClassArray() {
        ManyOops[] array = (ManyOops[])ValueClass.newNullRestrictedArray(ManyOops.class, LEN);
        for (int i = 0; i < LEN; ++i) {
            array[i] = new ManyOops();
        }
        return array;
    }

    static Object[] createObjectArray() {
        return createValueClassArray();
    }

    static Object createObject() {
        return createValueClassArray();
    }

    // System.arraycopy tests

    static void test1(ManyOops[] dst) {
        System.arraycopy(createValueClassArray(), 0, dst, 0, LEN);
    }

    static void test2(Object[] dst) {
        System.arraycopy(createObjectArray(), 0, dst, 0, LEN);
    }

    static void test3(ManyOops[] dst) {
        System.arraycopy(createObjectArray(), 0, dst, 0, LEN);
    }

    static void test4(Object[] dst) {
        System.arraycopy(createValueClassArray(), 0, dst, 0, LEN);
    }

    // System.arraycopy tests (tightly coupled with allocation of dst array)

    static Object[] test5() {
        ManyOops[] dst = (ManyOops[])ValueClass.newNullRestrictedArray(ManyOops.class, LEN);
        System.arraycopy(createValueClassArray(), 0, dst, 0, LEN);
        return dst;
    }

    static Object[] test6() {
        Object[] dst = new Object[LEN];
        System.arraycopy(createObjectArray(), 0, dst, 0, LEN);
        return dst;
    }

    static Object[] test7() {
        ManyOops[] dst = (ManyOops[])ValueClass.newNullRestrictedArray(ManyOops.class, LEN);
        System.arraycopy(createObjectArray(), 0, dst, 0, LEN);
        return dst;
    }

    static Object[] test8() {
        Object[] dst = new Object[LEN];
        System.arraycopy(createValueClassArray(), 0, dst, 0, LEN);
        return dst;
    }

    // Arrays.copyOf tests

    static Object[] test9() {
        return Arrays.copyOf(createValueClassArray(), LEN, ManyOops[].class);
    }

    static Object[] test10() {
        return Arrays.copyOf(createObjectArray(), LEN, Object[].class);
    }

    static Object[] test11() {
        ManyOops[] src = createValueClassArray();
        return Arrays.copyOf(src, LEN, src.getClass());
    }

    static Object[] test12() {
        Object[] src = createObjectArray();
        return Arrays.copyOf(createObjectArray(), LEN, src.getClass());
    }

    // System.arraycopy test using generic_copy stub

    static void test13(Object dst) {
        System.arraycopy(createObject(), 0, dst, 0, LEN);
    }

    static void produceGarbage() {
        for (int i = 0; i < 100; ++i) {
            Object[] arrays = new Object[1024];
            for (int j = 0; j < arrays.length; j++) {
                arrays[i] = new int[1024];
            }
        }
        System.gc();
    }

    @Test
    public void run0() throws Throwable {
        ManyOops[] dst1 = createValueClassArray();
        ManyOops[] dst2 = createValueClassArray();
        ManyOops[] dst3 = createValueClassArray();
        ManyOops[] dst4 = createValueClassArray();
        ManyOops[] dst13 = createValueClassArray();

        // Warmup runs to trigger compilation
        for (int i = 0; i < 50_000; ++i) {
            test1(dst1);
            test2(dst2);
            test3(dst3);
            test4(dst4);
            test5();
            test6();
            test7();
            test8();
            test9();
            test10();
            test11();
            test12();
            test13(dst13);
        }


        // Trigger GC to make sure dst arrays are moved to old gen
        produceGarbage();

        // Move data from flat src to flat dest
        InstalledCode c = getCode(getResolvedJavaMethod("test1"), null, true, false, getInitialOptions());
        c.executeVarargs(new Object[]{dst1});
        c =getCode(getResolvedJavaMethod("test2"), null, true, false, getInitialOptions());
        c.executeVarargs(new Object[]{dst2});
        c =getCode(getResolvedJavaMethod("test3"), null, true, false, getInitialOptions());
        c.executeVarargs(new Object[]{dst3});
        c =getCode(getResolvedJavaMethod("test4"), null, true, false, getInitialOptions());
        c.executeVarargs(new Object[]{dst4});
        c =getCode(getResolvedJavaMethod("test5"), null, true, false, getInitialOptions());
        Object[] dst5 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test6"), null, true, false, getInitialOptions());
        Object[] dst6 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test7"), null, true, false, getInitialOptions());
        Object[] dst7 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test8"), null, true, false, getInitialOptions());
        Object[] dst8 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test9"), null, true, false, getInitialOptions());
        Object[] dst9 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test10"), null, true, false, getInitialOptions());
        Object[] dst10 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test11"), null, true, false, getInitialOptions());
        Object[] dst11 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test12"), null, true, false, getInitialOptions());
        Object[] dst12 = (Object[])c.executeVarargs();
        c =getCode(getResolvedJavaMethod("test13"), null, true, false, getInitialOptions());
        c.executeVarargs(new Object[]{dst13});

        // Trigger GC again to make sure that the now dead src arrays are collected.
        // MyObjects should be kept alive via oop references from the dst array.
        produceGarbage();

        // Verify content
        long expected = 4L*Integer.MAX_VALUE;
        for (int i = 0; i < LEN; ++i) {
            Assert.assertEquals(dst1[i].hash(), expected);
            Assert.assertEquals(dst2[i].hash(), expected);
            Assert.assertEquals(dst3[i].hash(), expected);
            Assert.assertEquals(dst4[i].hash(), expected);
            Assert.assertEquals(((ManyOops)dst5[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst6[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst7[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst8[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst8[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst9[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst10[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst11[i]).hash(), expected);
            Assert.assertEquals(((ManyOops)dst12[i]).hash(), expected);
            Assert.assertEquals(dst13[i].hash(), expected);
        }
    }

}

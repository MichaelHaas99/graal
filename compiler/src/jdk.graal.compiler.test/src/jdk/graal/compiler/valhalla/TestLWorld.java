package jdk.graal.compiler.valhalla;

import java.lang.classfile.Label;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.EnumSet;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.core.phases.HighTier;
import jdk.graal.compiler.hotspot.replacements.HotspotSnippetsOptions;
import jdk.graal.compiler.phases.common.UseTrappingNullChecksPhase;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.internal.value.ValueClass;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class TestLWorld extends JTTTest {

    public interface MyInterface {
        public long hash();
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static abstract value class MyAbstract implements MyInterface {

    }


    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue1 extends MyAbstract {
        static int s;
        static final long sf = rL;
        int x;
        long y;
        short z;
        Integer o;
        int[] oa;
        @NullRestricted
        MyValue2 v1;
        @NullRestricted
        MyValue2 v2;
        @NullRestricted
        static final MyValue2 v3 = MyValue2.createWithFieldsInline(rI, rD);
        MyValue2 v4;
        @NullRestricted
        MyValue2 v5;
        int c;

        @DontInline
        long hasInterpreted(){
            return 0;
        }

        public MyValue1(int x, long y, short z, Integer o, int[] oa, MyValue2 v1, MyValue2 v2, MyValue2 v4, MyValue2 v5, int c) {
            s = 0;
            this.x = x;
            this.y = y;
            this.z = z;
            this.o = o;
            this.oa = oa;
            this.v1 = v1;
            this.v2 = v2;
            this.v4 = v4;
            this.v5 = v5;
            this.c = c;
        }

        static MyValue1 createDefaultDontInline() {
            return createDefaultInline();
        }

        static MyValue1 createDefaultInline() {
            return new MyValue1(0, 0, (short)0, null, null, MyValue2.createDefaultInline(), MyValue2.createDefaultInline(), null, MyValue2.createDefaultInline(), 0);
        }

        static MyValue1 createWithFieldsDontInline(int x, long y) {
            return createWithFieldsInline(x, y);
        }

        static MyValue1 createWithFieldsInline(int x, long y) {
            MyValue1 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, y);
            v = setZ(v, (short)x);
            // Don't use Integer.valueOf here to avoid control flow added by Integer cache check
            v = setO(v, new Integer(x));
            int[] oa = {x};
            v = setOA(v, oa);
            v = setV1(v, MyValue2.createWithFieldsInline(x, y, rD));
            v = setV2(v, MyValue2.createWithFieldsInline(x + 1, y + 1, rD + 1));
            v = setV4(v, MyValue2.createWithFieldsInline(x + 2, y + 2, rD + 2));
            v = setV5(v, MyValue2.createWithFieldsInline(x + 3, y + 3, rD + 3));
            v = setC(v, (int)(x+y));
            return v;
        }

        // Hash only primitive and inline type fields to avoid NullPointerException
        public long hashPrimitive() {
            return s + sf + x + y + z + c + v1.hash() + v2.hash() + v3.hash() + v5.hash();
        }

        public long hash() {
            long res = hashPrimitive();
            try {
                res += o;
            } catch (NullPointerException npe) {}
            try {
                res += oa[0];
            } catch (NullPointerException npe) {}
            try {
                res += v4.hash();
            } catch (NullPointerException npe) {}
            return res;
        }


        public void print() {
            System.out.print("s=" + s + ", sf=" + sf + ", x=" + x + ", y=" + y + ", z=" + z + ", o=" + (o != null ? (Integer)o : "NULL") + ", oa=" + (oa != null ? oa[0] : "NULL") + ", v1[");
            v1.print();
            System.out.print("], v2[");
            v2.print();
            System.out.print("], v3[");
            v3.print();
            System.out.print("], v4[");
            v4.print();
            System.out.print("], v5[");
            v5.print();
            System.out.print("], c=" + c);
        }

        static MyValue1 setX(MyValue1 v, int x) {
            return new MyValue1(x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setY(MyValue1 v, long y) {
            return new MyValue1(v.x, y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setZ(MyValue1 v, short z) {
            return new MyValue1(v.x, v.y, z, v.o, v.oa, v.v1, v.v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setO(MyValue1 v, Integer o) {
            return new MyValue1(v.x, v.y, v.z, o, v.oa, v.v1, v.v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setOA(MyValue1 v, int[] oa) {
            return new MyValue1(v.x, v.y, v.z, v.o, oa, v.v1, v.v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setC(MyValue1 v, int c) {
            return new MyValue1(v.x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, v.v5, c);
        }

        static MyValue1 setV1(MyValue1 v, MyValue2 v1) {
            return new MyValue1(v.x, v.y, v.z, v.o, v.oa, v1, v.v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setV2(MyValue1 v, MyValue2 v2) {
            return new MyValue1(v.x, v.y, v.z, v.o, v.oa, v.v1, v2, v.v4, v.v5, v.c);
        }

        static MyValue1 setV4(MyValue1 v, MyValue2 v4) {
            return new MyValue1(v.x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v4, v.v5, v.c);
        }

        static MyValue1 setV5(MyValue1 v, MyValue2 v5) {
            return new MyValue1(v.x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, v5, v.c);
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue2Inline {
        double d;
        long l;

        public MyValue2Inline(double d, long l) {
            this.d = d;
            this.l = l;
        }

        static MyValue2Inline setD(MyValue2Inline v, double d) {
            return new MyValue2Inline(d, v.l);
        }

        static MyValue2Inline setL(MyValue2Inline v, long l) {
            return new MyValue2Inline(v.d, l);
        }

        public static MyValue2Inline createDefault() {
            return new MyValue2Inline(0, 0);
        }

        public static MyValue2Inline createWithFieldsInline(double d, long l) {
            MyValue2Inline v = MyValue2Inline.createDefault();
            v = MyValue2Inline.setD(v, d);
            v = MyValue2Inline.setL(v, l);
            return v;
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue2 extends MyAbstract {
        int x;
        byte y;
        @NullRestricted
        MyValue2Inline v;

        public MyValue2(int x, byte y, MyValue2Inline v) {
            this.x = x;
            this.y = y;
            this.v = v;
        }

        public static MyValue2 createDefaultInline() {
            return new MyValue2(0, (byte)0, MyValue2Inline.createDefault());
        }

        public static MyValue2 createWithFieldsInline(int x, long y, double d) {
            MyValue2 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, (byte)x);
            v = setV(v, MyValue2Inline.createWithFieldsInline(d, y));
            return v;
        }

        public static MyValue2 createWithFieldsInline(int x, double d) {
            MyValue2 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, (byte)x);
            v = setV(v, MyValue2Inline.createWithFieldsInline(d, rL));
            return v;
        }

        public static MyValue2 createWithFieldsDontInline(int x, double d) {
            MyValue2 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, (byte)x);
            v = setV(v, MyValue2Inline.createWithFieldsInline(d, rL));
            return v;
        }

        public long hash() {
            return x + y + (long)v.d + v.l;
        }

        public long hashInterpreted() {
            return x + y + (long)v.d + v.l;
        }

        public void print() {
            System.out.print("x=" + x + ", y=" + y + ", d=" + v.d + ", l=" + v.l);
        }

        static MyValue2 setX(MyValue2 v, int x) {
            return new MyValue2(x, v.y, v.v);
        }

        static MyValue2 setY(MyValue2 v, byte y) {
            return new MyValue2(v.x, y, v.v);
        }

        static MyValue2 setV(MyValue2 v, MyValue2Inline vi) {
            return new MyValue2(v.x, v.y, vi);
        }
    }

    public static final int rI = 20;
    public static final long rL = 30;
    public static final double rD = 2.0;

    // Test storing/loading inline types to/from Object and inline type fields
    Object objectField1 = null;
    Object objectField2 = null;
    Object objectField3 = null;
    Object objectField4 = null;
    Object objectField5 = null;
    Object objectField6 = null;

    @NullRestricted
    private static final MyValue1 testValue1 = MyValue1.createWithFieldsInline(rI, rL);
    @NullRestricted
    private static final MyValue2 testValue2 = MyValue2.createWithFieldsInline(rI, rD);

    @NullRestricted MyValue1 valueField1 = testValue1;
    @NullRestricted MyValue1 valueField2 = testValue1;
    MyValue1 valueField3 = testValue1;
    @NullRestricted MyValue1 valueField4;
    MyValue1 valueField5;

    static MyValue1 staticValueField1 = testValue1;
    @NullRestricted static MyValue1 staticValueField2 = testValue1;
    @NullRestricted static MyValue1 staticValueField3;
    static MyValue1 staticValueField4;

    // Test comparing inline types with objects
    public boolean test6(Object arg) throws IllegalAccessException {
        Object vt = MyValue1.createWithFieldsInline(rI, rL);
        if (vt == arg || vt == (Object) valueField1 || vt == objectField1 || vt == null ||
                arg == vt || (Object) valueField1 == vt || objectField1 == vt || null == vt) {
            return true;
        }
        return false;
    }


    public MyValue1 myTest(MyValue1[] vals) {
        return vals[0];
    }

    public int test41() {
        MyValue1[] vals = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        vals[0] = testValue1;
        return vals[0].oa[0];
    }

    static class InlineBox {
        @NullRestricted
        LongWrapper content;

        InlineBox(long val) {
            this.content = LongWrapper.wrap(val);
        }

        static InlineBox box(long val) {
            return new InlineBox(val);
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class LongWrapper implements WrapperInterface {
        @NullRestricted
        final static LongWrapper ZERO = new LongWrapper(0);
        private long val;

        LongWrapper(long val) {
            this.val = val;
        }

        static LongWrapper wrap(long val) {
            return (val == 0L) ? ZERO : new LongWrapper(val);
        }

        public long value() {
            return val;
        }
    }

    static interface WrapperInterface {
        long value();

        final static WrapperInterface ZERO = new LongWrapper(0);

        static WrapperInterface wrap(long val) {
            return (val == 0L) ? ZERO : new LongWrapper(val);
        }
    }
    long[] lArr = {0L, rL, 0L, rL, 0L, rL, 0L, rL, 0L, rL};

    public long test112() {
        long res = 0;
        for (int i = 0; i < lArr.length; i++) {
            res += InlineBox.box(lArr[i]).content.value();
        }
        return res;
    }

    public MyValue2 test28() {
        MyValue2[] src = (MyValue2[])ValueClass.newNullRestrictedArray(MyValue2.class, 10);
        src[0] = MyValue2.createWithFieldsInline(rI, rD);
        MyValue2[] dst = (MyValue2[])src.clone();
        return dst[0];
    }

    static final MyValue2[] val_src = (MyValue2[])ValueClass.newNullRestrictedArray(MyValue2.class, 8);

    public Object[] test126() {
        return val_src.clone();
    }

    public NotFlattenable[] test86(NotFlattenable[] array, NotFlattenable o, boolean b) {
        if (b) {
            array[0] = null;
        } else {
            array[1] = null;
        }
        array[1] = o;
        return array;
    }

    public static long testLWorld126(boolean trap) {
        MyValue2 nonNull = MyValue2.createWithFieldsInline(rI, rD);
        MyValue2 val = null;

        for (int i = 0; i < 4; i++) {
            if ((i % 2) == 0) {
                val = nonNull;
            }
        }
        // 'val' is always non-null here but that's only known after loop opts
        if (trap) {
            // Uncommon trap with an inline input that can only be scalarized after loop opts
            return val.hash();
        }
        return 0;
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class NotFlattenable {
        private final Object o1 = null;
        private final Object o2 = null;
        private final Object o3 = null;
        private final Object o4 = null;
        private final Object o5 = null;
        private final Object o6 = null;
    }

    static class NonValueClass {
        public final int x;

        public NonValueClass(int x) {
            this.x = x;
        }
    }

    // stamp is type == null and exact type == false but why?
    public Object test141() {
        Object[]  array = null;
        Object[] oarray = new NonValueClass[1];
        Object[] varray = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        for (int i = 0; i < 10; i++) {
            array = oarray;
            oarray = varray;
        }
        return array[0];
    }

    public Object[] test63_helper(int i, MyValue1[] va, NonValueClass[] oa) {
        Object[] arr = null;
        if (i == 10) {
            arr = va;
        } else {
            arr = oa;
        }
        return arr;
    }

    public Object[] test63() {
        int len = Math.abs(rI) % 10;
        MyValue1[] va = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, len);
        MyValue1[] verif = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, len + 1);
        for (int i = 0; i < len; ++i) {
            va[i] = MyValue1.createWithFieldsInline(rI, rL);
            verif[i] = va[i];
        }
        NonValueClass[] oa = new NonValueClass[len];
        test63_helper(42, va, oa);
        int i = 0;
        for (; i < 10; i++);

        Object[] arr = test63_helper(i, va, oa);

        return Arrays.copyOf(arr, arr.length+1, arr.getClass());
    }

    public boolean test101(Object[] array) {
        return array instanceof MyValue1[];
    }

    public static Object[] test59(MyValue1[] va) {
        return Arrays.copyOf(va, va.length+1, va.getClass());
    }

    public static Object[] testStoreCheck(){
        MyValue1[] nullFreeArray = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        Object[] dummy = nullFreeArray;
        dummy[0]=new Object();
        return dummy;
    }


    static final MyValue1[] nullFreeArray = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);

    // Test propagation of not null-free/flat information
    public MyValue1[] test95(Object[] array) {
        array[0] = null;
        // Always throws a ClassCastException because we just successfully
        // stored null and therefore the array can't be a null-free value class array.
        return nullFreeArray.getClass().cast(array);
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValueEmpty extends MyAbstract {
        public long hash() { return 0; }

        public MyValueEmpty copy(MyValueEmpty other) { return other; }
    }

    @NullRestricted
    static MyValueEmpty fEmpty1;
    static MyValueEmpty fEmpty2 = new MyValueEmpty();
    @NullRestricted
    MyValueEmpty fEmpty3;
    MyValueEmpty fEmpty4 = new MyValueEmpty();

    public boolean test121() {
        return fEmpty1.equals(fEmpty3);
        // fEmpty2 and fEmpty4 could be null, load can't be removed
    }

    public void test34(Object[] oa, Object o, int index) {
        oa[index] = o;
    }

    private static final MyValue1[] testValue1Array = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 3);

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");

    @Test
    public void run0() throws Throwable {
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test6", new Object[]{null});
    }

    @Test
    public void run1() throws Throwable {
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test6", new Object[]{null});
    }

    @Test
    public void run2() throws Throwable {
        resetCache();
        MyValue1[] vals = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 2);
        vals[0] = testValue1;
        runTest(EnumSet.allOf(DeoptimizationReason.class), "myTest", new Object[]{vals});
    }

    @Test
    public void run3() throws Throwable {
        resetCache();
        MyValue1[] vals = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 2);
        vals[0] = testValue1;
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "myTest", new Object[]{vals});
    }

    @Test
    public void run4() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test41");
    }

    @Test
    public void run5() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test41");
    }

    @Test
    public void run6() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test112");
    }

    @Test
    public void run7() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test112");
    }

    @Test
    public void run8() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test28");
    }

    @Test
    public void run9() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test28");
    }

    @Test
    public void run10() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test126");
    }

    @Test
    public void run11() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test126");
    }

    @Test
    public void run12() throws Throwable {
        resetCache();
        NotFlattenable vt = new NotFlattenable();
        NotFlattenable[] array1 = new NotFlattenable[2];
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test86", new Object[]{array1, vt, true});
        runTest("test86", new Object[]{array1, null, false});
        NotFlattenable[] array2 = (NotFlattenable[])ValueClass.newNullRestrictedArray(NotFlattenable.class, 2);
        runTest("test86", new Object[]{array2, null, true});
    }

    @Test
    public void run13() throws Throwable {
        resetCache();
        NotFlattenable vt = new NotFlattenable();
        NotFlattenable[] array1 = new NotFlattenable[2];
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test86", new Object[]{array1, vt, true});
        runTest(WITHOUT_PEA, "test86", new Object[]{array1, null, false});
        NotFlattenable[] array2 = (NotFlattenable[])ValueClass.newNullRestrictedArray(NotFlattenable.class, 2);
        runTest(WITHOUT_PEA, "test86", new Object[]{array2, null, true});
    }

    @Test
    public void run14() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test141");
    }

    @Test
    public void run15() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test141");
    }

    @Test
    public void run16() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test63");
    }

    @Test
    public void run17() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test63");
    }

    @Test
    public void run18() throws Throwable {
        resetCache();
        MyValue1[] array1 = new MyValue1[1];
        NotFlattenable[] array2 = (NotFlattenable[])ValueClass.newNullRestrictedArray(NotFlattenable.class, 1);
        MyValue1[] array3 = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test101", new Object[]{array3});
    }

    @Test
    public void run19() throws Throwable {
        resetCache();
        MyValue1[] array1 = new MyValue1[1];
        NotFlattenable[] array2 = (NotFlattenable[])ValueClass.newNullRestrictedArray(NotFlattenable.class, 1);
        MyValue1[] array3 = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test101", new Object[]{array3});
    }

    @Test
    public void run20() throws Throwable {
        resetCache();
        int len = Math.abs(rI) % 10;
        MyValue1[] va = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, len);
        MyValue1[] verif = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, len + 1);
        for (int i = 0; i < len; ++i) {
            va[i] = MyValue1.createWithFieldsInline(rI, rL);
            verif[i] = va[i];
        }
        InstalledCode c =getCode(getResolvedJavaMethod("test59"), null, true, false, getInitialOptions());
        Object[] result = (Object[])c.executeVarargs(new Object[]{va});
        //Assert.assertEquals(result[len], ValueClass.zeroInstance(MyValue1.class));
        result[len] = MyValue1.createDefaultInline();
        for (int i = 0; i < verif.length; ++i) {
            Assert.assertEquals(verif[i].hash(), ((MyInterface)result[i]).hash());
        }

        //runTest(EnumSet.allOf(DeoptimizationReason.class), "test59", new Object[]{va});
    }

    @Test
    public void run21() throws Throwable {
        resetCache();
        int len = Math.abs(rI) % 10;
        MyValue1[] va = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, len);
        MyValue1[] verif = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, len + 1);
        for (int i = 0; i < len; ++i) {
            va[i] = MyValue1.createWithFieldsInline(rI, rL);
            verif[i] = va[i];
        }
        InstalledCode c =getCode(getResolvedJavaMethod("test59"), null, true, false, WITHOUT_PEA);
        Object[] result = (Object[])c.executeVarargs(new Object[]{va});
        //Assert.assertEquals(result[len], ValueClass.zeroInstance(MyValue1.class));
        result[len] = MyValue1.createDefaultInline();
        for (int i = 0; i < verif.length; ++i) {
            Assert.assertEquals(verif[i].hash(), ((MyInterface)result[i]).hash());
        }
        //runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test59", new Object[]{va});
    }

    @Test
    public void run22() throws Throwable {
        resetCache();
        MyValue1[] array1 = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        NonValueClass[] array2 = new NonValueClass[1];

//        InstalledCode c =getCode(getResolvedJavaMethod("test95"), null, true, false, WITHOUT_PEA);
//        Object[] result = (Object[])c.executeVarargs(new Object[]{va});
//        Assert.assertEquals(result[len], ValueClass.zeroInstance(MyValue1.class));
        runTest(WITHOUT_PEA, "test95", new Object[]{array1});
        resetCache();
        runTest(WITHOUT_PEA, "test95", new Object[]{array2});
        //runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test59", new Object[]{va});
    }

    @Test
    public void run23() throws Throwable {
        resetCache();
        MyValue1[] array1 = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        NonValueClass[] array2 = new NonValueClass[1];

//        InstalledCode c =getCode(getResolvedJavaMethod("test95"), null, true, false, WITHOUT_PEA);
//        Object[] result = (Object[])c.executeVarargs(new Object[]{va});
//        Assert.assertEquals(result[len], ValueClass.zeroInstance(MyValue1.class));
        runTest("test95", new Object[]{array1});
        resetCache();
        runTest("test95", new Object[]{array2});
        //runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test59", new Object[]{va});
    }

    @Test
    public void run24() throws Throwable {
        resetCache();
        runTest(EnumSet.allOf(DeoptimizationReason.class), "test121");
    }

    @Test
    public void run25() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, EnumSet.allOf(DeoptimizationReason.class), "test121");
    }

    @Test
    public void run26() throws Throwable {
        resetCache();

        try{
            InstalledCode c =getCode(getResolvedJavaMethod("testStoreCheck"), null, true, false, getInitialOptions());
            c.executeVarargs();
            throw new Exception("expected store exception");
        }catch (Exception e){
            // true
        }

    }

    @Test
    public void run27() throws Throwable {
        resetCache();
        try{
            InstalledCode c =getCode(getResolvedJavaMethod("testStoreCheck"), null, true, false, WITHOUT_PEA);
            c.executeVarargs();
            throw new Exception("expected store exception");
        }catch (Exception e){
            // true
        }
        //runTest(WITHOUT_PEA, "testStoreCheck");
    }

    @Test
    public void run28() throws Throwable {
        runTest( "test34", new Object[]{testValue1Array, null, Math.abs(rI) % 3});
    }

    @Test
    public void run29() throws Throwable {
        runTest(WITHOUT_PEA, "test34", new Object[]{testValue1Array, null, Math.abs(rI) % 3});
    }

    private static final OptionValues DEMO_OPTIONS_WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, GraalOptions.InlineVTableStubs, false, GraalOptions.LimitInlinedInvokes, 0.0, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");


    @Test
    public void run30() throws Throwable {
        resetCache();
        for (int i = 0; i < 100000; i++) {
            testLWorld126(false);
            //test("testLWorld126", false);
        }
        //runTest(DEMO_OPTIONS_WITHOUT_INLINING, "testLWorld126", false);
        //runTest( DEMO_OPTIONS_WITHOUT_INLINING,"testLWorld126", true);
        InstalledCode c = getCode(getResolvedJavaMethod("testLWorld126"), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        c.executeVarargs(true);
    }

    @Test
    public void run31() throws Throwable {
        resetCache();
        for (int i = 0; i < 100000; i++) {
            testLWorld126(false);
            //test("testLWorld126", false);
        }
        //runTest( WITHOUT_PEA,"testLWorld126", false);
        //runTest(WITHOUT_PEA, "testLWorld126", true);
        InstalledCode c = getCode(getResolvedJavaMethod("testLWorld126"), null, true, false, getInitialOptions());
        c.executeVarargs(true);
    }

    public void randomTestMethod(){
        MyValue2.createWithFieldsInline(1,2.0);
    }

    @Test
    public void run32() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod("randomTestMethod"), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
    }

    public long test5(MyValue1 arg, boolean deopt) {
        Object vt1 = MyValue1.createWithFieldsInline(rI, rL);
        Object vt2 = MyValue1.createWithFieldsDontInline(rI, rL);
        Object vt3 = arg;
        Object vt4 = valueField1;
        if (deopt) {
            // uncommon trap
            GraalDirectives.deoptimize();
        }
        return ((MyValue1)vt1).hash() + ((MyValue1)vt2).hash() +
                ((MyValue1)vt3).hash() + ((MyValue1)vt4).hash();
    }

    @Test
    public void run33() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod("test5"), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        c.executeVarargs(this, testValue1, false);
        c.executeVarargs(this, testValue1, true);
    }

    static class InterfaceBox {
        WrapperInterface content;

        @ForceInline
        InterfaceBox(WrapperInterface content) {
            this.content = content;
        }

        @ForceInline
        static InterfaceBox box_sharp(long val) {
            return new InterfaceBox(LongWrapper.wrap(val));
        }

        @ForceInline
        static InterfaceBox box(long val) {
            return new InterfaceBox(WrapperInterface.wrap(val));
        }
    }

    public long test109() {
        long res = 0;
        for (int i = 0; i < lArr.length; i++) {
            res += InterfaceBox.box(lArr[i]).content.value();
        }
        return res;
    }

    @Test
    public void run34() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod("test109"), null, true, true, DEMO_OPTIONS_WITHOUT_INLINING);
        c.executeVarargs(this);
    }

    public void test23_inline(Object[] oa, Object o, int index) {
        oa[index] = o;
    }

    public long test23() {
        MyValue2 v = MyValue2.createDefaultInline();
        return v.hash();
    }

    @Test
    public void run35() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod("test23"), null, true, true, getInitialOptions());
        //c.executeVarargs(this);
    }

    public static void test599(Object o, boolean b) {
        MyValue1 vt = MyValue1.createWithFieldsInline(rI, rL);
        Object sync = b ? vt : o;
        synchronized (sync) {
            if (b) {
                throw new RuntimeException("test59 failed: synchronization on inline type should not succeed");
            }
        }
    }

    @Test
    public void run36() throws InvalidInstalledCodeException {
        for(int i =0; i<1000;i++){
            try{
                test599(new Object(), true);
            } catch (Exception e) {

            }
        }
        InstalledCode c = getCode(getResolvedJavaMethod("test599"), null, true, false, getInitialOptions());
        c.executeVarargs(new Object(), false);
        //test59(new Object(), false);
        try {
            c.executeVarargs(new Object(), true);
            //test59(new Object(), true);
            throw new RuntimeException("test59 failed: no exception thrown");
        } catch (IdentityException ex) {
            // Expected
        }
    }

    @Test
    public void run37() throws  Throwable{
        resetCache();

        InstalledCode c = getCode(getResolvedJavaMethod(java.io.ObjectStreamField.class, "toString"), null, true, true, getInitialOptions());
    }

    public MyValue1[] test5(boolean b) {
        MyValue1[] va;
        if (b) {
            va = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 5);
            for (int i = 0; i < 5; ++i) {
                va[i] = MyValue1.createWithFieldsInline(rI, rL);
            }
        } else {
            va = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 10);
            for (int i = 0; i < 10; ++i) {
                va[i] = MyValue1.createWithFieldsInline(rI + i, rL + i);
            }
        }
        long sum = va[0].hasInterpreted();
        if (b) {
            va[0] = MyValue1.createWithFieldsDontInline(rI, sum);
        } else {
            va[0] = MyValue1.createWithFieldsDontInline(rI + 1, sum + 1);
        }
        return va;
    }

    @Test
    public void run38() throws  Throwable{
        resetCache();

        InstalledCode c = getCode(getResolvedJavaMethod("test5"), null, true, true, getInitialOptions());
    }

    @Test
    public void run39() throws  Throwable{
        resetCache();

        InstalledCode c = getCode(getResolvedJavaMethod(MyValue1.class, "createWithFieldsInline"), null, true, true, getInitialOptions());
    }

    public static long test11(int x, long y) {
        MyValue1 v = MyValue1.createWithFieldsInline(x, y);
        for (int i = 0; i < 10; ++i) {
            v = MyValue1.createWithFieldsInline(v.x + 1, v.y + 1);
        }
        return v.hash();
    }

    // Leaf method not inlined but returned type is known
    @NullRestricted
    final MyValue3 test2_vt = MyValue3.create();

    @DontInline
    MyValue3 test2_target() {
        return test2_vt;
    }

    static final MethodHandle test2_mh;

    static {
        try {
            test2_mh = MethodHandles.lookup().findVirtual(TestLWorld.class, "test2_target", MethodType.methodType(MyValue3.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    ;

    public MyValue3 test2() throws Throwable {
        return (MyValue3)test2_mh.invokeExact(this);
    }

    @Test
    public void run40() throws  Throwable{
        resetCache();

        InstalledCode c = getCode(getResolvedJavaMethod( "test11"), null, true, true, getInitialOptions());
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue3Inline {
        float f7;
        double f8;

        @ForceInline
        public MyValue3Inline(float f7, double f8) {
            this.f7 = f7;
            this.f8 = f8;
        }

        @ForceInline
        static MyValue3Inline setF7(MyValue3Inline v, float f7) {
            return new MyValue3Inline(f7, v.f8);
        }

        @ForceInline
        static MyValue3Inline setF8(MyValue3Inline v, double f8) {
            return new MyValue3Inline(v.f7, f8);
        }

        @ForceInline
        public static MyValue3Inline createDefault() {
            return new MyValue3Inline(0, 0);
        }

        @ForceInline
        public static MyValue3Inline createWithFieldsInline(float f7, double f8) {
            MyValue3Inline v = createDefault();
            v = setF7(v, f7);
            v = setF8(v, f8);
            return v;
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue3 extends MyAbstract {
        char c;
        byte bb;
        short s;
        int i;
        long l;
        Object o;
        float f1;
        double f2;
        float f3;
        double f4;
        float f5;
        double f6;
        @NullRestricted
        MyValue3Inline v1;

        @ForceInline
        public MyValue3(char c, byte bb, short s, int i, long l, Object o,
                        float f1, double f2, float f3, double f4, float f5, double f6,
                        MyValue3Inline v1) {
            this.c = c;
            this.bb = bb;
            this.s = s;
            this.i = i;
            this.l = l;
            this.o = o;
            this.f1 = f1;
            this.f2 = f2;
            this.f3 = f3;
            this.f4 = f4;
            this.f5 = f5;
            this.f6 = f6;
            this.v1 = v1;
        }

        @ForceInline
        static MyValue3 setC(MyValue3 v, char c) {
            return new MyValue3(c, v.bb, v.s, v.i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setBB(MyValue3 v, byte bb) {
            return new MyValue3(v.c, bb, v.s, v.i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setS(MyValue3 v, short s) {
            return new MyValue3(v.c, v.bb, s, v.i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setI(MyValue3 v, int i) {
            return new MyValue3(v.c, v.bb, v.s, i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setL(MyValue3 v, long l) {
            return new MyValue3(v.c, v.bb, v.s, v.i, l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setO(MyValue3 v, Object o) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setF1(MyValue3 v, float f1) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, f1, v.f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setF2(MyValue3 v, double f2) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, v.f1, f2, v.f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setF3(MyValue3 v, float f3) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, v.f1, v.f2, f3, v.f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setF4(MyValue3 v, double f4) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, v.f1, v.f2, v.f3, f4, v.f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setF5(MyValue3 v, float f5) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, f5, v.f6, v.v1);
        }

        @ForceInline
        static MyValue3 setF6(MyValue3 v, double f6) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, f6, v.v1);
        }

        @ForceInline
        static MyValue3 setV1(MyValue3 v, MyValue3Inline v1) {
            return new MyValue3(v.c, v.bb, v.s, v.i, v.l, v.o, v.f1, v.f2, v.f3, v.f4, v.f5, v.f6, v1);
        }

        @ForceInline
        public static MyValue3 createDefault() {
            return new MyValue3((char) 0, (byte) 0, (short) 0, 0, 0, null, 0, 0, 0, 0, 0, 0, MyValue3Inline.createDefault());
        }

        @ForceInline
        public static MyValue3 create() {
            MyValue3 v = createDefault();
            v = setC(v, (char) 3);
            v = setBB(v, (byte) 4);
            v = setS(v, (short) 5);
            v = setI(v, 6);
            v = setL(v, 7);
            v = setO(v, new Object());
            v = setF1(v, 8.0f);
            v = setF2(v, 9.0);
            v = setF3(v, 10.0f);
            v = setF4(v, 11.0);
            v = setF5(v, 12.0f);
            v = setF6(v, 13.0);
            v = setV1(v, MyValue3Inline.createWithFieldsInline(14.0f, 15.0));
            return v;
        }

        @DontInline
        public static MyValue3 createDontInline() {
            return create();
        }

        @ForceInline
        public static MyValue3 copy(MyValue3 other) {
            MyValue3 v = createDefault();
            v = setC(v, other.c);
            v = setBB(v, other.bb);
            v = setS(v, other.s);
            v = setI(v, other.i);
            v = setL(v, other.l);
            v = setO(v, other.o);
            v = setF1(v, other.f1);
            v = setF2(v, other.f2);
            v = setF3(v, other.f3);
            v = setF4(v, other.f4);
            v = setF5(v, other.f5);
            v = setF6(v, other.f6);
            v = setV1(v, other.v1);
            return v;
        }
        @ForceInline
        public long hash() {
            return c +
                    bb +
                    s +
                    i +
                    l +
                    o.hashCode() +
                    Float.hashCode(f1) +
                    Double.hashCode(f2) +
                    Float.hashCode(f3) +
                    Double.hashCode(f4) +
                    Float.hashCode(f5) +
                    Double.hashCode(f6) +
                    Float.hashCode(v1.f7) +
                    Double.hashCode(v1.f8);
        }
    }

    public MyValue3 testMethodHandle() throws Throwable {
        return (MyValue3)test2_mh.invokeExact(this);
    }


    @Test
    public void run41() throws  Throwable{
        resetCache();
        MyValue1.createDefaultInline();
        getCode(getResolvedJavaMethod( "test2_target"), null, true, true, getInitialOptions());
        InstalledCode c = getCode(getResolvedJavaMethod( "testMethodHandle"), null, true, true, getInitialOptions());
        c.executeVarargs(this);
    }

    public static int test28_intrinsic(MyValue1 v) {
        return UNSAFE.getUnsafe().getByte(v, 108);
    }

    @Test
    public void run42() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test28_intrinsic"), null, true, true, WITHOUT_PEA);
    }

    static class TestRawLoad{
        private int v = -870; // 1111110011011001
    }

    public static void testRawLoad() throws NoSuchFieldException {
        TestRawLoad t = new TestRawLoad();
        Field vField = TestRawLoad.class.getDeclaredField("v");
        Unsafe U = UNSAFE.getUnsafe();
        System.out.println(U.getByte(t, 12));
    }

    @Test
    public void run43() throws  Throwable{
        resetCache();
        testRawLoad();
        // 1111111111011001
        getCode(getResolvedJavaMethod( "testRawLoad"), null, true, true, WITHOUT_PEA).executeVarargs();
        // 1111110011011001
        getCode(getResolvedJavaMethod( "testRawLoad"), null, true, true, getInitialOptions()).executeVarargs();
    }

    public static void testRawStore() throws NoSuchFieldException {
        TestRawLoad t = new TestRawLoad();
        Field vField = TestRawLoad.class.getDeclaredField("v");
        Unsafe U = UNSAFE.getUnsafe();
        U.putByte(t, 12, (byte)-870);
        System.out.println(t.v);
    }

    @Test
    public void run45() throws  Throwable{
        resetCache();
        testRawStore();
        // System.out.println();
        //System.out.println();
        // 1111111111011001
        getCode(getResolvedJavaMethod( "testRawStore"), null, true, true, WITHOUT_PEA).executeVarargs();
        // 1111110011011001
        getCode(getResolvedJavaMethod( "testRawStore"), null, true, true, getInitialOptions()).executeVarargs();
    }

    public static int test6(MyValue1 v) {
        return v.hashCode();
    }

    @Test
    public void run44() throws  Throwable{
        resetCache();
        MyValue1 v = MyValue1.createWithFieldsInline(rI, rL);
        System.out.println(test6(v));
        getCode(getResolvedJavaMethod( "test6"), null, true, true, getInitialOptions()).executeVarargs(v);
    }

    public static java.util.Optional<java.lang.Integer> test7(java.util.Optional<java.lang.Integer> v, Label a) {
        ExceptionCatch.of(null, null, null, null);
        return v;
    }

    @Test
    public void run46() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test7"), null, true, true, getInitialOptions());
        getCode(getResolvedJavaMethod( ExceptionCatch.class, "of", Label.class, Label.class, Label.class, java.util.Optional.class), null, true, true, getInitialOptions());
    }

    public long test142() {
        MyValue2 nonNull = MyValue2.createWithFieldsInline(rI, rD);
        MyInterface val = null;

        for (int i = 0; i < 4; i++) {
            if ((i % 2) == 0) {
                val = nonNull;
            }
        }
        return val.hash();
    }

    @Test
    public void run47() throws  Throwable{
        // set forceInlineEveryThing to trigger error
        resetCache();
        getCode(getResolvedJavaMethod( "test142"), null, true, true, getInitialOptions());
    }

    public static boolean test111() {
        MyValue1[] src = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
        src[0] = testValue1;
        MyValue1[] dst = Arrays.copyOf(src, src.length, MyValue1[].class);
        //Assert.assertEquals(src[0], dst[0]);
        return src[0]== dst[0];
    }

    @Test
    public void run48() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test111"), null, true, true, getInitialOptions()).executeVarargs();
    }

    MyValue1 refField;

    public MyValue1 test45(boolean b1, boolean b2) {
        MyValue1 val = MyValue1.createWithFieldsInline(rI, rL);
        if (b1) {
            val = refField;
        }
        if (b2) {
            // Uncommon trap
            GraalDirectives.deoptimize();
        }
        return val;
    }

    @Test
    public void run49() throws  Throwable{
        resetCache();
        refField = MyValue1.createWithFieldsInline(rI+1, rL+1);
        getCode(getResolvedJavaMethod( "test45"), null, true, true, getInitialOptions());
    }

    static final MyValue1[] refArray = new MyValue1[2];

    public long test113(boolean b) {
        MyValue1 val = MyValue1.createWithFieldsInline(rI, rL);
        if (b) {
            val = refArray[0];
        }
        return val.hash();
    }

    @Test
    public void run50() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test113"), null, true, true, getInitialOptions());
    }

    @Test
    public void run51() throws  Throwable{
        resetCache();
        Class<?> valueClass = Class.forName("ValueObjectMethods");
        int a = 3;
        //getCode(getResolvedJavaMethod( "test113"), null, true, true, getInitialOptions());
    }

    static class PClass{}
    static PClass pfield1;
    static PClass pfield2;

    public void testPEAFanOut(boolean a){
        PClass virtual = new PClass();
        if(a){
            pfield1 = virtual;
        }else{
            pfield2 = virtual;
        }
    }

    @Test
    public void run52() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testPEAFanOut"), null, true, true, getInitialOptions());
    }

    public int getConstant3(int[] a, boolean b) {
        if (b) {
            return a[0];
        } else {
            return a[0];
        }

    }

    @Test
    public void run53() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "getConstant3"), null, true, true, getInitialOptions());
    }

    static OneField staticAlwaysVirtual;


    @ImplicitlyConstructible
    static value class OneField{
        private int c;
        public OneField(int c) {
            this.c = c;
        }


        public OneField oneFieldInnerMethod(OneField o){
            return o;
        }
    }

    public int testPEAAlwaysVirtual(boolean a) {
        OneField val = new OneField(1);
        OneField val2 = new OneField(2);
        if (a) {
            staticAlwaysVirtual = val;
        }else{
            val = val2;
            GraalDirectives.blackhole(val2);
        }
        return val.c;
    }

    @Test
    public void run54() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testPEAAlwaysVirtual"), null, true, true, getInitialOptions());
    }

    public int testPEANullCache(boolean a) {
        OneField val = new OneField(1);
        OneField val2 = new OneField(2);
        if (a) {
            val =  null;
            val2 =  null;
        }
        return val.c + val2.c;
    }

    @Test
    public void run55() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testPEANullCache"), null, true, true, getInitialOptions());
    }

    public void testMonitorEnter(boolean a) {
        Object val = new OneField(1);
        if (a) {
            val =  null;
        }
        synchronized (val){};
    }

    @Test
    public void run56() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testMonitorEnter"), null, true, true, getInitialOptions());
    }

    static value class TwoField{
        private int c;
        private int d;
        public TwoField(int c, int d) {
            this.c = c;
            this.d = d;
        }
    }

    public int testPEANullCheck(boolean a) {
        TwoField val = new TwoField(1, 2);
        if (a) {
            val =  null;
        }
        return val.c + val.d;
    }

    @Test
    public void run57() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testPEANullCheck"), null, true, true, getInitialOptions());
    }

    static value class MyNumber {
        private long d0;
        @BytecodeParserForceInline
        private MyNumber(long d0) { this.d0 = d0; }
        @BytecodeParserForceInline
        public MyNumber add(long v) { return new MyNumber(d0 + v); }

    }

    private static void loop(Object number) {
        MyNumber dec = (MyNumber) number;
        for (int i = 0; i < 1_000_000_000; ++i) {
            dec = dec.add(i);
        }
    }

    @Test
    public void run58() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "loop"), null, true, true, getInitialOptions());
    }

    private static long loop2(Object number) {
        MyNumber dec = new MyNumber(1);
        for (int i = 0; i < 1_000_000_000; ++i) {
            dec = (MyNumber) number;
        }
        return dec.d0;
    }

    @Test
    public void run59() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "loop2"), null, true, true, getInitialOptions());
    }

    public OneField testScalarization(OneField o) {
        return o;
    }

    @Test
    public void run60() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testScalarization"), null, true, true, getInitialOptions());
    }

    public int testLoadFieldCanonicalization(OneField o) {
        return o.c;
    }

    @Test
    public void run61() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testLoadFieldCanonicalization"), null, true, true, getInitialOptions());
    }

    @BytecodeParserNeverInline
    public static OneField scalarizedCall(OneField o){
        return o;
    }

    public static OneField testScalarizedArgument(OneField o) {
        return scalarizedCall(o);
    }

    @Test
    public void run62() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testScalarizedArgument"), null, true, true, getInitialOptions()).executeVarargs(new OneField(1));
    }

    static class FlatFieldHolder{
        @NullRestricted OneField o;
        FlatFieldHolder(OneField o) { this.o = o; }
    }

    public static int testFlatField(FlatFieldHolder o, OneField f) {
        o.o = f;
        o.o = new OneField(2);
        return f.c;
    }

    @Test
    public void run63() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testFlatField"), null, true, true, getInitialOptions());
    }

    @Test
    public void run64() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod(OneField.class, "oneFieldInnerMethod"), null, true, true, getInitialOptions()).executeVarargs(new OneField(1), new OneField(2));
    }

    private static long loop3(Object number, boolean a) {
        MyNumber dec = new MyNumber(1);
        for (int i = 0; i < 1_000_000_000; ++i) {
            if(a){
                dec = (MyNumber) number;
            }else{
                dec =null;
            }
        }
        return dec.d0;
    }

    @Test
    public void run65() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "loop3"), null, true, true, getInitialOptions());
    }

    public void test73(Object[] oa, MyValue1 v, Object o) {
        // TestLWorld.test38 use a C1 Phi node for the array. This test
        // adds the case where the stored value is a C1 Phi node.
        Object o2 = (o == null) ? v : o;
        oa[0] = v;  // The stored value is known to be flattenable
        oa[1] = o;  // The stored value may be flattenable
        oa[2] = o2; // The stored value may be flattenable (a C1 Phi node)
        oa[0] = oa; // The stored value is known to be not flattenable (an Object[])
    }

    @Test
    public void run66() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test73"), null, true, true, getInitialOptions());
    }

    static value class MyValueContainer {
        private final Object value;

        private MyValueContainer(Object value) {
            this.value = value;
        }
    }
    static value class MyValue161 {
        int x = 0;
    }

    public MyValueContainer test166(boolean b) {
        MyValueContainer res = b ? new MyValueContainer(42) : new MyValueContainer(new MyValue161());
        // Cast to verify that merged values are of correct type
        Object obj = b ? (Integer)res.value : (MyValue161)res.value;
        return res;
    }

    //  mx unittest -XX:CompileCommand='dontinline,java.lang.Object::*' jdk.graal.compiler.valhalla.TestLWorld#run67
    @Test
    public void run67() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test166"), null, true, true, getInitialOptions());
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class CircularValue1 {
        CircularValue1 val;
        int x;

        @ForceInline
        public CircularValue1(CircularValue1 val) {
            this.val = val;
            this.x = rI;
        }
    }

    public CircularValue1 test102(boolean b) {
        CircularValue1 val = new CircularValue1(new CircularValue1(null));
        if (b) {
            val = null;
        }
        return val;
    }

    @Test
    public void run68() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test102"), null, true, true, getInitialOptions());
    }

    static value class MyLocalClass{
        Object[] a = new Object[3];
    }

    static Object[] globalObjectArray;
    public static boolean test103(){
        MyLocalClass m = new MyLocalClass();
        GraalDirectives.blackhole(m);
        globalObjectArray = m.a;
        return m.a == globalObjectArray;
    }

    @Test
    public void run69() throws  Throwable{
        resetCache();
        assert (boolean) getCode(getResolvedJavaMethod( "test103"), null, true, true, getInitialOptions()).executeVarargs();
    }

    static class IdentityClass{
        MyValue161 myValue161;
        IdentityClass(MyValue161 myValue161){
            this.myValue161 = myValue161;
        }
    }

    public static void testCache(Object o, boolean a, MyValue161 my){
        IdentityClass i1 = new IdentityClass(my);
        IdentityClass i2 = new IdentityClass(my);

        if(a){
            i1 = new IdentityClass(new MyValue161());
            i2 = new IdentityClass(new MyValue161());
        }
        GraalDirectives.blackhole(i1);
        GraalDirectives.blackhole(i2);
    }

    @Test
    public void run70() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testCache"), null, true, true, getInitialOptions());
    }


    public MyValueContainer testUniqueVirtual(boolean b) {
        Object m = new MyValue161();
        MyValueContainer res = b ? new MyValueContainer(m) : new MyValueContainer(m);
        return res;
    }

    @Test
    public void run71() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "testUniqueVirtual"), null, true, true, getInitialOptions());
    }

    public void test131() {
        Object obj = testValue1;
        synchronized (obj) {
            throw new RuntimeException("test131 failed: synchronization on inline type should not succeed");
        }
    }

    @Test
    public void run72() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "test131"), null, true, true, getInitialOptions());
    }

    static class ValueData {
        final int x;
        ValueData(int x) {
            this.x = x;
        }
    }

    public static int demo(boolean a) {
        ValueData v = new ValueData(3);
        if (a) {
            GraalDirectives.blackhole(3);
            GraalDirectives.blackhole(v);
        }
        return v.x;
    }

    @Test
    public void run73() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "demo"), null, true, true, getInitialOptions());
    }

    static value class ValueContainer1 {
        ValueContainer2 container;

        @BytecodeParserNeverInline
        ValueContainer1(ValueContainer2 c) {
            container = c;
        }
    }

    static value class ValueContainer2 {
        int i;

        ValueContainer2(int i ){
            this.i = i;
        }
    }

    public static int demoScalarizationParam(ValueContainer1 v) {
        return v.container.i;
    }

    @Test
    public void run74() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "demoScalarizationParam"), null, true, true, getInitialOptions());
    }

    public static int demoScalarizationConstructor() {
        ValueContainer1 v = new ValueContainer1(null);
        return v.container.i;
    }

    @Test
    public void run75() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "demoScalarizationConstructor"), null, true, true, DEMO_OPTIONS_WITHOUT_INLINING);
    }

    public static int demoScalarizationConstructo2() {
        ValueContainer1 v = new ValueContainer1(new ValueContainer2(3));
        return v.container.i;
    }

    @Test
    public void run76() throws  Throwable{
        resetCache();
        getCode(getResolvedJavaMethod( "demoScalarizationConstructo2"), null, true, true, DEMO_OPTIONS_WITHOUT_INLINING);
    }

}

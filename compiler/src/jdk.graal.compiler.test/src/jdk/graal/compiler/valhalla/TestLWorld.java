package jdk.graal.compiler.valhalla;

import java.lang.reflect.Field;
import java.util.EnumSet;

import jdk.graal.compiler.hotspot.replacements.HotspotSnippetsOptions;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
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

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test6");

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
}

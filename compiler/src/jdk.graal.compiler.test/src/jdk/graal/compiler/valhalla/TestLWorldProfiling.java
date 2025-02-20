package jdk.graal.compiler.valhalla;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.core.phases.HighTier;
import jdk.graal.compiler.hotspot.replacements.HotspotSnippetsOptions;
import jdk.graal.compiler.phases.common.UseTrappingNullChecksPhase;
import jdk.internal.vm.annotation.ForceInline;
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
public class TestLWorldProfiling extends JTTTest {

    public static final int rI = 20;
    public static final long rL = 30;
    public static final double rD = 2.0;

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
    @NullRestricted
    private static final MyValue1 testValue1 = MyValue1.createWithFieldsInline(rI, rL);

    @NullRestricted
    private static final MyValue2 testValue2 = MyValue2.createWithFieldsInline(rI, rD);

    private static final MyValue1[] testValue1Array = (MyValue1[])ValueClass.newNullRestrictedArray(MyValue1.class, 1);
    static {
        testValue1Array[0] = testValue1;
    }

    private static final MyValue2[] testValue2Array = (MyValue2[])ValueClass.newNullRestrictedArray(MyValue2.class, 1);
    static {
        testValue2Array[0] = testValue2;
    }

    public Object test3(Object[] array) {
        return array[0];
    }

    public void test3_verifier() throws InvalidInstalledCodeException {
        //InstalledCode c = getCode(getResolvedJavaMethod("test3"), null, true, false, getInitialOptions());
        //Object o  = c.executeVarargs(this, testValue1Array);
        Object o = test3(testValue1Array);
        //Object o = test3(testValue1Array);
        Assert.assertEquals(((MyValue1)o).hash(), testValue1.hash());
        //o = c.executeVarargs(this, testValue2Array);
        o = test3(testValue2Array);
        Assert.assertEquals(((MyValue2)o).hash(), testValue2.hash());
    }

    public Object test5(Object[] array) {
        return array[0];
    }

    private static final MyValue1[] testValue1NotFlatArray = new MyValue1[] { testValue1 };

//    @Test
//    public void test5_verifier() throws InvalidInstalledCodeException {
//        InstalledCode c = getCode(getResolvedJavaMethod("test5"), null, true, false, getInitialOptions());
//        Object o  = c.executeVarargs(this, testValue1Array);
//        Assert.assertEquals(((MyValue1)o).hash(), testValue1.hash());
//        o = c.executeVarargs(this, testValue1NotFlatArray);
//        Assert.assertEquals(((MyValue1)o).hash(), testValue1.hash());
//    }

    public void test5_verifier() {
        Object o = test5(testValue1Array);
        System.out.println("result: "+ (((MyValue1)o).hash()== testValue1.hash()));
        //Assert.assertEquals(((MyValue1)o).hash(), testValue1.hash());
        o = test5(testValue1NotFlatArray);
        //Assert.assertEquals(((MyValue1)o).hash(), testValue1.hash());
        System.out.println("result: "+ (((MyValue1)o).hash() == testValue1.hash()));
    }

    private static final OptionValues DEMO_OPTIONS_WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, GraalOptions.InlineVTableStubs, false, GraalOptions.LimitInlinedInvokes, 0.0, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");


    @Test
    public void test5_verifier2() throws InvalidInstalledCodeException {
        InstalledCode c = getCode(getResolvedJavaMethod("test5_verifier"), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        c.executeVarargs(this);
    }

    @Test
    public void test3_verifier2() throws InvalidInstalledCodeException {
        InstalledCode c = getCode(getResolvedJavaMethod("test3_verifier"), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        c.executeVarargs(this);
    }

    @Test
    public void testArrayListAdd(){
        getCode(getResolvedJavaMethod(ArrayList.class, "add"), null, true, false, getInitialOptions());
    }

    public static Integer ack(Integer x, Integer y) {
        return x == 0 ?
                y + 1 :
                (y == 0 ?
                        ack(x - 1, 1) :
                        ack(x - 1, ack(x, y - 1)));
    }

    public static int intAck(int x, int y) {
        return x == 0 ?
                y + 1 :
                (y == 0 ?
                        intAck(x - 1, 1) :
                        intAck(x - 1, intAck(x, y - 1)));
    }

    public static MyInteger myIntegerAck(MyInteger x, MyInteger y) {
        return x.isZero()  ?
                y.inc() :
                (y.isZero()?
                        myIntegerAck(x.dec(), new MyInteger(1)) :
                        myIntegerAck(x.dec(), myIntegerAck(x, y.dec())));
    }

    public static final int Y1 = 1748;
    public static final int Y2 = 1897;
    public static final int Y3 = 8;

    static value class MyInteger{
        int value;

        MyInteger(int v) {
            value = v;
        }

        public int value(){
            return value;
        }

        boolean isZero(){
            return value==0;
        }

        MyInteger dec(){
            return new MyInteger(value-1);
        }

        MyInteger inc(){
            return new MyInteger(value+1);
        }
    }

    public int myIntegerFunc() {
        return myIntegerAck(new MyInteger(1), new MyInteger(Y1)).value() + myIntegerAck(new MyInteger(2), new MyInteger(Y2)).value() + myIntegerAck(new MyInteger(3), new MyInteger(Y3)).value();
    }

    public int intFunc() {
        return intAck(1, Y1) + intAck(2, Y2) + intAck(3, Y3);
    }

    public Integer func() {
        return ack(1, Y1) + ack(2, Y2) + ack(3, Y3);
    }

    private static final OptionValues WITHOUT_RECURSIVE_INLINING = new OptionValues(getInitialOptions(), GraalOptions.MaximumRecursiveInlining, 0);


    @Test
    public void ackerman(){
        getCode(getResolvedJavaMethod("myIntegerAck"), null, true, true, WITHOUT_RECURSIVE_INLINING);
        getCode(getResolvedJavaMethod("myIntegerFunc"), null, true, true, WITHOUT_RECURSIVE_INLINING);
        long sum = 0;
        for(int i = 0; i<10;i++){
            long time_1 = System.currentTimeMillis();
            myIntegerFunc();
            //runTest(WITHOUT_RECURSIVE_INLINING, EnumSet.allOf(DeoptimizationReason.class), "myIntegerFunc");
            long time_2 = System.currentTimeMillis();
            sum+=time_2-time_1;
        }
        sum/=10;
        int result = myIntegerFunc();
        System.out.println("average: "+sum+" result: " + result);

        getCode(getResolvedJavaMethod("intAck"), null, true, true, WITHOUT_RECURSIVE_INLINING);
        getCode(getResolvedJavaMethod("intFunc"), null, true, true, WITHOUT_RECURSIVE_INLINING);
        sum = 0;
        for(int i = 0; i<10;i++){
            long time_1 = System.currentTimeMillis();
            intFunc();
            long time_2 = System.currentTimeMillis();
            sum+=time_2-time_1;
        }
        sum/=10;
        result = intFunc();
        System.out.println("average: "+sum+" result: " + result);
    }

    @Test
    public void intAckerman(){
        getCode(getResolvedJavaMethod("intAck"), null, true, true, getInitialOptions());
        getCode(getResolvedJavaMethod("intFunc"), null, true, true, getInitialOptions());
        long sum = 0;
        for(int i = 0; i<1000;i++){
            long time_1 = System.currentTimeMillis();
            intFunc();
            long time_2 = System.currentTimeMillis();
            sum+=time_2-time_1;
        }
        sum/=1000;
        Integer result = intFunc();
        System.out.println("average: "+sum+" result: " + result);
    }

    public MyInteger createmyInteger(){
        return new MyInteger(1);
    }

    public static Integer testInteger(Integer x) {
        return x-1;
    }

    @Test
    public void testIntegerSnippet(){
        getCode(getResolvedJavaMethod("testInteger"), null, true, true, getInitialOptions());
    }

    public MyInteger reuseOop(){
        return createmyInteger();
    }

    @Test
    public void testReuseOop(){
        getCode(getResolvedJavaMethod("createmyInteger"), null, true, true, getInitialOptions());
        runTest(WITHOUT_INLINING, EnumSet.allOf(DeoptimizationReason.class), "reuseOop");
    }

    private static final OptionValues WITHOUT_PEA_INLINING = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");
    private static final OptionValues WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false);




}

package jdk.graal.compiler.valhalla;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.core.phases.HighTier;
import jdk.graal.compiler.hotspot.replacements.HotspotSnippetsOptions;
import jdk.graal.compiler.phases.common.UseTrappingNullChecksPhase;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.vm.ci.code.InstalledCode;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.internal.value.ValueClass;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value","java.base/java.lang.CharacterData"})
public class TestValueClasses extends JTTTest {

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

    static value class MyValueClass2Inline {
        double d;
        long l;

        @ForceInline
        public MyValueClass2Inline(double d, long l) {
            this.d = d;
            this.l = l;
        }

        @ForceInline
        static MyValueClass2Inline setD(MyValueClass2Inline v, double d) {
            return new MyValueClass2Inline(d, v.l);
        }

        @ForceInline
        static MyValueClass2Inline setL(MyValueClass2Inline v, long l) {
            return new MyValueClass2Inline(v.d, l);
        }

        @ForceInline
        public static MyValueClass2Inline createDefault() {
            return new MyValueClass2Inline(0, 0);
        }

        @ForceInline
        public static MyValueClass2Inline createWithFieldsInline(double d, long l) {
            MyValueClass2Inline v = MyValueClass2Inline.createDefault();
            v = MyValueClass2Inline.setD(v, d);
            v = MyValueClass2Inline.setL(v, l);
            return v;
        }
    }

    static value class MyValueClass2 extends MyAbstract {
        int x;
        byte y;
        MyValueClass2Inline v;

        @ForceInline
        public MyValueClass2(int x, byte y, MyValueClass2Inline v) {
            this.x = x;
            this.y = y;
            this.v = v;
        }

        @ForceInline
        public static MyValueClass2 createDefaultInline() {
            return new MyValueClass2(0, (byte)0, null);
        }

        @ForceInline
        public static MyValueClass2 createWithFieldsInline(int x, long y, double d) {
            MyValueClass2 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, (byte)x);
            v = setV(v, MyValueClass2Inline.createWithFieldsInline(d, y));
            return v;
        }

        @ForceInline
        public static MyValueClass2 createWithFieldsInline(int x, double d) {
            MyValueClass2 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, (byte)x);
            v = setV(v, MyValueClass2Inline.createWithFieldsInline(d, rL));
            return v;
        }

        public static MyValueClass2 createWithFieldsDontInline(int x, double d) {
            MyValueClass2 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, (byte)x);
            v = setV(v, MyValueClass2Inline.createWithFieldsInline(d, rL));
            return v;
        }

        @ForceInline
        public long hash() {
            return x + y + (long)v.d + v.l;
        }

        public long hashInterpreted() {
            return x + y + (long)v.d + v.l;
        }

        @ForceInline
        public void print() {
            System.out.print("x=" + x + ", y=" + y + ", d=" + v.d + ", l=" + v.l);
        }

        @ForceInline
        static MyValueClass2 setX(MyValueClass2 v, int x) {
            return new MyValueClass2(x, v.y, v.v);
        }

        @ForceInline
        static MyValueClass2 setY(MyValueClass2 v, byte y) {
            return new MyValueClass2(v.x, y, v.v);
        }

        @ForceInline
        static MyValueClass2 setV(MyValueClass2 v, MyValueClass2Inline vi) {
            return new MyValueClass2(v.x, v.y, vi);
        }
    }
    
    static value class MyValueClass1 extends MyAbstract {
        static int s;
        static long sf = rL;
        int x;
        long y;
        short z;
        Integer o;
        int[] oa;
        MyValueClass2 v1;
        MyValueClass2 v2;
        static MyValueClass2 v3 = MyValueClass2.createWithFieldsInline(rI, rD);
        MyValueClass2 v4;
        int c;

        @ForceInline
        public MyValueClass1(int x, long y, short z, Integer o, int[] oa, MyValueClass2 v1, MyValueClass2 v2, MyValueClass2 v4, int c) {
            s = 0;
            this.x = x;
            this.y = y;
            this.z = z;
            this.o = o;
            this.oa = oa;
            this.v1 = v1;
            this.v2 = v2;
            this.v4 = v4;
            this.c = c;
        }

        @DontInline
        static MyValueClass1 createDefaultDontInline() {
            return createDefaultInline();
        }

        @ForceInline
        static MyValueClass1 createDefaultInline() {
            return new MyValueClass1(0, 0, (short)0, null, null, null, null, null, 0);
        }

        @DontInline
        static MyValueClass1 createWithFieldsDontInline(int x, long y) {
            return createWithFieldsInline(x, y);
        }

        @ForceInline
        static MyValueClass1 createWithFieldsInline(int x, long y) {
            MyValueClass1 v = createDefaultInline();
            v = setX(v, x);
            v = setY(v, y);
            v = setZ(v, (short)x);
            // Don't use Integer.valueOf here to avoid control flow added by Integer cache check
            v = setO(v, new Integer(x));
            int[] oa = {x};
            v = setOA(v, oa);
            v = setV1(v, MyValueClass2.createWithFieldsInline(x, y, rD));
            v = setV2(v, MyValueClass2.createWithFieldsInline(x + 1, y + 1, rD + 1));
            v = setV4(v, MyValueClass2.createWithFieldsInline(x + 2, y + 2, rD + 2));
            v = setC(v, (int)(x+y));
            return v;
        }

        // Hash only primitive and inline type fields to avoid NullPointerException
        @ForceInline
        public long hashPrimitive() {
            return s + sf + x + y + z + c + v1.hash() + v2.hash() + v3.hash();
        }

        @ForceInline
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

        public long hashInterpreted() {
            return s + sf + x + y + z + o + oa[0] + c + v1.hashInterpreted() + v2.hashInterpreted() + v3.hashInterpreted() + v4.hashInterpreted();
        }

        @ForceInline
        public void print() {
            System.out.print("s=" + s + ", sf=" + sf + ", x=" + x + ", y=" + y + ", z=" + z + ", o=" + (o != null ? (Integer)o : "NULL") + ", oa=" + (oa != null ? oa[0] : "NULL") + ", v1[");
            v1.print();
            System.out.print("], v2[");
            v2.print();
            System.out.print("], v3[");
            v3.print();
            System.out.print("], v4[");
            v4.print();
            System.out.print("], c=" + c);
        }

        @ForceInline
        static MyValueClass1 setX(MyValueClass1 v, int x) {
            return new MyValueClass1(x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setY(MyValueClass1 v, long y) {
            return new MyValueClass1(v.x, y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setZ(MyValueClass1 v, short z) {
            return new MyValueClass1(v.x, v.y, z, v.o, v.oa, v.v1, v.v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setO(MyValueClass1 v, Integer o) {
            return new MyValueClass1(v.x, v.y, v.z, o, v.oa, v.v1, v.v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setOA(MyValueClass1 v, int[] oa) {
            return new MyValueClass1(v.x, v.y, v.z, v.o, oa, v.v1, v.v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setC(MyValueClass1 v, int c) {
            return new MyValueClass1(v.x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v.v4, c);
        }

        @ForceInline
        static MyValueClass1 setV1(MyValueClass1 v, MyValueClass2 v1) {
            return new MyValueClass1(v.x, v.y, v.z, v.o, v.oa, v1, v.v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setV2(MyValueClass1 v, MyValueClass2 v2) {
            return new MyValueClass1(v.x, v.y, v.z, v.o, v.oa, v.v1, v2, v.v4, v.c);
        }

        @ForceInline
        static MyValueClass1 setV4(MyValueClass1 v, MyValueClass2 v4) {
            return new MyValueClass1(v.x, v.y, v.z, v.o, v.oa, v.v1, v.v2, v4, v.c);
        }

        @DontInline
        void dontInline(MyValueClass1 arg) {

        }
    }

    static MyValueClass1 test14_field1;
    static MyValueClass1 test14_field2;

    // Test buffer checks emitted by acmp followed by buffering
    public boolean test14(MyValueClass1 vt1, MyValueClass1 vt2) {
        // Trigger buffer checks
        if (vt1 != vt2) {
            throw new RuntimeException("Should be equal");
        }
        if (vt2 != vt1) {
            throw new RuntimeException("Should be equal");
        }
        // Trigger buffering
        test14_field1 = vt1;
        test14_field2 = vt2;
        return vt1 == null;
    }

    private static final MyValueClass1 testValue1 = MyValueClass1.createWithFieldsInline(rI, rL);

    /*
    Thread[#14,JVMCI CompilerThread0,9,system]: Compilation of compiler.valhalla.inlinetypes.TestValueClasses.test14(MyValueClass1, MyValueClass1) @ -1 failed:
java.lang.NullPointerException: Cannot invoke "jdk.graal.compiler.nodes.ProfileData$BranchProbabilityData.getDesignatedSuccessorProbability()" because the return value of "jdk.graal.compiler.nodes.ShortCircuitOrNode.getShortCircuitProbability()" is null
	at jdk.graal.compiler/jdk.graal.compiler.phases.common.ExpandLogicPhase.expandBinary(ExpandLogicPhase.java:122)
	caused in ObjectEquals by
    LogicConstantNode.and(
    no branchprobability given
     */

    @Test
    public void run0() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod("test14"), null, true, true, getInitialOptions());
        c.executeVarargs(this, null, null);
    }

    @Test
    public void run1() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        //InstalledCode c = getCode(getResolvedJavaMethod(org.graalvm.collections.EconomicMapImpl.class, "setRawValue"), null, true, true, getInitialOptions());
        //c.executeVarargs(this);
    }

    @Test
    public void run5() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        //InstalledCode c = getCode(getResolvedJavaMethod(org.graalvm.collections.EconomicMapImpl.class, "setValue"), null, true, true, getInitialOptions());
        //c.executeVarargs(this);
    }

    @Test
    public void run6() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod(Class.forName("java.lang.CharacterData"), "of"), null, true, true, getInitialOptions());
        //c.executeVarargs(this);
    }

    @Test
    public void run7() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod(HashMap.class, "putVal"), null, true, true, getInitialOptions());
        //c.executeVarargs(this);
    }
    //jdk.internal.math.DoubleToDecimal::toDecimal



    static value class Container {
        int x = 0;
        Empty1 empty1;
        Empty2 empty2 = new Empty2();

        public Container(Empty1 val) {
            empty1 = val;
        }
    }

    static value class Empty2 {

    }

    static value class Empty1 {
        Empty2 empty2 = new Empty2();
    }

    @DontInline
    public static Empty1 test6_helper1(Empty1 vt) {
        return vt;
    }

    @DontInline
    public static Empty2 test6_helper2(Empty2 vt) {
        return vt;
    }

    @DontInline
    public static Container test6_helper3(Container vt) {
        return vt;
    }

    public Empty1 test6(Empty1 vt) {
//        Empty1 empty1 = test6_helper1(vt);
//        test6_helper2((empty1 != null) ? empty1.empty2 : null);
//        Container c = test6_helper3(new Container(empty1));
//        return c.empty1;
        Container c = new Container(vt);
        return c.empty1;
    }

    // causes materialization of nullable scalarized inline object as part of other object
    public void test6_verifier() {
        Assert.assertEquals(test6(null), null);
        //return test6(null) == null;
    }

    @Test
    public void run2() throws  Throwable{
        resetCache();
        new Container(null);
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod("test6_verifier"), null, true, true, getInitialOptions());
        //c.executeVarargs(this, null);
    }

    public static Container container;

    public Empty1 testCommitAlloc(){
        Empty1 empty = new Empty1();
        container = new Container(empty);
        return empty;
    }

    @Test
    public void run3() throws  Throwable{
        resetCache();
        new Empty1();
        new Empty2();
        new Container(null);
        InstalledCode c = getCode(getResolvedJavaMethod("testCommitAlloc"), null, true, true, getInitialOptions());
    }

    public Empty1 testCommitAlloc2(Empty1 empty){
        container = new Container(empty);
        return empty;
    }

    @Test
    public void run4() throws  Throwable{
        resetCache();
        new Empty1();
        new Empty2();
        new Container(null);
        InstalledCode c = getCode(getResolvedJavaMethod("testCommitAlloc2"), null, true, true, getInitialOptions());
    }

    static MyValueClass2Inline staticTest1;
    static MyValueClass2Inline staticTest2;

    public double testBranch(int number){
        MyValueClass2Inline a= MyValueClass2Inline.createDefault();
        //MyValueClass2Inline b= MyValueClass2Inline.createWithFieldsInline(rI, rL);
        if(number>3){
            staticTest1= a;
        }else{
            staticTest2= a;
        }
        return a.d;
    }

    @Test
    public void run9() throws  Throwable{
        resetCache();
        InstalledCode c = getCode(getResolvedJavaMethod("testBranch"), null, true, true, getInitialOptions());
    }

    public void testIdentityException(){
        Object a;
        a = MyValueClass2Inline.createDefault();
        synchronized (a){}

    }

    @Test
    public void run10() throws  Throwable{
        resetCache();
        InstalledCode c = getCode(getResolvedJavaMethod("testIdentityException"), null, true, true, getInitialOptions());
        try{
            testIdentityException();
        } catch (Exception e) {
            //
        }
        c = getCode(getResolvedJavaMethod("testIdentityException"), null, true, true, getInitialOptions());
        try{
            testIdentityException();
        } catch (Exception e) {
            //
        }
    }


    @Test
    public void run11() throws  Throwable{
        resetCache();
        //MyValue2.createWithFieldsInline
        //InstalledCode c = getCode(getResolvedJavaMethod(MyValue2.class, "createWithFieldsInline", int.class, double.class), null, true, false, DEMO_OPTIONS_WITHOUT_INLINING);
        InstalledCode c = getCode(getResolvedJavaMethod(String.class, "getBytes", byte[].class, int.class, byte.class), null, true, true, getInitialOptions());
        //c.executeVarargs(this);
    }
}

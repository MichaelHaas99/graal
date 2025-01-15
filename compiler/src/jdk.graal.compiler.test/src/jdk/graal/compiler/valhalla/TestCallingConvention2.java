package jdk.graal.compiler.valhalla;

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
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.vm.ci.meta.DeoptimizationReason;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class TestCallingConvention2 extends JTTTest {

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue1{
        int a;
        int b;
        Object c;
        double d;

        MyValue1(int a){
            this.a=a;
            b = 4;
            c = null;
            d=3.0;
        }

        MyValue1(){
            this.a=4;
            b = 4;
            c = null;
            d=4.0;
        }

        public MyValue1 test(MyValue1 value){
            return null;
        }
    }

    @DontInline
    public static MyValue1 test(MyValue1 value){
        return value;
    }


    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");

    @DontInline
    public static MyValue1 testStaticInvoke(){
        return test(new MyValue1());
    }



    /*
     * Tests the JVMCI interface concerning the scalarized calling convention
     * */
    @Test
    public void run0() throws Throwable {
        ResolvedJavaMethod method = getResolvedJavaMethod("test");
        method.getScalarizedParameter(0, true);
        Assert.assertTrue(method.hasScalarizedParameters());
        Assert.assertTrue(method.isScalarizedParameter(0));
        Assert.assertTrue(method.hasScalarizedReturn());
        Assert.assertFalse(method.hasScalarizedReceiver());
        System.out.println(Arrays.toString(method.getScalarizedReturn()));
        System.out.println(Arrays.toString(method.getScalarizedParameters(true)));
    }

    @Test
    public void run1() throws Throwable {
        ResolvedJavaMethod method = getResolvedJavaMethod(MyValue1.class, "test");
        Assert.assertTrue(method.hasScalarizedParameters());
        Assert.assertTrue(method.isScalarizedParameter(0));
        Assert.assertTrue(method.hasScalarizedReturn());
        Assert.assertTrue(method.hasScalarizedReceiver());
        //System.out.println();
        //System.out.println("parameters: "+Arrays.toString(method.getScalarizedParameters()));
        //System.out.println("return type: "+Arrays.toString(method.getScalarizedReturn()));
        System.out.println(getCallingConvention(method));

    }


    private static final OptionValues WITHOUT_PEA_INLINING = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");
    private static final OptionValues WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");
    private static final OptionValues DEMO_OPTIONS_WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, GraalOptions.InlineVTableStubs, false, GraalOptions.LimitInlinedInvokes, 0.0,UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");
    private static final OptionValues DEMO_OPTIONS_WITH_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, true, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");

    @Test
    public void run2() throws Throwable{
        runTest(DEMO_OPTIONS_WITHOUT_INLINING, "testStaticInvoke");
    }

    @Test
    public void run3() throws Throwable{
        runTest(DEMO_OPTIONS_WITHOUT_INLINING, "test", new MyValue1());
    }

    //mx unittest --verbose -XX:+UnlockDiagnosticVMOptions -Djdk.graal.Dump -Djdk.graal.Dump=:5 -XX:CompileCommand=compileonly,TestCallingConvention2::test -XX:-UseCompressedOops -XX:-InlineTypeReturnedAsFields jdk.graal.compiler.valhalla.TestCallingConvention2#run3

    @ImplicitlyConstructible
    @LooselyConsistentValue
    public value class MyValue3 {
        char c='0';
        byte bb = (byte) 3;
        short s =4;
        int i =6;
        long l=7;
        Object o=null;
        float f1=5.0f;
        double f2=9.0;
        float f3=11.0f;
        double f4=12.0;
        float f5=13.0f;
        double f6=15.0f;

        MyValue3 test(){
            return this;
        }

        MyValue3 testWithParameter(MyValue3 value){
            return this;
        }
    }




    public static MyValue3 testMyValue3(MyValue3 value, MyValue3 value2, MyValue3 value3){
        return value;
    }

    @Test
    public void run4() throws Throwable{
        runTest(DEMO_OPTIONS_WITHOUT_INLINING, "testMyValue3", new MyValue3(), new MyValue3(), new MyValue3());
    }

    public static MyValue3 testVirtual(MyValue3 value){
        value.test();
        return value.test();
    }

    @Test
    public void run5() throws Throwable{
        runTest(DEMO_OPTIONS_WITHOUT_INLINING,"testVirtual", new MyValue3());
    }

    @Test
    public void run6() throws Throwable{
        test(DEMO_OPTIONS_WITHOUT_INLINING,getResolvedJavaMethod(MyValue3.class, "test"), new MyValue3());
    }

    @Test
    public void run7() throws Throwable{
        test(DEMO_OPTIONS_WITHOUT_INLINING,getResolvedJavaMethod(MyValue3.class, "testWithParameter"), new MyValue3(), new MyValue3());
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

    static interface MyInterface {
        public long hash();
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    abstract static value class MyAbstract implements MyInterface {

    }

    public static final int  rI = 10;
    public static final long rL = 20;
    public static final double rD = 30.0;

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

        @DontInline
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

        @DontInline
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

    public long test10_interp(int i, MyValue2 v, long l) {
        return v.hash() + i + l;
    }

    public long test10(int i, MyValue2 v, long l) {
        return test10_interp(i, v, l);
    }

    @Test
    public void testTest10(){
        MyValue2 v = MyValue2.createWithFieldsInline(rI, rD);
        long result = test10(rI, v, rL);
        runTest("test10", 3, v,6L);
    }

    private static String getCallingConvention(ResolvedJavaMethod method){
        StringBuilder builder = new StringBuilder();
        builder.append(method.getDeclaringClass().getName());
        builder.append("\n");
        if(method.hasScalarizedReceiver()){
            builder.append("scalarized receiver:\n");
            builder.append(ArrayToString(method.getScalarizedReceiver()));
        }else{
            builder.append("non-scalarized receiver:\n");
            builder.append(method.getDeclaringClass());
        }
        builder.append("\n");
        builder.append("parameters: \n");
        for(int i=0; i<method.getSignature().getParameterCount(false); i++){
            if(method.isScalarizedParameter(i)){
                builder.append(ArrayToString(method.getScalarizedParameter(i)));
            }else{
                builder.append(method.getSignature().getParameterType(i, method.getDeclaringClass()));
            }
            builder.append("\n");
        }
        if(method.hasScalarizedReturn()){
            builder.append("scalarized return:\n");
            builder.append(ArrayToString(method.getScalarizedReturn()));
        }else{
            builder.append("non-scalarized return:\n");
            builder.append(method.getDeclaringClass());
        }
        return builder.toString();
    }

    private static String ArrayToString(Object[] array){
        return Arrays.toString(array);
    }
}

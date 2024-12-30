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
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.vm.ci.meta.DeoptimizationReason;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class TestCallingConvention extends JTTTest {

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
            this.a=3;
            b = 4;
            c = null;
            d=4.0;
        }

        public MyValue1 test(MyValue1 value){
            return null;
        }
    }

    public static MyValue1 test(MyValue1 value){
        return null;
    }

    public static MyValue1 test2(MyValue1 value){
        MyValue1 object = new MyValue1(4);
        global = object;
        return object;
    }

    public static MyValue1 test3(MyValue1 value){
        MyValue1 object = new MyValue1(4);
        return test2(object);
    }

    public static MyValue1 test4(MyValue1 value){
        MyValue1 object = test2(value);
        global = object;
        return object;
    }

    public static MyValue1 testz(MyValue1 value){
        MyValue1 object = new MyValue1(4);
        global = object;
        return object;
    }

    public static MyValue1 test5(MyValue1 value){
        MyValue1 object = testz(value);
        global = object;
        return object;
    }

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");


    /*
    * Tests the JVMCI interface concerning the scalarized calling convention
    * */
    @Test
    public void run0() throws Throwable {
        ResolvedJavaMethod method = getResolvedJavaMethod("test");
        Assert.assertTrue(method.hasScalarizedParameters());
        Assert.assertTrue(method.isScalarizedParameter(0));
        Assert.assertTrue(method.hasScalarizedReturn());
        Assert.assertFalse(method.hasScalarizedReceiver());
        System.out.println(Arrays.toString(method.getScalarizedReturn()));
        System.out.println(Arrays.toString(method.getScalarizedParameters()));
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

    public static MyValue1 global;

    @Test
    public void run2() throws Throwable {
        runTest("test2", new MyValue1());
    }

    @Test
    public void run3() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA, "test2", new MyValue1());
    }

    private static final OptionValues WITHOUT_PEA_INLINING = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false, HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false, HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");
    private static final OptionValues WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");

    @Test
    public void run4() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA_INLINING, "test3", new MyValue1());
    }

    @Test
    public void run5() throws Throwable {
        resetCache();
        runTest(WITHOUT_INLINING, "test3", new MyValue1());
    }

    @Test
    public void run6() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA_INLINING, "test4", new MyValue1());
    }

    @Test
    public void run7() throws Throwable {
        resetCache();
        runTest(WITHOUT_INLINING, "test4", new MyValue1());
    }

    @Test
    public void run8() throws Throwable {
        resetCache();
        runTest(WITHOUT_PEA_INLINING,EnumSet.allOf(DeoptimizationReason.class), "test5", new MyValue1());
    }

    @Test
    public void run9() throws Throwable {
        resetCache();
        runTest(WITHOUT_INLINING,EnumSet.allOf(DeoptimizationReason.class), "test5", new MyValue1());
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

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
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.vm.ci.meta.DeoptimizationReason;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class TestCallingConventionC1 extends JTTTest {

    // Helper methods and classes
    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class Point {
        int x;
        int y;
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int func() {
            return x + y;
        }

        @DontInline
        public int func_c1(Point p) {
            return x + y + p.x + p.y;
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class FloatPoint {
        float x;
        float y;
        public FloatPoint(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class DoublePoint {
        double x;
        double y;
        public DoublePoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
    @NullRestricted
    static FloatPoint floatPointField = new FloatPoint(123.456f, 789.012f);
    @NullRestricted
    static DoublePoint doublePointField = new DoublePoint(123.456, 789.012);

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class EightFloats {
        float f1, f2, f3, f4, f5, f6, f7, f8;
        public EightFloats() {
            f1 = 1.1f;
            f2 = 2.2f;
            f3 = 3.3f;
            f4 = 4.4f;
            f5 = 5.5f;
            f6 = 6.6f;
            f7 = 7.7f;
            f8 = 8.8f;
        }

        public void dummy(){

        }
    }
    @NullRestricted
    static Point pointField  = new Point(123, 456);

    static EightFloats eightFloatsField = new EightFloats();

    // C2->C1 invokestatic, circular dependency (between rdi and first stack slot on x64)

    static value class MyValue1{
        int a=3;
        float c = 4;
        Object i = null;

        public void dummy(){

        }
    }



    @DontInline
    private static float test42_helper(EightFloats ep1,
                                       EightFloats ep2,
                                       EightFloats ep3,// (xmm0 ... xmm7) -> rsi
                                       Point p2,        // (rsi, rdx) -> rdx
                                       int i3,          // rcx -> rcx
                                       int i4,          // r8 -> r8
                                       int i5,          // r9 -> r9
                                       FloatPoint fp6,  // (stk[0], stk[1]) -> rdi   ** circ depend
                                       int i7,
                                       MyValue1 myValue1)          // rdi -> stk[0]             ** circ depend
    {

        ep1.dummy();
        return ep1.f1 + ep1.f2 + ep1.f3 + ep1.f4 + ep1.f5 + ep1.f6 + ep1.f7 + ep1.f8 +
                p2.x + p2.y + i3 + i4 + i5 + fp6.x + fp6.y + i7;
    }

    private static final OptionValues DEMO_OPTIONS_WITHOUT_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, false, GraalOptions.InlineMonomorphicCalls, false, GraalOptions.InlinePolymorphicCalls, false, GraalOptions.InlineMegamorphicCalls, false, GraalOptions.InlineVTableStubs, false, GraalOptions.LimitInlinedInvokes, 0.0,UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");
    private static final OptionValues DEMO_OPTIONS_WITH_INLINING = new OptionValues(getInitialOptions(), HighTier.Options.Inline, true, UseTrappingNullChecksPhase.Options.UseTrappingNullChecks, false,HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter, "test121");

    @Test
    public void run2() throws Throwable{
        runTest(DEMO_OPTIONS_WITHOUT_INLINING,EnumSet.allOf(DeoptimizationReason.class), "test42_helper",eightFloatsField,eightFloatsField,eightFloatsField, pointField, 3, 4, 5, floatPointField, 7, new MyValue1());
    }

    static interface Intf {
        public int func1(int a, int b);
        public int func2(int a, int b, Point p);
    }

    static class MyImplPojo0 implements Intf {
        int field = 0;
        public int func1(int a, int b)             { return field + a + b + 1; }
        public int func2(int a, int b, Point p)     { return field + a + b + p.x + p.y + 1; }
    }

    static class MyImplPojo3 implements Intf {
        int field = 0;
        @DontInline // will be compiled with counters
        public int func1(int a, int b)             { return field + a + b + 1; }
        @DontInline // will be compiled with counters
        public int func2(int a, int b, Point p)     { return field + a + b + p.x + p.y + 1; }
    }

    public int test109(Intf intf, int a, int b) {
        return intf.func2(a, b, pointField);
    }

    public void test109_verifier() {
        Intf intf1 = new MyImplPojo0();
        Intf intf2 = new MyImplPojo3();

        for (int i = 0; i < 1000; i++) {
            test109(intf1, 123, 456);
        }
        for (int i = 0; i < 500_000; i++) {
            // Run enough loops so that test109 will be compiled by C2.
            if (i % 30 == 0) {
                // This will indirectly call MyImplPojo3.func2, but the call frequency is low, so
                // test109 will be compiled by C2, but MyImplPojo3.func2 will compiled by C1 only.
                int result = test109(intf2, 123, 456) + i;
                Assert.assertEquals(result, intf2.func2(123, 456, pointField) + i);
            } else {
                // Call test109 with a mix of intf1 and intf2, so C2 will use a virtual call (not an optimized call)
                // for the invokeinterface bytecode in test109.
                test109(intf1, 123, 456);
            }
        }
    }

    public int test108(Intf intf, int a, int b) {
        return intf.func2(a, b, pointField);
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyImplVal1X implements Intf {
        int field;
        MyImplVal1X() {
            field = 11000;
        }

        public int func1(int a, int b)             { return field + a + b + 300; }

        public int func2(int a, int b, Point p)    { return field + a + b + p.x + p.y + 300; }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyImplVal2X implements Intf {
        int field;
        MyImplVal2X() {
            field = 12000;
        }

        @DontInline // will be compiled with counters
        public int func1(int a, int b)             { return field + a + b + 300; }

        @DontInline // will be compiled with counters
        public int func2(int a, int b, Point p)    { return field + a + b + p.x + p.y + 300; }
    }

    //@Test
    public void test108_verifier() {
        Intf intf1 = new MyImplVal1X();
        Intf intf2 = new MyImplVal2X();

        for (int i = 0; i < 1000; i++) {
            test108(intf1, 123, 456);
        }
        for (int i = 0; i < 500_000; i++) {
            // Run enough loops so that test108 will be compiled by C2.
            if (i % 30 == 0) {
                // This will indirectly call MyImplVal2X.func2, but the call frequency is low, so
                // test108 will be compiled by C2, but MyImplVal2X.func2 will compiled by C1 only.
                int result = test108(intf2, 123, 456) + i;
                Assert.assertEquals(result, intf2.func2(123, 456, pointField) + i);
            } else {
                // Call test108 with a mix of intf1 and intf2, so C2 will use a virtual call (not an optimized call)
                // for the invokeinterface bytecode in test108.
                test108(intf1, 123, 456);
            }
        }

    }

    @Test
    public void test108(){
        getCode(getResolvedJavaMethod("test108_verifier"), null, true, false, getInitialOptions());
    }


}

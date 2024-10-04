package jdk.graal.compiler.jtt.bytecode;

import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;

public class BC_ifacmpeq3 extends JTTTest {

    private static value class InlineTypeTestClass {
    }

    public static boolean test(int arg){
        InlineTypeTestClass inlineTypeObject1 = new InlineTypeTestClass();
        return testNullComparison(arg, inlineTypeObject1, inlineTypeObject1, null, null);
    }

    public static boolean testNullComparison(int arg, Object nonNull1, Object nonNull2, Object null1, Object null2) {

        boolean result;
        if (arg == 0) {
            result = nonNull1 == nonNull2;
        } else if (arg == 1) {
            result = nonNull1 == null1;
        } else if (arg == 2) {
            result = nonNull2 == null1;
        } else {
            result = null1 == null2;
        }
        return result;
    }

    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);


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

    @Test
    public void run6() throws Throwable {
        runTest(WITHOUT_PEA, "test", 3);
    }

    @Test
    public void run7() throws Throwable {
        runTest("test", 3);
    }

}
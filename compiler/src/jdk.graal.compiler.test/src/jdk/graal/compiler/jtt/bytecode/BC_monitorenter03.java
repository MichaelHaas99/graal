package jdk.graal.compiler.jtt.bytecode;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;
import org.junit.Test;

public class BC_monitorenter03 extends JTTTest {

    static value class InlineTypeTestClass { }


    public static void test(Object arg) {
            synchronized (arg) {
            }

    }
    private static final OptionValues WITHOUT_PEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);

    @Test
    public void run0() throws Throwable {
        runTest("test", new Object());
    }

    @Test
    public void run1() throws Throwable {
        runTest(WITHOUT_PEA, "test", new Object());
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", new String());
    }

    @Test
    public void run3() throws Throwable {
        runTest(WITHOUT_PEA, "test", new String());
    }

    @Test
    public void run4() throws Throwable {
        runTest("test", new InlineTypeTestClass());
    }

    @Test
    public void run5() throws Throwable {
        runTest(WITHOUT_PEA, "test", new InlineTypeTestClass());
    }

    @Test
    public void run6() throws Throwable {
        runTest("test", Integer.valueOf(42));
    }

    @Test
    public void run7() throws Throwable {
        for(int i =0; i<1000;i++){
            try{
                test(Integer.valueOf(42));
            } catch (Exception e) {

            }
        }

        runTest(WITHOUT_PEA, "test", Integer.valueOf(42));
    }

}

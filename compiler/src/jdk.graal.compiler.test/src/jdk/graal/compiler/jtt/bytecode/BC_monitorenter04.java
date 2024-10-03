package jdk.graal.compiler.jtt.bytecode;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;
import org.junit.Test;

public class BC_monitorenter04 extends JTTTest {

    static value class MyValue { }


    public static void test(Object arg) {
            synchronized (arg) {
            }

    }
    private static final OptionValues withoutPEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);

    @Test
    public void run0() throws Throwable {
        runTest("test", new Object());
    }

    @Test
    public void run1() throws Throwable {
        runTest(withoutPEA, "test", new Object());
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", new String());
    }

    @Test
    public void run3() throws Throwable {
        runTest(withoutPEA, "test", new String());
    }

    @Test
    public void run4() throws Throwable {
        runTest("test", new MyValue());
    }

    @Test
    public void run5() throws Throwable {
        runTest(withoutPEA, "test", new MyValue());
    }

    @Test
    public void run6() throws Throwable {
        runTest("test", Integer.valueOf(42));
    }

    @Test
    public void run7() throws Throwable {
        runTest(withoutPEA, "test", Integer.valueOf(42));
    }

}

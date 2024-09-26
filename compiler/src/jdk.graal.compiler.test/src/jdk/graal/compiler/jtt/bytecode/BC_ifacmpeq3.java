package jdk.graal.compiler.jtt.bytecode;

import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;

public class BC_ifacmpeq3 extends JTTTest {

    private static value class ValueTestClass {
        final int x;
        final float y;
        final ValueTestClass z;

        ValueTestClass(int x, float y, ValueTestClass z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        ValueTestClass() {
            this(2, 3.0f, null);
        }
    }

    public static boolean test(int arg) {
        ValueTestClass valueObject1 = new ValueTestClass();
        ValueTestClass valueObject2 = new ValueTestClass(2, 3.0f, valueObject1);
        ValueTestClass valueObject3 = new ValueTestClass(2+arg, 3.0f, valueObject1);
        ValueTestClass valueObject4 = new ValueTestClass(2, 3.0f, valueObject1);

        boolean result;
        if (arg == 2) {
            result = valueObject4 == valueObject3;
        } else {
            result = valueObject3 == valueObject3;
        }
        return result;
    }

    @Test
    public void run0() throws Throwable {
        OptionValues options = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);
        runTest(options, "test", 2);
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", 2);
    }

    @Test
    public void run2() throws Throwable {
        OptionValues options = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);
        runTest(options, "test", 3);
    }

    @Test
    public void run3() throws Throwable {
        runTest("test", 3);
    }
}
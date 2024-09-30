package jdk.graal.compiler.jtt.bytecode;

import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;

public class BC_ifacmpeq3 extends JTTTest {

    private static value class InlineTypeTestClass {
        static Object escapedObject = new Object();
        static Object escapedObject2 = escapedObject;

        final int x;
        final float y;
        final Object z;

        InlineTypeTestClass(int x, float y, Object z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        InlineTypeTestClass() {
            this(2, 3.0f, null);
        }
    }

    public static boolean test(int arg) {

        InlineTypeTestClass inlineTypeObject1 = new InlineTypeTestClass();
        InlineTypeTestClass inlineTypeObject2 = new InlineTypeTestClass(2, 3.0f, inlineTypeObject1);
        InlineTypeTestClass inlineTypeObject3 = new InlineTypeTestClass(3, 3.0f + 2.0f, inlineTypeObject1);
        InlineTypeTestClass inlineTypeObject4 = new InlineTypeTestClass(2, 3.0f, inlineTypeObject1);
        InlineTypeTestClass inlineTypeObject5 = new InlineTypeTestClass(2 + arg, 3.0f, inlineTypeObject1);
        InlineTypeTestClass inlineTypeObject6 = new InlineTypeTestClass(2, 3.0f, InlineTypeTestClass.escapedObject);
        InlineTypeTestClass inlineTypeObject7 = new InlineTypeTestClass(2, 3.0f, InlineTypeTestClass.escapedObject2);

        boolean result;
        if (arg == 0) {
            result = inlineTypeObject3 == inlineTypeObject3;
        } else if (arg == 1) {
            result = inlineTypeObject3 == inlineTypeObject4;
        } else if (arg == 2) {
            result = inlineTypeObject4 == inlineTypeObject5;
        } else if (arg == 3) {
            result = inlineTypeObject1 == inlineTypeObject2;
        } else if (arg == 4) {
            result = inlineTypeObject6 == inlineTypeObject7;
        } else {
            result = inlineTypeObject3 == inlineTypeObject5;
        }
        return result;
    }

    private static final OptionValues withoutPEA = new OptionValues(getInitialOptions(), GraalOptions.PartialEscapeAnalysis, false);

    @Test
    public void run0() throws Throwable {
        runTest(withoutPEA, "test", 0);
    }

    @Test
    public void run1() throws Throwable {
        runTest(withoutPEA, "test", 1);
    }

    @Test
    public void run2() throws Throwable {
        runTest(withoutPEA, "test", 2);
    }

    @Test
    public void run3() throws Throwable {
        runTest(withoutPEA, "test", 3);
    }

    @Test
    public void run4() throws Throwable {
        runTest(withoutPEA, "test", 4);
    }

    @Test
    public void run5() throws Throwable {
        runTest(withoutPEA, "test", 5);
    }

    @Test
    public void run6() throws Throwable {
        runTest("test", 0);
    }

    @Test
    public void run7() throws Throwable {
        runTest("test", 1);
    }

    @Test
    public void run8() throws Throwable {
        runTest("test", 2);
    }

    @Test
    public void run9() throws Throwable {
        runTest("test", 3);
    }

    @Test
    public void run10() throws Throwable {
        runTest("test", 4);
    }

    @Test
    public void run11() throws Throwable {
        runTest("test", 5);
    }

}
package jdk.graal.compiler.core.test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import org.junit.Test;

public class CheckObjectEqualsTest extends GraalCompilerTest {

    public static final Object NULL = null;
    public static final Object testObject1 = new Object();
    public static final Object testObject2 = new Object();
    public static final Object inlineTypeTestObject = new InlineTypeTestClass();
    public static final OptionValues printProfiling = new OptionValues(getInitialOptions(), GraalOptions.PrintProfilingInformation, true);

    public static value class InlineTypeTestClass {
    }

    public static boolean snippetNoProfile(Object a, Object b) {
        return a==b;
    }

    public static boolean snippetLeftAlwaysNull(Object a, Object b) {
        return a==b;
    }

    public static boolean snippetRightAlwaysNull(Object a, Object b) {
        return a==b;
    }

    public static boolean snippetLeftInlineType(Object a, Object b) {
        return a==b;
    }

    public static boolean snippetRightInlineType(Object a, Object b) {
        return a==b;
    }

    @Test
    public void testNoProfile() throws InvalidInstalledCodeException {
        resetCache();
        InstalledCode c = getCode(getResolvedJavaMethod("snippetNoProfile"), null, true, false, getInitialOptions());
        assert c.executeVarargs(NULL, NULL).equals(true);
        assert c.isValid();
    }

    @Test
    public void testLeftAlwaysNull() throws InvalidInstalledCodeException {
        resetCache();
        for (int i = 0; i < 10000; i++) {
            test("snippetLeftAlwaysNull", NULL, testObject1);
        }
        InstalledCode c = getCode(getResolvedJavaMethod("snippetLeftAlwaysNull"), null, true, false, getInitialOptions());
        assert c.executeVarargs(testObject1, testObject2).equals(false);
        assert !c.isValid();
    }

    @Test
    public void testSnippetRightAlwaysNull() throws InvalidInstalledCodeException {
        resetCache();
        for (int i = 0; i < 10000; i++) {
            test("snippetRightAlwaysNull", testObject1, NULL);
        }
        InstalledCode c = getCode(getResolvedJavaMethod("snippetRightAlwaysNull"), null, true, false, getInitialOptions());
        assert c.executeVarargs(testObject1, testObject2).equals(false);
        assert !c.isValid();
    }

    @Test
    public void testLeftInlineType() throws InvalidInstalledCodeException {
        resetCache();
        for (int i = 0; i < 10000; i++) {
            test("snippetLeftInlineType", testObject1, inlineTypeTestObject);
            //test("snippetLeftInlineType", inlineTypeTestObject, testObject2);
        }
        InstalledCode c = getCode(getResolvedJavaMethod("snippetLeftInlineType"), null, true, false, printProfiling);
        assert c.executeVarargs(inlineTypeTestObject, testObject1).equals(false);
        assert !c.isValid();
    }

    @Test
    public void testRightInlineType() throws InvalidInstalledCodeException {
        resetCache();
        for (int i = 0; i < 10000; i++) {
            test("snippetRightInlineType", inlineTypeTestObject, testObject1);
        }
        InstalledCode c = getCode(getResolvedJavaMethod("snippetRightInlineType"), null, true, false, getInitialOptions());
        assert c.executeVarargs(testObject1, inlineTypeTestObject).equals(false);
        assert !c.isValid();
    }

}

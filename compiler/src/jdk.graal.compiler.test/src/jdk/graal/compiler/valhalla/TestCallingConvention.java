package jdk.graal.compiler.valhalla;

import java.util.Arrays;
import java.util.EnumSet;

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
        int a=3;
        int b = 4;
        Object c = new Object();
    }

    public static MyValue1 test(MyValue1 value){
        return null;
    }

    /*
    * Tests the JVMCI interface concerning the scalarized calling convention
    * */
    @Test
    public void run0() throws Throwable {
        ResolvedJavaMethod method = getResolvedJavaMethod("test");
        Assert.assertTrue(method.hasScalarizedParameters());
        Assert.assertTrue(method.isScalarizedParameter(0));
        Assert.assertTrue(method.hasScalarizedReturn());
        System.out.println(Arrays.toString(method.getScalarizedReturn()));
        System.out.println(Arrays.toString(method.getScalarizedParameters()));
    }
}

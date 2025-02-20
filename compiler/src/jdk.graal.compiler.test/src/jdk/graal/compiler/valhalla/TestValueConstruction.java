package jdk.graal.compiler.valhalla;

import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.vm.ci.code.InstalledCode;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.NullRestricted;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.internal.value.ValueClass;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})

public class TestValueConstruction extends JTTTest {

    // Trigger deopts at various places
    static void checkDeopt(int deoptNum) {

    }
    static abstract value class MyAbstract1 { }

    static value class MyValue2 extends MyAbstract1 {
        int x;

        public MyValue2(int x) {
            checkDeopt(0);
            this.x = x;
            checkDeopt(1);
            super();
            checkDeopt(2);
        }

        public String toString() {
            return "x: " + x;
        }
    }

    static abstract value class MyAbstract2 {
        public MyAbstract2(int x) {
            checkDeopt(0);
        }
    }
    static value class MyValue3 extends MyAbstract2 {
        int x;

        public MyValue3(int x) {
            checkDeopt(1);
            this(x, 0);
            helper1(this, x, 2); // 'this' escapes through argument
            helper2(x, 3); // 'this' escapes through receiver
            checkDeopt(4);
        }

        public MyValue3(int x, int unused) {
            this.x = helper3(x, 5);
            super(x);
            helper1(this, x, 6); // 'this' escapes through argument
            helper2(x, 7); // 'this' escapes through receiver
            checkDeopt(8);
        }

        public static void helper1(MyValue3 obj, int x, int deoptNum) {
            checkDeopt(deoptNum);
            Assert.assertEquals(obj.x, x);
        }

        public void helper2(int x, int deoptNum) {
            checkDeopt(deoptNum);
            Assert.assertEquals(this.x, x);
        }

        public static int helper3(int x, int deoptNum) {
            checkDeopt(deoptNum);
            return x;
        }

        public String toString() {
            return "x: " + x;
        }
    }

    public static MyValue3 test12(int x) {
        MyValue3 v = new MyValue3(x);
        checkDeopt(9);
        v = new MyValue3(x);
        return v;
    }

    @Test
    public void run4() throws  Throwable{
        resetCache();
        InstalledCode c = getCode(getResolvedJavaMethod("test12"), null, true, true, getInitialOptions());
    }

}

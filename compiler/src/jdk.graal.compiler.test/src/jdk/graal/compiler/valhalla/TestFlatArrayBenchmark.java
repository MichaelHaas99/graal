package jdk.graal.compiler.valhalla;

import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.internal.vm.annotation.NullRestricted;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.test.AddExports;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import jdk.internal.value.ValueClass;

@AddExports({"java.base/jdk.internal.vm.annotation", "java.base/jdk.internal.value"})
public class TestFlatArrayBenchmark extends JTTTest {

    @ImplicitlyConstructible
    static value class Q32int {

        public final int v0;

        public Q32int() {
            v0 = 0;
        }

        public Q32int(int val) {
            this.v0 = val;
        }


        public int intValue() {
            return v0;
        }

    }

    static Q32int[] array = (Q32int[]) ValueClass.newNullRestrictedArray(Q32int.class, 1_000);
    public static Q32int[] bubblesort() {
        Q32int temp;
        for (int i = 1; i < array.length; i++) {
            array[i].intValue();
//            for (int j = 0; j < array.length - i; j++) {
//                if (array[j].intValue() > array[j + 1].intValue()) {
//                    temp = array[j];
//                    array[j] = array[j + 1];
//                    array[j + 1] = temp;
//                }
//            }
        }
        return array;
    }

    @Test
    public void run1() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("bubblesort"), null, true, true, getInitialOptions());
        code.executeVarargs();
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue1 {
        static int cnt = 0;
        int x;
        @NullRestricted
        MyValue2 vtField1;
        MyValue2 vtField2;

        public MyValue1() {
            cnt++;
            x = cnt;
            vtField1 = new MyValue2();
            vtField2 = new MyValue2();
        }

        public MyValue1(int x, MyValue2 vtField1, MyValue2 vtField2) {
            this.x = x;
            this.vtField1 = vtField1;
            this.vtField2 = vtField2;
        }

        public int hash() {
            return x + vtField1.x + vtField2.x;
        }

        public MyValue1 testWithField(int x) {
            return new MyValue1(x, vtField1, vtField2);
        }

        public static MyValue1 makeDefault() {
            return new MyValue1(0, MyValue2.makeDefault(), null);
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    static value class MyValue2 {
        static int cnt = 0;
        int x;

        public MyValue2() {
            cnt++;
            x = cnt;
        }

        public MyValue2(int x) {
            this.x = x;
        }

        public static MyValue2 makeDefault() {
            return new MyValue2(0);
        }
    }

    public static void test(MyValue1 val){

    }

    @Test
    public void run2() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test"), null, true, true, getInitialOptions());
    }

    public static boolean test2(Q32int val1, Object val2, Object[] array){
        int cnt = array.length;
        boolean z = val1 == val2;
        return z && val1 == val2 && array.length +cnt > 1;
    }

    @Test
    public void run3() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test2"), null, true, true, getInitialOptions());
    }


}

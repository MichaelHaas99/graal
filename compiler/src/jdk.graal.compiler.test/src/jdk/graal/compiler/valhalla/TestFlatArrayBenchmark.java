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

    public static void testArrayStore(Object[] array){
        array[0] = new Object();
    }

    @Test
    public void run4() throws InvalidInstalledCodeException {
        try{
            testArrayStore(new Integer[3]);
        } catch (Exception e) {

        }
        InstalledCode code = getCode(getResolvedJavaMethod("testArrayStore"), null, true, true, getInitialOptions());
    }

    public static Integer[] testArrayCopy(Integer[] array){
        Integer[] copy = (Integer[])ValueClass.newNullRestrictedArray(Integer.class, array.length);
        System.arraycopy(array, 0, array, 0, array.length);
        return copy;
    }

    @Test
    public void run5() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testArrayCopy"), null, true, true, getInitialOptions());
    }

    public static value class Line {
        @NullRestricted
        Point p1;
        @NullRestricted
        Point p2;

        public Line(Point p1, Point p2) {
            this.p1 = p1;
            this.p2 = p2;
        }
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    public static value class Point {
        int x;
        int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public Object clone() throws CloneNotSupportedException {
            // Returning a clone of the current object
            return super.clone();
        }
    }

    static final int N = 100;
    static Line[] lineArray = new Line[N];


    public static int findDuplicates() {
        int count = 0;
        for (int i = 0; i < lineArray.length; i++) {
            for (int j = 0; j < lineArray.length; j++) {
                if (lineArray[i] == lineArray[j]) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    public void run6() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("findDuplicates"), null, true, true, getInitialOptions());
    }

    public static boolean equality(Line a, Line b) {
        return a == b;
    }

    static value class OneInstance{
        int a=3;
    }

    public static boolean equality2(OneInstance a, OneInstance b) {
        boolean result= false;
        for(int i = 0;i<1000;i++){
            result =  a == b;
            result &= a == b;
        }
        return result;
    }

    public static int hash(Point p) {
        return System.identityHashCode(p);
    }

    public static Object clone(Point p) throws CloneNotSupportedException {
        return p.clone();
    }

    @Test
    public void run7() throws InvalidInstalledCodeException {
        Object result = getCode(getResolvedJavaMethod("hash"), null, true, true, getInitialOptions());
        hash(new Point(3,4));
        int a = 3;
    }

    @Test
    public void run8() throws InvalidInstalledCodeException {
        Object result = getCode(getResolvedJavaMethod("equality"), null, true, true, getInitialOptions());
        boolean equal = equality(new Line(new Point(3,4), new Point(3,5)), new Line(new Point(3,4), new Point(3,5)));
        System.out.println(equal);
        int a = 3;
    }

    @Test
    public void run10() throws InvalidInstalledCodeException {
        Object result = getCode(getResolvedJavaMethod("equality2"), null, true, true, getInitialOptions());
        //equality(new Point(3,4), new Point(3,4));
        int a = 3;
    }

    @Test
    public void run9() throws InvalidInstalledCodeException {
        getCode(getResolvedJavaMethod("clone"), null, true, true, getInitialOptions());
    }


}

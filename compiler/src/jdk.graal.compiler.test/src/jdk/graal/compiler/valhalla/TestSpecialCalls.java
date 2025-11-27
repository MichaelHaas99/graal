package jdk.graal.compiler.valhalla;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.test.AddExports;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;

import jdk.internal.value.ValueClass;

import java.util.AbstractCollection;

@AddExports({"java.base/jdk.internal.vm.annotation","java.base/jdk.internal.value"})
public class TestSpecialCalls extends JTTTest {
    static class A {
        void testMethod() {

        }
    }

    static class B extends A {
    }

    static class C extends B {
        @Override
        void testMethod() {
        }
    }

    public static void testVirtual() {
        new B().testMethod();
        new C().testMethod();
    }

    @Test
    public void run0() {
        getCode(getResolvedJavaMethod("testVirtual"), null, true, true, getInitialOptions());
    }

    static class D {
        int a;
    }

    public static void testLoad(D d) {
        if (d != null) {
            int s = d.a;
        }
    }

    @Test
    public void run1() {
        getCode(getResolvedJavaMethod("testLoad"), null, true, true, getInitialOptions());
    }

    static class E {
        Integer a;
    }

    public static void testIntegerLoad(E e) {
        if (e != null) {
            Integer s = e.a;
        }
    }

    @Test
    public void run2() {
        getCode(getResolvedJavaMethod("testIntegerLoad"), null, true, true, getInitialOptions());
    }

    static value class F{
        int a;

        F(int a) {
            this.a = a;
        }
        F() {
            this.a = 8;
        }
    }

    static class G{
        F f;
        G(F f){
            this.f = f;
        }
    }

    static F globalF;
    static F globalF2;
    static F globalF3;
    public static int testEA(boolean condition, F f) {
        F localF;
        if(condition) {
            localF = new F(3);
            globalF = localF;
        }else{
            localF = new F(4);
        }
        G g = new G(localF);
        int b = localF.a;
        return b;
    }

    @Test
    public void run3() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    public static int testEA2(boolean condition, F f) {
        F localF = new F(3);;
        if(condition) {
            globalF = localF;
        }else{
            localF = new F(4);
        }
        G g = new G(localF);
        int b = localF.a;
        return b;
    }

    @Test
    public void run4() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA2"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    public static int testEA3(boolean condition, boolean condition2, F f) {
        F localF;
        int a =0;
        if(condition) {
            if(condition2) {
                localF = new F(3);
                globalF = localF;
            }else{
                localF = f;
            }
            a = localF.a;

        }else{
            localF = new F(4);
        }
        G g = new G(localF);
        int b = localF.a;
        return b+a;
    }

    @Test
    public void run5() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA3"), null, true, true, getInitialOptions());
        code.executeVarargs(true, true, new F());
        code.executeVarargs(false, true, new F());
    }

    public static int testEA4(boolean condition, F f) {
        F localF = new F(2);
        if(condition) {
            localF = f;
            globalF = localF;
            int s = localF.a;
        }else{
        }
        G g = new G(localF);
        int b = localF.a;
        return b;
    }

    @Test
    public void run6() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA4"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    public static int testEA5(boolean condition, F f) {
        F localF = new F(2);
        if(condition) {
            localF = f;
            globalF = localF;
            int s = localF.a;
        }else{
            globalF3 = localF;
        }
        G g = new G(localF);
        globalF2 = localF;
        int b = localF.a;
        return b;
    }


    @Test
    public void run7() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA5"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    public static F testEA6(boolean condition, F f) {
        F localF = new F(3);
        if(condition) {
            globalF = localF;
        }
        return localF;
    }

    @Test
    public void run8() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA6"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    static value class H{
        F f = new F(3);
    }

    static H globalH;
    public static H testEA7(boolean condition, H h) {
        H localH = new H();
        if(condition) {
            globalH = localH;
        }
        return localH;
    }

    @Test
    public void run9() throws InvalidInstalledCodeException {
        testEA7(true, new H());
        InstalledCode code = getCode(getResolvedJavaMethod("testEA7"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new H());
        code.executeVarargs(false, new H());
    }

    static F globalFf;
    public static int testEA8(boolean condition, H h, H h2) {
        H localH = h;
        if(condition) {
            globalH = h;
            localH = new H();
        }
        F a = h.f;
        globalFf = a;
        int i = 0;
        if(condition) {
            i= 3;
        }else{
            i = 4;
        }
        globalFf = localH.f;
        return i;
    }

    @Test
    public void run10() throws InvalidInstalledCodeException {
        testEA7(true, new H());
        InstalledCode code = getCode(getResolvedJavaMethod("testEA8"), null, true, true, getInitialOptions());
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    value class MyValue1 {
        //int x = 42;
        int[] array = new int[1];
    }

    void test3(MyValue1[] array) {
        for (int i = 0; i < array.length; ++i) {
            array[i] = new MyValue1();
        }
//        for (int i = 0; i < 1000; ++i) {
//
//        }
    }

    @Test
    public void run11() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test3"), null, true, true, getInitialOptions());
    }

    @ImplicitlyConstructible
    @LooselyConsistentValue
    value class MyValue4 {
        short b = 2;
        int c = 8;
    }

    void test14(boolean b, MyValue4 val) {
        for (int i = 0; i < 10; ++i) {
            if (b) {
                val = new MyValue4();
            }
            MyValue4[] array = (MyValue4[])ValueClass.newNullRestrictedArray(MyValue4.class, 1);
            array[0] = val;

            for (int j = 0; j < 5; ++j) {
                for (int k = 0; k < 5; ++k) {
                }
            }
        }
    }

    @Test
    public void run12() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test14"), null, true, true, getInitialOptions());
    }

    MyValue1 test2(MyValue1[] array) {
        MyValue1 res = new MyValue1();
        for (int i = 0; i < array.length; ++i) {
            res = array[i];
        }
        return res;
    }

    @Test
    public void run13() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test2"), null, true, true, getInitialOptions());
    }

    static Object[] oArrArr = new Object[100][100];
    static Object[] oArr = new Object[100];
    static void testSubTypeCheck() {
        for (int i = 0; i < 100; i++) {
            Object arrayElement = oArrArr[i];
            oArr = (Object[])arrayElement;
        }
    }

    @Test
    public void run14() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testSubTypeCheck"), null, true, true, getInitialOptions());
    }

    static class WrapperClass{
        Object o;
        int a;
        WrapperClass(Object o, int a){
            this.o = o;
            this.a = a;
        }
    }

    public static Object testWrapper(boolean condition){
        WrapperClass wrapperClass = new WrapperClass(new Object(), 0);
        if(condition){
            wrapperClass.a = 1;
        }
        return wrapperClass;
    }

    @Test
    public void run15() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testWrapper"), null, true, true, getInitialOptions());
    }

    void test16(MyValue1[] array) {
        for (int i = 0; i < array.length; ++i) {
            array[i] = new MyValue1();
        }
//        for (int i = 0; i < 1000; ++i) {
//
//        }
    }

    @Test
    public void run16() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test16"), null, true, true, getInitialOptions());
    }

    void test17(Object[] array) {
        Object o = new Object();
        for (int i = 0; i < array.length; ++i) {
            array[i] = o;
        }
    }

    @Test
    public void run17() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test17"), null, true, true, getInitialOptions());
    }

    MyValue1 test18(boolean condition) {
        MyValue1 res;
        if(condition){
            res = new MyValue1();
        }else{
            res = null;
        }
        return res;
    }

    @Test
    public void run18() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test18"), null, true, true, getInitialOptions());
    }

    static value class X{
        int i = 0;
        Y y = new Y();
        long s = 0;
        X3 x3 = new X2();
    }
    static value class Y{
        Z z = new Z();
    }

    static value class Z{
        X1 x2 = new X2();
        X x = new X();
        Object o = new Object();
    }

    interface X1{}
    static class X2 extends X3 implements X1{}
    static abstract class X3{}

    @Test
    public void run19() throws InvalidInstalledCodeException {
        InlineTypeUtil.isCircularInlineType(getMetaAccess().lookupJavaType(X.class));
    }

    @Test
    public void run20() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod(AbstractCollection.class, "addAll"), null, true, true, getInitialOptions());
    }

    public static int testEA9(boolean condition, H f) {
        H localH;
        if(condition) {
            GraalDirectives.blackhole(3);
            localH = new H();
            globalH = localH;
        }else{
            localH = new H();
        }
        I i = new I(localH);
        int b = i.h.f.a;
        return b;
    }

    static class I{
        H h;
        I(H h){
            this.h = h;
        }
    }

    @Test
    public void run21() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA9"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    public static int testEA10(boolean condition, J j) {
        I2 i = new I2(j);
        if(condition) {
            i.j = new J(3);
        }
        return i.j.l;
    }

    static class I2{
        J j;
        I2(J j){
            this.j = j;
        }
    }

    static value class J{
        int l;
        J(int l){
            this.l = l;
        }
    }

    @Test
    public void run22() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("testEA10"), null, true, true, getInitialOptions());
        code.executeVarargs(true, new F());
        code.executeVarargs(false, new F());
    }

    static X[] test19_orig = null;
    public X[] test19() {
        X[] va = new X[8];
        for (int i = 1; i < va.length; ++i) {
            va[i] = new X();
        }
        test19_orig = va;

        return va.clone();
    }

    @Test
    public void run23() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("test19"), null, true, true, getInitialOptions());
    }

    @Test
    public void run24() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod(java.lang.classfile.instruction.ExceptionCatch.class, "of", java.lang.classfile.Label.class,java.lang.classfile.Label.class,java.lang.classfile.Label.class, java.util.Optional.class), null, true, true, getInitialOptions());
    }

    static final F equalsF1 = new F();
    static final F equalsF2 = new F();

    public static boolean equalsTest(){
        return equalsF1 == equalsF2;
    }

    @Test
    public void run25() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("equalsTest"), null, true, true, getInitialOptions());
    }

    static class U{
        int x = 3;
    }

    static final U u = new U();

    static int constantFolding(){
        return u.x;
    }

    @Test
    public void run26() throws InvalidInstalledCodeException {
        InstalledCode code = getCode(getResolvedJavaMethod("constantFolding"), null, true, true, getInitialOptions());
    }

}

package com.xposed.hook.fixture;

/**
 * 供 HookUtilsTest 反射定位的测试夹具类，不直接参与任何断言。
 */
public class SampleClasses {

    public static class Base {
        public String greet(String name) {
            return "hello " + name;
        }

        public void sideEffect() {
        }
    }

    public static class Child extends Base {
        public int compute(int a, int b) {
            return a + b;
        }

        public String greet(String name) {
            return "hi " + name;
        }
    }

    public static class Overloads {
        public void act() {
        }

        public void act(int x) {
        }

        public void act(String x) {
        }

        public void act(int x, String y) {
        }
    }

    public static class NoArg {
        public static String staticNoArg() {
            return "static";
        }

        public String instanceNoArg() {
            return "instance";
        }
    }

    public static class WithFields {
        public String name;

        public WithFields(String name) {
            this.name = name;
        }
    }
}

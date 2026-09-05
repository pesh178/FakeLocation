package com.xposed.hook.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.xposed.hook.fixture.SampleClasses;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/**
 * 针对 libxposed API 102 兼容层的单元测试。
 * 用动态代理模拟框架，验证 HookUtils 正确地把"定位方法/解析类名/构造参数"
 * 转换为 XposedInterface.hook(Executable).intercept(Hooker) 调用。
 */
public class HookUtilsTest {

    private final ClassLoader loader = HookUtilsTest.class.getClassLoader();

    // ---------- 框架 mock ----------

    private static class Recorder {
        final AtomicReference<Executable> hooked = new AtomicReference<>();
        final AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<>();
    }

    private final AtomicReference<int[]> hookCounter = new AtomicReference<>(null);

    private XposedInterface recordingApi(final Recorder rec) {
        Object handle = Proxy.newProxyInstance(loader,
                new Class<?>[]{XposedInterface.HookHandle.class}, (p, m, a) -> null);
        Object builder = Proxy.newProxyInstance(loader,
                new Class<?>[]{XposedInterface.HookBuilder.class}, (p, m, a) -> {
                    if (m.getName().equals("intercept")) {
                        rec.hooker.set((XposedInterface.Hooker) a[0]);
                        return handle;
                    }
                    return p; // setPriority/setExceptionMode 链式返回自身
                });
        InvocationHandler apiHandler = (p, m, a) -> {
            if (m.getName().equals("hook")) {
                rec.hooked.set((Executable) a[0]);
                int[] c = hookCounter.get();
                if (c != null) c[0]++;
                return builder;
            }
            Class<?> r = m.getReturnType();
            if (r == int.class) return 102;
            if (r == long.class) return 1L;
            if (r == boolean.class) return true;
            return null;
        };
        return (XposedInterface) Proxy.newProxyInstance(loader,
                new Class<?>[]{XposedInterface.class}, apiHandler);
    }

    /** 模拟一次 Chain 调用：proceed() 返回 "orig"，getArgs() 返回 ["a"]。 */
    private XposedInterface.Chain fakeChain() {
        return (XposedInterface.Chain) Proxy.newProxyInstance(loader,
                new Class<?>[]{XposedInterface.Chain.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "proceed":
                            return "orig";
                        case "getArgs":
                            return Arrays.asList("a");
                        case "getArg":
                            return "a";
                        default:
                            return null;
                    }
                });
    }

    // ---------- findClass ----------

    @Test
    public void findClass_resolvesPrimitiveNames() throws Exception {
        assertEquals(int.class, HookUtils.findClass("int", loader));
        assertEquals(boolean.class, HookUtils.findClass("boolean", loader));
        assertEquals(long.class, HookUtils.findClass("long", loader));
        assertEquals(void.class, HookUtils.findClass("void", loader));
    }

    @Test
    public void findClass_resolvesNormalAndArrayTypeNames() throws Exception {
        assertEquals(SampleClasses.Child.class,
                HookUtils.findClass("com.xposed.hook.fixture.SampleClasses$Child", loader));
        assertEquals(String[].class, HookUtils.findClass("[Ljava.lang.String;", loader));
    }

    // ---------- findMethodExact ----------

    @Test
    public void findMethodExact_findsOwnAndInheritedAndOverride() throws Exception {
        Method compute = HookUtils.findMethodExact(SampleClasses.Child.class, "compute", int.class, int.class);
        assertSame(SampleClasses.Child.class, compute.getDeclaringClass());

        Method sideEffect = HookUtils.findMethodExact(SampleClasses.Child.class, "sideEffect");
        assertSame(SampleClasses.Base.class, sideEffect.getDeclaringClass());

        Method greet = HookUtils.findMethodExact(SampleClasses.Child.class, "greet", String.class);
        assertSame(SampleClasses.Child.class, greet.getDeclaringClass());
    }

    @Test
    public void findMethodExact_throwsWhenMissing() {
        try {
            HookUtils.findMethodExact(SampleClasses.Child.class, "nonexistent");
            fail("expected NoSuchMethodException");
        } catch (NoSuchMethodException expected) {
        }
    }

    // ---------- callMethod / callStaticMethod ----------

    @Test
    public void callStaticMethod_noArg() throws Throwable {
        assertEquals("static", HookUtils.callStaticMethod(SampleClasses.NoArg.class, "staticNoArg"));
    }

    @Test
    public void callMethod_instanceNoArg() throws Throwable {
        assertEquals("instance", HookUtils.callMethod(new SampleClasses.NoArg(), "instanceNoArg"));
    }

    @Test
    public void callMethod_dispatchesPrimitiveOverload() throws Throwable {
        Object result = HookUtils.callMethod(new SampleClasses.Child(), "compute", 2, 3);
        assertEquals(5, result);
    }

    @Test
    public void callMethod_dispatchesStringOverload() throws Throwable {
        SampleClasses.Overloads target = new SampleClasses.Overloads();
        assertNull(HookUtils.callMethod(target, "act", "x"));
        assertNull(HookUtils.callMethod(target, "act", 1));
        assertNull(HookUtils.callMethod(target, "act", 1, "y"));
    }

    @Test
    public void callMethod_throwsWhenMissing() {
        try {
            HookUtils.callMethod(new SampleClasses.NoArg(), "missing");
            fail("expected NoSuchMethodException");
        } catch (Throwable expected) {
            assertTrue(expected instanceof NoSuchMethodException);
        }
    }

    // ---------- 字段查找 ----------

    @Test
    public void findFirstFieldByExactType_findsField() throws Exception {
        java.lang.reflect.Field f = HookUtils.findFirstFieldByExactType(SampleClasses.WithFields.class, String.class);
        assertEquals("name", f.getName());
        f.setAccessible(true);
        assertEquals("viaField", f.get(new SampleClasses.WithFields("viaField")));
    }

    @Test
    public void getObjectField_readsValue() throws Throwable {
        assertEquals("hello", HookUtils.getObjectField(new SampleClasses.WithFields("hello"), "name"));
    }

    // ---------- argsOf ----------

    @Test
    public void argsOf_copiesChainArgs() {
        assertArrayEquals(new Object[]{"a"}, HookUtils.argsOf(fakeChain()));
    }

    // ---------- hook 注册链路 ----------

    @Test
    public void hookMethod_passesExecutableAndHookerToBuilder() throws Throwable {
        Recorder rec = new Recorder();
        XposedHolder.init(recordingApi(rec), "com.test.proc");

        Method m = HookUtils.findMethodExact(SampleClasses.Child.class, "greet", String.class);
        AtomicBoolean called = new AtomicBoolean();
        HookUtils.hookMethod(m, chain -> {
            called.set(true);
            return chain.proceed();
        });

        assertSame(m, rec.hooked.get());
        assertEquals("orig", rec.hooker.get().intercept(fakeChain()));
        assertTrue(called.get());
    }

    @Test
    public void hookConstructor_passesConstructor() throws Exception {
        Recorder rec = new Recorder();
        XposedHolder.init(recordingApi(rec), "com.test.proc");

        Constructor<?> ctor = SampleClasses.WithFields.class.getDeclaredConstructor(String.class);
        HookUtils.hookConstructor(ctor, chain -> chain.proceed());
        assertSame(ctor, rec.hooked.get());
    }

    @Test
    public void findAndHookMethod_resolvesStringParamTypesAndHooks() throws Exception {
        Recorder rec = new Recorder();
        XposedHolder.init(recordingApi(rec), "com.test.proc");

        // getDeclaredMethod 每次返回新实例，故用 equals 而非 assertSame
        XposedInterface.Hooker hooker = chain -> chain.proceed();
        HookUtils.findAndHookMethod(SampleClasses.Overloads.class, loader, "act", "int", hooker);
        assertEquals(SampleClasses.Overloads.class.getDeclaredMethod("act", int.class), rec.hooked.get());

        HookUtils.findAndHookMethod("com.xposed.hook.fixture.SampleClasses$Overloads", loader,
                "act", "int", String.class, hooker);
        assertEquals(SampleClasses.Overloads.class.getDeclaredMethod("act", int.class, String.class), rec.hooked.get());
    }

    @Test
    public void findAndHookMethod_swallowsNotFoundWithoutThrowing() {
        Recorder rec = new Recorder();
        XposedHolder.init(recordingApi(rec), "com.test.proc");
        XposedInterface.Hooker hooker = chain -> null;
        HookUtils.findAndHookMethod("com.example.NoSuchClass", loader, "nope", hooker);
        assertNull(rec.hooked.get());
    }

    @Test
    public void hookMethod_withoutFrameworkInitialized_isSafe() throws Exception {
        XposedHolder.init(null, null);
        Method m = HookUtils.findMethodExact(SampleClasses.Child.class, "compute", int.class, int.class);
        HookUtils.hookMethod(m, chain -> null); // 只记日志，不抛异常
    }

    @Test
    public void hookAllMethods_hooksEveryPublicOverload() throws Exception {
        Recorder rec = new Recorder();
        hookCounter.set(new int[]{0});
        XposedHolder.init(recordingApi(rec), "com.test.proc");
        HookUtils.hookAllMethods(SampleClasses.Overloads.class, "act", chain -> null);
        assertEquals("Overloads 有 4 个 public act 重载", 4, hookCounter.get()[0]);
        hookCounter.set(null);
    }
}

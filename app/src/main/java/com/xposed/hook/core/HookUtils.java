package com.xposed.hook.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * legacy XposedHelpers/XposedBridge 的替代工具层，
 * 全部基于 libxposed API 102（XposedInterface.hook + Chain 拦截器模型）。
 */
public final class HookUtils {

    private HookUtils() {
    }

    /** 解析类名，支持常见签名形式（如 "[Ljava.lang.String;" 与基本类型名）。 */
    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        switch (className) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            default: return Class.forName(className, false, classLoader);
        }
    }

    /** 参数类型既可以是 Class 也可以是类名字符串（legacy 风格）。 */
    private static Class<?>[] resolveParamTypes(Object[] paramTypes, ClassLoader classLoader) throws Throwable {
        Class<?>[] result = new Class<?>[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object p = paramTypes[i];
            result[i] = p instanceof Class ? (Class<?>) p : findClass((String) p, classLoader);
        }
        return result;
    }

    /** 在类及其父类中查找精确签名的方法。找不到抛 NoSuchMethodException。 */
    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + methodName);
    }

    /**
     * 等价于 legacy XposedHelpers.findAndHookMethod：
     * 最后一个元素为 Hooker，其余为参数类型（Class 或类名字符串）。
     */
    public static void findAndHookMethod(Class<?> clazz, ClassLoader classLoader, String methodName, Object... paramTypesAndHooker) {
        try {
            Object last = paramTypesAndHooker[paramTypesAndHooker.length - 1];
            if (!(last instanceof XposedInterface.Hooker))
                throw new IllegalArgumentException("last argument must be Hooker");
            Object[] types = new Object[paramTypesAndHooker.length - 1];
            System.arraycopy(paramTypesAndHooker, 0, types, 0, types.length);
            Method method = findMethodExact(clazz, methodName, resolveParamTypes(types, classLoader));
            hookMethod(method, (XposedInterface.Hooker) last);
        } catch (Throwable e) {
            XposedHolder.log("HookUtils", "findAndHookMethod failed: " + clazz.getName() + "." + methodName + " -> " + e);
        }
    }

    public static void findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... paramTypesAndHooker) {
        try {
            findAndHookMethod(findClass(className, classLoader), classLoader, methodName, paramTypesAndHooker);
        } catch (Throwable e) {
            XposedHolder.log("HookUtils", "findAndHookMethod failed: " + className + "." + methodName + " -> " + e);
        }
    }

    /** 等价于 legacy XposedBridge.hookMethod。 */
    public static void hookMethod(Method method, XposedInterface.Hooker hooker) {
        XposedInterface api = XposedHolder.get();
        if (api == null) {
            XposedHolder.log("HookUtils", "XposedInterface not initialized");
            return;
        }
        api.hook(method).intercept(hooker);
    }

    public static void hookConstructor(Constructor<?> constructor, XposedInterface.Hooker hooker) {
        XposedInterface api = XposedHolder.get();
        if (api == null) {
            XposedHolder.log("HookUtils", "XposedInterface not initialized");
            return;
        }
        api.hook(constructor).intercept(hooker);
    }

    /** 等价于 legacy XposedBridge.hookAllMethods：挂钩全部 public 非抽象同名方法。 */
    public static void hookAllMethods(String className, ClassLoader classLoader, String methodName, XposedInterface.Hooker hooker) {
        try {
            Class<?> clazz = findClass(className, classLoader);
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)
                        && !Modifier.isAbstract(method.getModifiers())
                        && Modifier.isPublic(method.getModifiers())) {
                    hookMethod(method, hooker);
                }
            }
        } catch (Throwable e) {
            XposedHolder.log("HookUtils", "hookAllMethods failed: " + className + "." + methodName + " -> " + e);
        }
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, XposedInterface.Hooker hooker) {
        hookAllMethods(clazz.getName(), clazz.getClassLoader(), methodName, hooker);
    }

    /** 等价于 legacy XposedHelpers.callStaticMethod（无参调用）。 */
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) throws Throwable {
        return invokeMethod(clazz, null, methodName, args);
    }

    /** 等价于 legacy XposedHelpers.callMethod。 */
    public static Object callMethod(Object obj, String methodName, Object... args) throws Throwable {
        return invokeMethod(obj.getClass(), obj, methodName, args);
    }

    private static Object invokeMethod(Class<?> clazz, Object receiver, String methodName, Object... args) throws Throwable {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (!matches(method.getParameterTypes(), args)) continue;
                if (!Modifier.isStatic(method.getModifiers()) && receiver == null) continue;
                if (Modifier.isStatic(method.getModifiers()) && receiver != null) continue;
                method.setAccessible(true);
                return method.invoke(receiver, args);
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + methodName);
    }

    private static boolean matches(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null) {
                if (paramTypes[i].isPrimitive()) return false;
            } else if (!wrap(paramTypes[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }

    /** 等价于 legacy XposedHelpers.findFirstFieldByExactType，返回已 setAccessible 的字段。 */
    public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == type) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(clazz.getName() + " field of type " + type.getName());
    }

    /** 读取实例字段。 */
    @SuppressWarnings("unchecked")
    public static <T> T getObjectField(Object obj, String fieldName) throws Throwable {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(obj);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + "." + fieldName);
    }

    /** 取 Chain 参数的可变副本。 */
    public static Object[] argsOf(XposedInterface.Chain chain) {
        List<Object> list = chain.getArgs();
        return list.toArray(new Object[0]);
    }
}

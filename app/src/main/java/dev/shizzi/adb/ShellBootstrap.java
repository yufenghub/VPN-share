package dev.shizzi.adb;

import dalvik.system.PathClassLoader;

/** Kept as Java so bootstrap loading does not initialize Kotlin/native classes in the wrong loader. */
public final class ShellBootstrap {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Arguments");
        PathClassLoader loader = new PathClassLoader(args[0], args[1], ClassLoader.getSystemClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> main = Class.forName("dev.shizzi.adb.ShellServerMain", true, loader);
        main.getMethod("main", String[].class).invoke(null, (Object) args);
    }
}

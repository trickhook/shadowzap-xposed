package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

/** API 100 shape: module callbacks and their parameters. */
public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        boolean isSystemServer();

        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();

        ApplicationInfo getApplicationInfo();

        ClassLoader getClassLoader();

        boolean isFirstPackage();
    }

    default void onPackageLoaded(PackageLoadedParam param) {
    }
}

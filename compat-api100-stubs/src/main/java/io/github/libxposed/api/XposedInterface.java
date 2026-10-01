package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

/** API 100 shape: only the members the API 100 entry uses. */
public interface XposedInterface {
    String getFrameworkName();

    String getFrameworkVersion();

    long getFrameworkVersionCode();

    /** The module's own application info. */
    ApplicationInfo getApplicationInfo();

    void log(String message);

    void log(String message, Throwable throwable);
}

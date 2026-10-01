package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

/** API 100 shape: delegates to the framework's implementation passed to the constructor. */
public class XposedInterfaceWrapper implements XposedInterface {
    public XposedInterfaceWrapper(XposedInterface base) {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    @Override
    public final String getFrameworkName() {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    @Override
    public final String getFrameworkVersion() {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    @Override
    public final long getFrameworkVersionCode() {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    @Override
    public final ApplicationInfo getApplicationInfo() {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    @Override
    public final void log(String message) {
        throw new UnsupportedOperationException("Compile-time stub");
    }

    @Override
    public final void log(String message, Throwable throwable) {
        throw new UnsupportedOperationException("Compile-time stub");
    }
}

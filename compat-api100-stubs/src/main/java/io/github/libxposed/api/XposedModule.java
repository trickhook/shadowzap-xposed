package io.github.libxposed.api;

/** API 100 shape: entry classes are constructed with the framework interface and the module-loaded parameters. */
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {
    public XposedModule(XposedInterface base, ModuleLoadedParam param) {
        super(base);
    }
}

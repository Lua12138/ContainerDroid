package top.niunaijun.blackbox.fake.hook;

import android.text.TextUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.binder.BlackBoxProxyCatalog;
import top.niunaijun.blackbox.utils.MethodParameterUtils;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public abstract class ClassInvocationStub implements InvocationHandler, IInjectHook {
    public static final String TAG = ClassInvocationStub.class.getSimpleName();

    private final Map<String, MethodHook> mMethodHookMap = new HashMap<>();
    private Object mBase;
    private Object mProxyInvocation;
    private boolean onlyProxy;

    protected abstract Object getWho();

    protected abstract void inject(Object baseInvocation, Object proxyInvocation);

    protected void onBindMethod() {

    }

    protected Object getProxyInvocation() {
        return mProxyInvocation;
    }

    protected Object getBase() {
        return mBase;
    }

    protected String getProxyServiceName() {
        return null;
    }

    protected String getProxyInterfaceDescriptor() {
        return null;
    }

    protected void onlyProxy(boolean o) {
        onlyProxy = o;
    }

    @Override
    public void injectHook() {
        mBase = getWho();
        mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(), MethodParameterUtils.getAllInterface(mBase.getClass()), this);
        if (!onlyProxy) {
            inject(mBase, mProxyInvocation);
        }

        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) {
            initAnnotation(declaredClass);
        }
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) {
                    initAnnotation(declaredClass);
                }
            }
        }
    }

    protected void initAnnotation(Class<?> clazz) {
        ProxyMethod proxyMethod = clazz.getAnnotation(ProxyMethod.class);
        if (proxyMethod != null) {
            final String name = proxyMethod.value();
            if (!TextUtils.isEmpty(name)) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        ProxyMethods proxyMethods = clazz.getAnnotation(ProxyMethods.class);
        if (proxyMethods != null) {
            String[] value = proxyMethods.value();
            for (String name : value) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    protected void addMethodHook(MethodHook methodHook) {
        mMethodHookMap.put(methodHook.getMethodName(), methodHook);
    }

    protected void addMethodHook(String name, MethodHook methodHook) {
        mMethodHookMap.put(name, methodHook);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodHook methodHook = mMethodHookMap.get(method.getName());
        Object result = null;
        String proxyResult = "forwarded";
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                result = method.invoke(mBase, args);
                return result;
            } catch (Throwable e) {
                proxyResult = "exception";
                throw e.getCause();
            } finally {
                recordProxyEvent(method, args, result, proxyResult);
            }
        }

        try {
            result = methodHook.beforeHook(mBase, method, args);
            if (result != null) {
                proxyResult = "blocked";
                return result;
            }
            result = methodHook.hook(mBase, method, args);
            result = methodHook.afterHook(result);
            proxyResult = "handled";
            return result;
        } catch (Throwable e) {
            proxyResult = "exception";
            throw e;
        } finally {
            recordProxyEvent(method, args, result, proxyResult);
        }
    }

    private void recordProxyEvent(Method method, Object[] args, Object result, String proxyResult) {
        BlackBoxBinderMonitor.recordProxyCall(
                resolveProxyServiceName(),
                resolveProxyInterfaceDescriptor(),
                method == null ? null : method.getName(),
                getClass().getSimpleName(),
                args == null ? "0 args" : args.length + " args",
                result == null ? "null" : result.getClass().getName(),
                proxyResult);
    }

    private String resolveProxyServiceName() {
        String serviceName = getProxyServiceName();
        return serviceName == null ? BlackBoxProxyCatalog.getServiceName(getClass().getSimpleName()) : serviceName;
    }

    private String resolveProxyInterfaceDescriptor() {
        String descriptor = getProxyInterfaceDescriptor();
        return descriptor == null ? BlackBoxProxyCatalog.getInterfaceDescriptor(getClass().getSimpleName()) : descriptor;
    }
}

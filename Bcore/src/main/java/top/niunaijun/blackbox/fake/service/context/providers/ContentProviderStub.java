package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

/**
 * Created by Milk on 4/8/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mAppPkg;

    public IInterface wrapper(final IInterface contentProviderProxy, final String appPkg) {
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        boolean argsRewritten = false;
        if (args != null && args.length > 0) {
            Object arg = args[0];
            if (arg instanceof String) {
                args[0] = mAppPkg;
                argsRewritten = true;
            } else if (arg.getClass().getName().equals(BRAttributionSource.getRealClass().getName())) {
                ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                argsRewritten = true;
            }
        }
        Object result = null;
        String proxyResult = "forwarded";
        try {
            result = method.invoke(mBase, args);
            return result;
        } catch (Throwable e) {
            proxyResult = "exception";
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        } finally {
            BlackBoxBinderMonitor.recordProxyCall(
                    "content_provider",
                    "android.content.IContentProvider",
                    method == null ? null : method.getName(),
                    getClass().getSimpleName(),
                    args == null ? "0 args" : args.length + " args",
                    result == null ? "null" : result.getClass().getName(),
                    proxyResult,
                    "forwarded".equals(proxyResult),
                    argsRewritten,
                    false);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}

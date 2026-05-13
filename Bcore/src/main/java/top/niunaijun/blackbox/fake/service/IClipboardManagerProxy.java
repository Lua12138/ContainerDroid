package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;

import black.android.content.BRClipboardManager;
import black.android.content.BRIClipboardStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

public class IClipboardManagerProxy extends BinderInvocationStub {

    public IClipboardManagerProxy() {
        super(getClipboardBinder());
    }

    @Override
    protected Object getWho() {
        return BRIClipboardStub.get().asInterface(getClipboardBinder());
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRClipboardManager.get()._set_sService((IInterface) proxyInvocation);
        replaceSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return BRClipboardManager.get().getService() != getProxyInvocation();
    }

    private static IBinder getClipboardBinder() {
        return BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE);
    }
}

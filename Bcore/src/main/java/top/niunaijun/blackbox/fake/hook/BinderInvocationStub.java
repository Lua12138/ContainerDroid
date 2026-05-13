package top.niunaijun.blackbox.fake.hook;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.util.Map;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.binder.BlackBoxProxyCatalog;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public abstract class BinderInvocationStub extends ClassInvocationStub implements IBinder {
    private IBinder mBaseBinder;
    private String mServiceName;
    private String mInterfaceDescriptor;

    public BinderInvocationStub(IBinder baseBinder) {
        mBaseBinder = baseBinder;
    }

    @Override
    protected void onBindMethod() {
    }

    @Nullable
    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        if (mInterfaceDescriptor != null) {
            return mInterfaceDescriptor;
        }
        mInterfaceDescriptor = mBaseBinder.getInterfaceDescriptor();
        return mInterfaceDescriptor;
    }

    @Override
    public boolean pingBinder() {
        return mBaseBinder.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return mBaseBinder.isBinderAlive();
    }

    @Nullable
    @Override
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return (IInterface) getProxyInvocation();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dump(fd, args);
    }

    @Override
    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dumpAsync(fd, args);
    }

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        boolean result = false;
        String proxyResult = "forwarded";
        try {
            result = mBaseBinder.transact(code, data, reply, flags);
            return result;
        } catch (RemoteException e) {
            proxyResult = "exception";
            throw e;
        } finally {
            BlackBoxBinderMonitor.recordProxyCall(
                    mServiceName,
                    mInterfaceDescriptor,
                    "transact#" + code,
                    getClass().getSimpleName(),
                    "flags=" + flags + ", data_size=" + safeDataSize(data),
                    "return=" + result,
                    proxyResult);
        }
    }

    @Override
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) throws RemoteException {
        mBaseBinder.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return mBaseBinder.unlinkToDeath(recipient, flags);
    }


    protected void replaceSystemService(String name) {
        mServiceName = name;
        mInterfaceDescriptor = BlackBoxProxyCatalog.getInterfaceDescriptorForService(name);
        Map<String, IBinder> services = BRServiceManager.get().sCache();
        services.put(name, this);
    }

    @Override
    protected String getProxyServiceName() {
        return mServiceName;
    }

    @Override
    protected String getProxyInterfaceDescriptor() {
        return mInterfaceDescriptor;
    }

    private static int safeDataSize(Parcel data) {
        try {
            return data == null ? -1 : data.dataSize();
        } catch (Throwable ignored) {
            return -1;
        }
    }
}

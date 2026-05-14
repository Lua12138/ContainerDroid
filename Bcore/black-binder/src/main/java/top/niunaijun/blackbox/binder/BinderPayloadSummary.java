package top.niunaijun.blackbox.binder;

import android.os.Parcel;

public final class BinderPayloadSummary {
    private static final String IPACKAGE_MANAGER = "android.content.pm.IPackageManager";
    private static final String ISERVICE_MANAGER = "android.os.IServiceManager";
    private static final int SDK_TIRAMISU = 33;

    private BinderPayloadSummary() {
    }

    static String summarize(String descriptor, String method, Parcel data, int sdkInt) {
        if (data == null) {
            return null;
        }
        return summarize(descriptor, method, new ParcelReader(data), sdkInt);
    }

    static String summarize(String descriptor, String method, Reader reader, int sdkInt) {
        PackageManagerCall call = parsePackageManagerCall(descriptor, method, reader, sdkInt);
        if (call != null) {
            return format(call.getPackageName(), call.getFlags(), call.getUserId());
        }
        String serviceName = parseServiceManagerName(descriptor, method, reader);
        return serviceName == null ? null : "service=" + serviceName;
    }

    public static PackageManagerCall parsePackageManagerCall(String descriptor, String method,
                                                             Parcel data, int sdkInt) {
        if (data == null) {
            return null;
        }
        return parsePackageManagerCall(descriptor, method, new ParcelReader(data), sdkInt);
    }

    static PackageManagerCall parsePackageManagerCall(String descriptor, String method,
                                                      Reader reader, int sdkInt) {
        if (!IPACKAGE_MANAGER.equals(descriptor) || !isPackageStringQuery(method) || reader == null) {
            return null;
        }
        int originalPosition = reader.dataPosition();
        try {
            if (!skipInterfaceToken(reader, descriptor)) {
                return null;
            }
            String packageName = reader.readString();
            if (packageName == null) {
                return null;
            }
            if (sdkInt >= SDK_TIRAMISU) {
                long flags = reader.readLong();
                int userId = reader.readInt();
                return new PackageManagerCall(method, packageName, flags, userId);
            }
            int flags = reader.readInt();
            int userId = reader.readInt();
            return new PackageManagerCall(method, packageName, flags, userId);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                reader.setDataPosition(originalPosition);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isPackageStringQuery(String method) {
        return "getPackageInfo".equals(method)
                || "getApplicationInfo".equals(method)
                || "getPackageUid".equals(method);
    }

    private static String parseServiceManagerName(String descriptor, String method, Reader reader) {
        if (!ISERVICE_MANAGER.equals(descriptor) || reader == null || !isServiceManagerLookup(method)) {
            return null;
        }
        int originalPosition = reader.dataPosition();
        try {
            if (!skipInterfaceToken(reader, descriptor)) {
                return null;
            }
            return reader.readString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                reader.setDataPosition(originalPosition);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isServiceManagerLookup(String method) {
        return "getService".equals(method)
                || "checkService".equals(method)
                || "waitForService".equals(method)
                || "rawGetService".equals(method)
                || "unknown".equals(method);
    }

    private static boolean skipInterfaceToken(Reader reader, String descriptor) {
        if (trySkipLegacyInterfaceToken(reader, descriptor)) {
            return true;
        }
        return trySkipModernInterfaceToken(reader, descriptor);
    }

    private static boolean trySkipLegacyInterfaceToken(Reader reader, String descriptor) {
        try {
            reader.setDataPosition(0);
            reader.readInt();
            return descriptor.equals(reader.readString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean trySkipModernInterfaceToken(Reader reader, String descriptor) {
        try {
            reader.setDataPosition(0);
            reader.readInt();
            reader.readInt();
            reader.readInt();
            return descriptor.equals(reader.readString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String format(String packageName, long flags, int userId) {
        return "package=" + packageName + ", flags=" + flags + ", userId=" + userId;
    }

    public static final class PackageManagerCall {
        private final String method;
        private final String packageName;
        private final long flags;
        private final int userId;

        private PackageManagerCall(String method, String packageName, long flags, int userId) {
            this.method = method;
            this.packageName = packageName;
            this.flags = flags;
            this.userId = userId;
        }

        public String getMethod() {
            return method;
        }

        public String getPackageName() {
            return packageName;
        }

        public long getFlags() {
            return flags;
        }

        public int getUserId() {
            return userId;
        }
    }

    interface Reader {
        String readString();

        int readInt();

        long readLong();

        int dataPosition();

        void setDataPosition(int position);
    }

    private static final class ParcelReader implements Reader {
        private final Parcel parcel;

        ParcelReader(Parcel parcel) {
            this.parcel = parcel;
        }

        @Override
        public String readString() {
            return parcel.readString();
        }

        @Override
        public int readInt() {
            return parcel.readInt();
        }

        @Override
        public long readLong() {
            return parcel.readLong();
        }

        @Override
        public int dataPosition() {
            return parcel.dataPosition();
        }

        @Override
        public void setDataPosition(int position) {
            parcel.setDataPosition(position);
        }
    }
}

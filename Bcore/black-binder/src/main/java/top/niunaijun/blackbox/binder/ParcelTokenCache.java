package top.niunaijun.blackbox.binder;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ParcelTokenCache {
    private final Map<Object, ParcelTokenInfo> tokens =
            Collections.synchronizedMap(new WeakHashMap<Object, ParcelTokenInfo>());

    public void put(Object parcel, String descriptor, int tid, long timestampNs) {
        if (parcel == null || descriptor == null) {
            return;
        }
        tokens.put(parcel, new ParcelTokenInfo(descriptor, tid, timestampNs));
    }

    public ParcelTokenInfo get(Object parcel) {
        if (parcel == null) {
            return null;
        }
        return tokens.get(parcel);
    }

    public void remove(Object parcel) {
        if (parcel != null) {
            tokens.remove(parcel);
        }
    }

    public void clear() {
        tokens.clear();
    }
}

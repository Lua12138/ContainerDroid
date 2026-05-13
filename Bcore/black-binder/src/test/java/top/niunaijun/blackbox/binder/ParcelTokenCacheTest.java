package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ParcelTokenCacheTest {

    @Test
    public void tracksAndClearsDescriptorByParcelIdentity() {
        ParcelTokenCache cache = new ParcelTokenCache();
        Object parcel = new Object();

        cache.put(parcel, "android.content.pm.IPackageManager", 7, 100L);

        ParcelTokenInfo info = cache.get(parcel);
        assertEquals("android.content.pm.IPackageManager", info.getDescriptor());
        assertEquals(7, info.getTid());
        assertEquals(100L, info.getTimestampNs());

        cache.remove(parcel);

        assertNull(cache.get(parcel));
    }
}

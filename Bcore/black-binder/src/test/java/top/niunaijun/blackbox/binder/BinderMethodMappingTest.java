package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BinderMethodMappingTest {

    @SuppressWarnings("unused")
    public static class ActivityManagerStub {
        private static final String DESCRIPTOR = "android.app.IActivityManager";
        public static final int TRANSACTION_startActivity = 3;
        public static final int TRANSACTION_broadcastIntent = 14;
    }

    @Test
    public void registerStubMapsDescriptorAndTransactionFields() {
        BinderMethodMapping mapping = new BinderMethodMapping();

        mapping.registerStub(ActivityManagerStub.class);

        assertEquals("startActivity", mapping.resolve("android.app.IActivityManager", 3));
        assertEquals("broadcastIntent", mapping.resolve("android.app.IActivityManager", 14));
    }

    @Test
    public void unknownMappingReturnsUnknown() {
        BinderMethodMapping mapping = new BinderMethodMapping();

        assertEquals("unknown", mapping.resolve("android.app.IActivityManager", 99));
        assertEquals("unknown", mapping.resolve(null, 3));
    }

    @Test
    public void registerJsonMapsDescriptorCodeToMethod() {
        BinderMethodMapping mapping = new BinderMethodMapping();

        mapping.registerJson("{"
                + "\"android.app.IActivityManager\":{\"3\":\"startActivity\",\"18\":\"startService\"},"
                + "\"android.content.pm.IPackageManager\":{\"1\":\"checkPermission\"}"
                + "}");

        assertEquals("startActivity", mapping.resolve("android.app.IActivityManager", 3));
        assertEquals("startService", mapping.resolve("android.app.IActivityManager", 18));
        assertEquals("checkPermission", mapping.resolve("android.content.pm.IPackageManager", 1));
    }
}

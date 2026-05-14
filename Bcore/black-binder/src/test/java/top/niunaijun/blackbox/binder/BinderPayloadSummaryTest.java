package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BinderPayloadSummaryTest {

    @Test
    public void packageManagerSummarySkipsModernHeaderAndPreservesReaderPosition() {
        FakeReader reader = new FakeReader(
                0,
                -1,
                0x53595354,
                "android.content.pm.IPackageManager",
                "com.bestv.tv.video.iqy.tjdx",
                134217728L,
                0);
        reader.setDataPosition(4);

        String summary = BinderPayloadSummary.summarize(
                "android.content.pm.IPackageManager",
                "getPackageInfo",
                reader,
                33);

        assertEquals("package=com.bestv.tv.video.iqy.tjdx, flags=134217728, userId=0", summary);
        assertEquals(4, reader.dataPosition());

        BinderPayloadSummary.PackageManagerCall call = BinderPayloadSummary.parsePackageManagerCall(
                "android.content.pm.IPackageManager",
                "getPackageInfo",
                reader,
                33);

        assertEquals("getPackageInfo", call.getMethod());
        assertEquals("com.bestv.tv.video.iqy.tjdx", call.getPackageName());
        assertEquals(134217728L, call.getFlags());
        assertEquals(0, call.getUserId());
        assertEquals(4, reader.dataPosition());
    }

    @Test
    public void packageManagerSummaryFallsBackToLegacyHeaderAndIntFlags() {
        FakeReader reader = new FakeReader(
                0,
                "android.content.pm.IPackageManager",
                "com.bestv.tv.video.iqy.tjdx",
                64,
                10);
        reader.setDataPosition(3);

        String summary = BinderPayloadSummary.summarize(
                "android.content.pm.IPackageManager",
                "getApplicationInfo",
                reader,
                29);

        assertEquals("package=com.bestv.tv.video.iqy.tjdx, flags=64, userId=10", summary);
        assertEquals(3, reader.dataPosition());
    }

    @Test
    public void packageManagerSummaryReturnsNullWhenTokenDoesNotMatch() {
        FakeReader reader = new FakeReader(
                0,
                "android.app.IActivityManager",
                "com.bestv.tv.video.iqy.tjdx",
                0,
                0);

        String summary = BinderPayloadSummary.summarize(
                "android.content.pm.IPackageManager",
                "getPackageInfo",
                reader,
                29);

        assertNull(summary);
        assertEquals(0, reader.dataPosition());
    }

    @Test
    public void serviceManagerSummaryIncludesRequestedServiceAndPreservesReaderPosition() {
        FakeReader reader = new FakeReader(
                0,
                "android.os.IServiceManager",
                "package");
        reader.setDataPosition(2);

        String summary = BinderPayloadSummary.summarize(
                "android.os.IServiceManager",
                "checkService",
                reader,
                30);

        assertEquals("service=package", summary);
        assertEquals(2, reader.dataPosition());
    }

    @Test
    public void summaryIgnoresUnwatchedDescriptor() {
        FakeReader reader = new FakeReader(
                0,
                "android.app.IActivityManager",
                "com.bestv.tv.video.iqy.tjdx",
                0,
                0);

        String summary = BinderPayloadSummary.summarize(
                "android.app.IActivityManager",
                "startActivity",
                reader,
                29);

        assertNull(summary);
    }

    private static final class FakeReader implements BinderPayloadSummary.Reader {
        private final Object[] values;
        private int position;

        FakeReader(Object... values) {
            this.values = values;
        }

        @Override
        public String readString() {
            Object value = values[position++];
            return value == null ? null : (String) value;
        }

        @Override
        public int readInt() {
            Object value = values[position++];
            return ((Number) value).intValue();
        }

        @Override
        public long readLong() {
            Object value = values[position++];
            return ((Number) value).longValue();
        }

        @Override
        public int dataPosition() {
            return position;
        }

        @Override
        public void setDataPosition(int position) {
            this.position = position;
        }
    }
}

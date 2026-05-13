package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class BinderEventTest {

    @Test
    public void binderEventSerializesPlanFieldsAsJson() {
        VirtualIdentity identity = new VirtualIdentity(
                "blackbox:p0",
                "com.example.app",
                "com.example.app",
                1001,
                0,
                12);

        BinderEvent event = BinderEvent.transact(
                123456789L,
                111,
                222,
                identity,
                "android.app.IActivityManager",
                "startActivity",
                3,
                16,
                512,
                true,
                "Pine.BinderProxy.transact",
                null);

        String json = event.toJson();

        assertTrue(json.contains("\"type\":\"binder_transact\""));
        assertTrue(json.contains("\"ts_ns\":123456789"));
        assertTrue(json.contains("\"host_pid\":111"));
        assertTrue(json.contains("\"host_tid\":222"));
        assertTrue(json.contains("\"host_process\":\"blackbox:p0\""));
        assertTrue(json.contains("\"virtual_package\":\"com.example.app\""));
        assertTrue(json.contains("\"virtual_process\":\"com.example.app\""));
        assertTrue(json.contains("\"virtual_uid\":1001"));
        assertTrue(json.contains("\"virtual_pid\":12"));
        assertTrue(json.contains("\"user_id\":0"));
        assertTrue(json.contains("\"descriptor\":\"android.app.IActivityManager\""));
        assertTrue(json.contains("\"method\":\"startActivity\""));
        assertTrue(json.contains("\"code\":3"));
        assertTrue(json.contains("\"flags\":16"));
        assertTrue(json.contains("\"oneway\":false"));
        assertTrue(json.contains("\"data_size\":512"));
        assertTrue(json.contains("\"reply_expected\":true"));
        assertTrue(json.contains("\"source\":\"Pine.BinderProxy.transact\""));
    }
}

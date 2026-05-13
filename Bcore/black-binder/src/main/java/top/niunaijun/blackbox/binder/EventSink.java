package top.niunaijun.blackbox.binder;

interface EventSink {
    void offer(JsonSerializable event);

    void close();
}

package top.niunaijun.blackbox.binder;

final class NoopEventSink implements EventSink {
    static final NoopEventSink INSTANCE = new NoopEventSink();

    private NoopEventSink() {
    }

    @Override
    public void offer(JsonSerializable event) {
    }

    @Override
    public void close() {
    }
}

package top.niunaijun.blackbox.binder;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class AsyncJsonlEventSink implements EventSink, Runnable {
    private static final String TAG = "BlackBoxBinderMonitor";

    private final ArrayBlockingQueue<JsonSerializable> queue;
    private final File outputFile;
    private final boolean logcat;
    private final Thread worker;
    private volatile boolean running = true;

    AsyncJsonlEventSink(File outputFile, boolean logcat, int queueCapacity) {
        this.outputFile = outputFile;
        this.logcat = logcat;
        this.queue = new ArrayBlockingQueue<>(Math.max(128, queueCapacity));
        this.worker = new Thread(this, "bb-binder-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void offer(JsonSerializable event) {
        if (event == null || !running) {
            return;
        }
        queue.offer(event);
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }

    @Override
    public void run() {
        BufferedWriter writer = null;
        try {
            writer = createWriter();
            while (running || !queue.isEmpty()) {
                JsonSerializable event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                String json = event.toJson();
                if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                    Log.d(TAG, json);
                }
                if (writer != null) {
                    writer.write(json);
                    writer.newLine();
                    writer.flush();
                }
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                Log.e(TAG, "event writer failed", e);
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private BufferedWriter createWriter() throws IOException {
        if (outputFile == null) {
            return null;
        }
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                Log.w(TAG, "failed to create binder monitor directory: " + parent);
            }
        }
        return new BufferedWriter(new FileWriter(outputFile, true));
    }

    private boolean shouldLogcat() {
        return BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && logcat;
    }
}

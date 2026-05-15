package top.niunaijun.blackbox.fake.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

public class RuntimeExecProxy implements IInjectHook {
    private static final String TAG = "RuntimeExecProxy";
    private static final String RUNTIME_EXEC_SERVICE = "runtime_exec";
    private static final String STAGE_SANITIZED_LOG = "stage=sanitized";
    private static final String STAGE_SANITIZED_GETPROP_LOG = "stage=sanitized_getprop";
    private static final String STAGE_SANITIZED_ID_LOG = "stage=sanitized_id";
    private static final String STAGE_SANITIZED_PROC_STATUS_LOG = "stage=sanitized_proc_status";
    private static final String DYNAMIC_PROC_MOUNTS_ENV = "BLACKBOX_DYNAMIC_PROC_MOUNTS";
    private static final String DYNAMIC_PROC_MOUNTS_JAVA_PROPERTY = "blackbox.dynamic_mounts";
    private static final String DYNAMIC_PROC_MOUNTS_PROPERTY = "debug.blackbox.dynamic_mounts";
    private static final String STATIC_PROCESS_TRACE_ENV = "BLACKBOX_STATIC_PROCESS_TRACE";
    private static final String STATIC_PROCESS_TRACE_JAVA_PROPERTY = "blackbox.static_process_trace";
    private static final String STATIC_PROCESS_TRACE_PROPERTY = "debug.blackbox.static_process_trace";
    private static final String EXEC_TRACE_ENV = "BLACKBOX_EXEC_TRACE";
    private static final String EXEC_TRACE_JAVA_PROPERTY = "blackbox.exec_trace";
    private static final String EXEC_TRACE_PROPERTY = "debug.blackbox.exec_trace";
    private static final String[] DEFAULT_GETPROP_KEYS = new String[] {
            "ro.product.stb.stbid",
            "ro.ril.oem.wifimac",
            "ro.boot.wifimacaddr",
            "Build.BRAND",
            "audio.chk.cal.spk",
            "audio.chk.cal.us",
            "build.version.extensions.r",
            "cache_key.display_info",
            "cache_key.has_system_feature",
            "cache_key.is_compat_change_enabled",
            "cache_key.is_interactive",
            "cache_key.is_power_save_mode",
            "cache_key.is_user_unlocked",
            "cache_key.location_enabled",
            "cache_key.package_info",
            "cache_key.telephony.get_active_data_sub_id",
            "cache_key.telephony.get_default_data_sub_id",
            "cache_key.telephony.get_default_sms_sub_id",
            "cache_key.telephony.get_default_sub_id",
            "cache_key.telephony.get_slot_index",
            "camera.disable_zsl_mode",
            "dalvik.vm.appimageformat",
            "dalvik.vm.bg-dex2oat-threads",
            "dalvik.vm.boot-dex2oat-threads",
            "dalvik.vm.dex2oat-Xms",
            "dalvik.vm.dex2oat-Xmx",
            "dalvik.vm.dex2oat-max-image-block-size",
            "dalvik.vm.dex2oat-minidebuginfo",
            "dalvik.vm.dex2oat-resolve-startup-strings",
            "dalvik.vm.dex2oat-threads",
            "dalvik.vm.dex2oat-updatable-bcp-packages-file",
            "dalvik.vm.dexopt.secondary",
            "dalvik.vm.heapgrowthlimit",
            "dalvik.vm.heapsize",
            "dalvik.vm.image-dex2oat-Xms",
            "dalvik.vm.image-dex2oat-Xmx",
            "dalvik.vm.isa.arm.features",
            "dalvik.vm.isa.arm.variant",
            "dalvik.vm.minidebuginfo",
            "dalvik.vm.usejit",
            "dalvik.vm.usejitprofiles",
            "debug.atrace.tags.enableflags",
            "debug.debuggerd.disable",
            "debug.force_rtl",
            "debug.mtk_tflite.target_nnapi",
            "debug.perf_cpu_time_max_percent",
            "debug.perf_event_max_sample_rate",
            "debug.perf_event_mlock_kb",
            "debug.power.monitor_tools",
            "debug.sf.disable_backpressure",
            "debug.tracing.screen_brightness",
            "dev.bootcomplete",
            "dev.mnt.blk.product",
            "dev.mnt.blk.root",
            "dev.mnt.blk.vendor",
            "drm.service.enabled",
            "events.cpu",
            "gsm.current.phone-type",
            "gsm.network.type",
            "gsm.operator.alpha",
            "gsm.operator.iso-country",
            "gsm.operator.isroaming",
            "gsm.operator.numeric",
            "gsm.operator.orig.alpha",
            "gsm.sim.num.simlock",
            "gsm.sim.state",
            "gsm.sim1.num.simlock",
            "gsm.sim1.type",
            "gsm.sim2.type",
            "gsm.slot1.num.pin1",
            "gsm.slot1.num.pin2",
            "gsm.slot1.num.puk1",
            "gsm.slot1.num.puk2",
            "gsm.slot2.num.pin1",
            "gsm.slot2.num.pin2",
            "gsm.slot2.num.puk1",
            "gsm.slot2.num.puk2",
            "gsm.version.baseband",
            "gsm.version.ril-impl",
            "hwservicemanager.ready",
            "init.svc.adbd",
            "init.svc.aee-reinit",
            "init.svc.aee_aed",
            "init.svc.agpsd",
            "init.svc.apexd",
            "init.svc.apexd-bootstrap",
            "init.svc.apexd-snapshotde",
            "init.svc.audioserver",
            "init.svc.batterywarning",
            "init.svc.beanpod_check_keybox_service",
            "init.svc.bluetooth-1-0",
            "init.svc.bootanim",
            "init.svc.bootlogoupdater",
            "init.svc.boringssl_self_test32",
            "init.svc.boringssl_self_test32_vendor",
            "init.svc.boringssl_self_test_apex32",
            "init.svc.bpfloader",
            "init.svc.camerahalserver",
            "init.svc.cameraserver",
            "init.svc.ccci3_mdinit",
            "init.svc.ccci_mdinit",
            "init.svc.charge_logger",
            "init.svc.connsyslogger",
            "init.svc.credstore",
            "init.svc.derive_sdk",
            "init.svc.drm",
            "init.svc.fdpp",
            "init.svc.fuelgauged",
            "init.svc.fuelgauged_nvram",
            "init.svc.gatekeeperd",
            "init.svc.getgameserver",
            "init.svc.gnss_service",
            "init.svc.gpu",
            "init.svc.gpu-1-0",
            "init.svc.health-hal-2-1",
            "init.svc.heapprofd",
            "init.svc.hidl_memory",
            "init.svc.hwservicemanager",
            "init.svc.idmap2d",
            "init.svc.incidentd",
            "init.svc.installd",
            "init.svc.iorapd",
            "init.svc.ipsec_mon",
            "init.svc.keystore",
            "init.svc.lbs_dbg",
            "init.svc.lbs_hidl_service",
            "init.svc.lmkd",
            "init.svc.logd",
            "init.svc.logd-auditctl",
            "init.svc.logd-reinit",
            "init.svc.mcd_init",
            "init.svc.mcd_service",
            "init.svc.mdnsd",
            "init.svc.media",
            "init.svc.media.swcodec",
            "init.svc.mediacodec",
            "init.svc.mediadrm",
            "init.svc.mediaextractor",
            "init.svc.mediametrics",
            "init.svc.mi_ric_init",
            "init.svc.mi_ric_run",
            "init.svc.mi_thermald",
            "init.svc.miui-early-boot",
            "init.svc.miuibooster",
            "init.svc.mnld",
            "init.svc.mobile_log_d",
            "init.svc.modemdbfilter_client",
            "init.svc.mqsasd",
            "init.svc.mtkcodecservice-1-1",
            "init.svc.netd",
            "init.svc.netdagent",
            "init.svc.netdiag",
            "init.svc.nvram-hidl-1-1",
            "init.svc.nvram_daemon",
            "init.svc.power-hal-1-0",
            "init.svc.pq-2-2",
            "init.svc.qadaemon",
            "init.svc.servicemanager",
            "init.svc.shelld",
            "init.svc.statsd",
            "init.svc.storaged",
            "init.svc.surfaceflinger",
            "init.svc.system_perf_init",
            "init.svc.system_suspend",
            "init.svc.teei_daemon",
            "init.svc.terservice",
            "init.svc.thermal",
            "init.svc.thermal_manager",
            "init.svc.thermald",
            "init.svc.thermalloadalgod",
            "init.svc.tombstoned",
            "init.svc.traced",
            "init.svc.traced_perf",
            "init.svc.traced_probes",
            "init.svc.ueventd",
            "init.svc.usbd",
            "init.svc.vendor_flash_recovery",
            "ro.build.id",
            "ro.build.fingerprint",
            "ro.build.description",
            "ro.build.display.id",
            "ro.build.tags",
            "ro.build.type",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.product.brand",
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.product.name",
            "ro.product.device",
            "ro.product.board",
            "ro.board.platform",
            "ro.hardware",
            "ro.boot.hardware",
            "ro.kernel.qemu",
            "ro.secure",
            "ro.debuggable",
            "ro.boot.verifiedbootstate",
            "ro.boot.flash.locked",
            "persist.sys.locale"
    };

    private static final String[] DEFAULT_PROC_MOUNTS_LINES = new String[] {
            "/dev/block/dm-3 / ext4 ro,seclabel,relatime,errors=panic,data=ordered,barrier=1,inode_readahead_blks=8,resuid=0,resgid=0,commit=5,verity,fsverity,metadata_csum,stable_inodes,first_stage_mount,logical,slotselect,avb,wait 0 0",
            "tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,size=1947712k,nr_inodes=486928,mode=755,uid=0,gid=0,strictatime,mpol=prefer:0,restorecon_recursive,early,devuid=0,devgid=0 0 0",
            "devpts /dev/pts devpts rw,seclabel,relatime,mode=600,ptmxmode=000,uid=0,gid=5,newinstance,hidepid=0,grantpt,create=0600,restorecon 0 0",
            "proc /proc proc rw,relatime,gid=3009,hidepid=2,subset=pid,nodev,noexec,nosuid,fscontext=u:object_r:proc:s0,mode=0555,early 0 0",
            "sysfs /sys sysfs rw,seclabel,relatime,nodev,noexec,nosuid,fscontext=u:object_r:sysfs:s0,mode=0555,strictatime,early 0 0",
            "tmpfs /mnt tmpfs rw,seclabel,nosuid,nodev,noexec,relatime,size=1947712k,nr_inodes=486928,mode=755,gid=1000,uid=0,strictatime,mpol=prefer:0,restorecon_recursive,shared 0 0",
            "/dev/block/dm-4 /vendor ext4 ro,seclabel,relatime,errors=panic,data=ordered,barrier=1,inode_readahead_blks=8,resuid=0,resgid=0,commit=5,verity,fsverity,metadata_csum,stable_inodes,first_stage_mount,logical,slotselect,avb,wait 0 0",
            "/dev/block/dm-5 /product ext4 ro,seclabel,relatime,errors=panic,data=ordered,barrier=1,inode_readahead_blks=8,resuid=0,resgid=0,commit=5,verity,fsverity,metadata_csum,stable_inodes,first_stage_mount,logical,slotselect,avb,wait 0 0",
            "/dev/block/platform/bootdevice/by-name/userdata /data f2fs rw,lazytime,seclabel,nosuid,nodev,noatime,background_gc=on,gc_merge,discard,no_heap,user_xattr,inline_xattr,acl,inline_data,inline_dentry,flush_merge,extent_cache,mode=adaptive,active_logs=6,reserve_root=26504,resuid=0,resgid=1065,inlinecrypt,alloc_mode=reuse,checkpoint_merge,fsync_mode=nobarrier,compress_algorithm=lz4,compress_chksum,compress_mode=fs,atgc,fsync_mode=nobarrier,memory=normal,norecovery,read_extent_cache,age_extent_cache,fscrypt=aes-256-xts:aes-256-cts:v2+inlinecrypt_optimized,wrappedkey_v0,quota,prjquota,usrquota,grpquota,latemount,wait,check,formattable,fileencryption=ice,metadata_encryption=aes-256-xts,keydirectory=/metadata/vold/metadata_encryption,checkpoint=fs,first_stage_mount,reservedsize=128M,sysfs_path=/sys/devices/platform/soc/1d84000.ufshc,inlinecrypt_optimized,key_per_boot_ref,discard_unit=block,io_bits=0,compress_extension=so,compress_extension=apk,compress_extension=vdex,compress_extension=odex,compress_extension=dex,compress_extension=art,compress_extension=prof,compress_extension=png,compress_extension=jpg,compress_extension=webp,compress_extension=db,compress_extension=dat,compress_extension=pak,checkpoint_disable_cap=0,fsync_fault_injection=0 0 0",
            "/data/media /mnt/runtime/default/emulated sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=1015,multiuser,mask=6,derive_gid,default_normal,reserved_mb=0,unshared_obb,derive_gid_from_media_rw,write_gid=1015,read_gid=9997,fsuid_cache=1023,userid=0,lower_fs=f2fs,casefold,projid,fscrypt,appfuse,pass_through,derive_gid_cache,isolated_storage,legacy_layout,default_permissions,visible=true,app_data_isolation,media_provider,obb_isolation,aid_media_rw=1023,aid_everybody=9997,aid_ext_data_rw=1078 0 0",
            "/dev/fuse /storage/emulated fuse rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0,group_id=0,allow_other,default_permissions,fsname=/dev/fuse,subtype=sdcardfs,writeback_cache,max_read=131072,blksize=4096,rootmode=40000,fd=54,fd_owner=media_rw,derive_gid,mask=6,multiuser,reserved_mb=0,lower_fs=f2fs,casefold,projid,fscrypt,appfuse,pass_through,derive_gid_cache,isolated_storage,legacy_layout,visible=true,app_data_isolation,media_provider,obb_isolation,aid_media_rw=1023,aid_everybody=9997,aid_ext_data_rw=1078 0 0",
            "/data/media /storage/emulated/0/Android/obb sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=1015,multiuser,mask=6,derive_gid,default_normal,unshared_obb,reserved_mb=0,derive_gid_from_media_rw,write_gid=1015,read_gid=9997,fsuid_cache=1023,userid=0,lower_fs=f2fs,casefold,projid,fscrypt,appfuse,pass_through,derive_gid_cache,isolated_storage,legacy_layout,default_permissions,visible=true,app_data_isolation,media_provider,obb_isolation,aid_media_rw=1023,aid_everybody=9997,aid_ext_data_rw=1078 0 0"
    };
    private static final String DEFAULT_PROC_MOUNTS = buildDefaultProcMounts();

    @Override
    public void injectHook() {
        try {
            Class<?> runtimeClass = Class.forName("java.lang.Runtime");
            hookRuntimeExec(runtimeClass, "exec", String.class);
            hookRuntimeExec(runtimeClass, "exec", String.class, String[].class);
            hookRuntimeExec(runtimeClass, "exec", String.class, String[].class, File.class);
            hookRuntimeExec(runtimeClass, "exec", String[].class);
            hookRuntimeExec(runtimeClass, "exec", String[].class, String[].class);
            hookRuntimeExec(runtimeClass, "exec", String[].class, String[].class, File.class);
        } catch (Throwable e) {
            Slog.d(TAG, "hook runtime exec failed: " + e);
        }
        try {
            hookProcessBuilderStart(Class.forName("java.lang.ProcessBuilder"));
        } catch (Throwable e) {
            Slog.d(TAG, "hook process builder start failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookRuntimeExec(final Class<?> owner, final String methodName,
                                 Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    boolean trace = shouldTraceSandboxExec();
                    String command = formatCommand(callFrame.args);
                    String stack = trace ? stackTraceSummary() : "disabled";
                    if (shouldReturnSanitizedId(callFrame.args)) {
                        Slog.d(TAG, STAGE_SANITIZED_ID_LOG + " command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), methodName, command, stack, "sanitized_id");
                        callFrame.setResult(new StaticProcess(buildSanitizedId()));
                        return;
                    }
                    if (shouldReturnSanitizedProcSelfStatus(callFrame.args)) {
                        Slog.d(TAG, STAGE_SANITIZED_PROC_STATUS_LOG + " command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), methodName, command, stack, "sanitized_proc_status");
                        callFrame.setResult(new StaticProcess(buildSanitizedProcSelfStatus()));
                        return;
                    }
                    if (shouldReturnSanitizedProcMounts(callFrame.args)) {
                        Slog.d(TAG, STAGE_SANITIZED_LOG + " command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), methodName, command, stack, "sanitized");
                        callFrame.setResult(new StaticProcess(buildSanitizedProcMounts()));
                        return;
                    }
                    if (shouldReturnSanitizedGetprop(callFrame.args)) {
                        Slog.d(TAG, STAGE_SANITIZED_GETPROP_LOG + " command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), methodName, command, stack, "sanitized_getprop");
                        callFrame.setResult(new StaticProcess(buildSanitizedGetprop()));
                        return;
                    }
                    if (shouldTraceSandboxExec()) {
                        Slog.d(TAG, "before " + owner.getName() + "." + methodName
                                + " command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), methodName, command, stack, "before");
                    }
                }

                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if (!shouldTraceSandboxExec()) {
                        return;
                    }
                    Object process = callFrame.getResult();
                    Throwable throwable = callFrame.getThrowable();
                    String command = formatCommand(callFrame.args);
                    String stack = stackTraceSummary();
                    Slog.d(TAG, "after " + owner.getName() + "." + methodName
                            + " command=" + command
                            + " process=" + process
                            + " throwable=" + throwable
                            + " stack=" + stack);
                    recordRuntimeExec(owner.getName(), methodName, command, stack,
                            throwable == null ? "after" : "throwable=" + throwable.getClass().getName());
                    if (throwable == null) {
                        callFrame.setResult(process);
                    }
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook " + owner.getName() + "." + methodName + " failed: " + e);
        }
    }

    private void hookProcessBuilderStart(final Class<?> owner) {
        try {
            Method method = owner.getDeclaredMethod("start");
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    boolean trace = shouldTraceSandboxExec();
                    List<String> commandList = processBuilderCommand(callFrame.thisObject);
                    String command = formatCommand(commandList);
                    String stack = trace ? stackTraceSummary() : "disabled";
                    if (shouldReturnSanitizedId(commandList)) {
                        Slog.d(TAG, STAGE_SANITIZED_ID_LOG
                                + " method=ProcessBuilder.start command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), "ProcessBuilder.start",
                                command, stack, "sanitized_id");
                        callFrame.setResult(new StaticProcess(buildSanitizedId()));
                        return;
                    }
                    if (shouldReturnSanitizedProcSelfStatus(commandList)) {
                        Slog.d(TAG, STAGE_SANITIZED_PROC_STATUS_LOG
                                + " method=ProcessBuilder.start command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), "ProcessBuilder.start",
                                command, stack, "sanitized_proc_status");
                        callFrame.setResult(new StaticProcess(buildSanitizedProcSelfStatus()));
                        return;
                    }
                    if (shouldReturnSanitizedProcMounts(commandList)) {
                        Slog.d(TAG, STAGE_SANITIZED_LOG
                                + " method=ProcessBuilder.start command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), "ProcessBuilder.start",
                                command, stack, "sanitized");
                        callFrame.setResult(new StaticProcess(buildSanitizedProcMounts()));
                        return;
                    }
                    if (shouldReturnSanitizedGetprop(commandList)) {
                        Slog.d(TAG, STAGE_SANITIZED_GETPROP_LOG
                                + " method=ProcessBuilder.start command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), "ProcessBuilder.start",
                                command, stack, "sanitized_getprop");
                        callFrame.setResult(new StaticProcess(buildSanitizedGetprop()));
                        return;
                    }
                    if (shouldTraceSandboxExec()) {
                        Slog.d(TAG, "before " + owner.getName() + ".start"
                                + " command=" + command + " stack=" + stack);
                        recordRuntimeExec(owner.getName(), "start", command, stack, "before");
                    }
                }

                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if (!shouldTraceSandboxExec()) {
                        return;
                    }
                    Object process = callFrame.getResult();
                    Throwable throwable = callFrame.getThrowable();
                    List<String> commandList = processBuilderCommand(callFrame.thisObject);
                    String command = formatCommand(commandList);
                    String stack = stackTraceSummary();
                    Slog.d(TAG, "after " + owner.getName() + ".start"
                            + " command=" + command
                            + " process=" + process
                            + " throwable=" + throwable
                            + " stack=" + stack);
                    recordRuntimeExec(owner.getName(), "start", command, stack,
                            throwable == null ? "after" : "throwable=" + throwable.getClass().getName());
                    if (throwable == null) {
                        callFrame.setResult(process);
                    }
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook " + owner.getName() + ".start failed: " + e);
        }
    }

    private static boolean shouldTraceSandboxExec() {
        return isTruthy(System.getenv(EXEC_TRACE_ENV))
                || isTruthy(System.getProperty(EXEC_TRACE_JAVA_PROPERTY))
                || isTruthy(SystemPropertiesCompat.get(EXEC_TRACE_PROPERTY));
    }

    private static String formatCommand(Object[] args) {
        if (args == null || args.length == 0) {
            return "<empty>";
        }
        return formatCommand(args[0]);
    }

    private static String formatCommand(Object command) {
        if (command instanceof String[]) {
            return Arrays.toString((String[]) command);
        }
        if (command instanceof List) {
            return command.toString();
        }
        return String.valueOf(command);
    }

    private static boolean shouldReturnSanitizedProcMounts(Object[] args) {
        return isProcMountsCommandArgs(args);
    }

    private static boolean shouldReturnSanitizedProcMounts(List<String> command) {
        return isProcMountsCommand(command);
    }

    private static boolean shouldReturnSanitizedGetprop(Object[] args) {
        return isGetpropCommandArgs(args);
    }

    private static boolean shouldReturnSanitizedGetprop(List<String> command) {
        return isGetpropCommand(command);
    }

    private static boolean shouldReturnSanitizedId(Object[] args) {
        return isIdCommandArgs(args);
    }

    private static boolean shouldReturnSanitizedId(List<String> command) {
        return isIdCommand(command);
    }

    private static boolean shouldReturnSanitizedProcSelfStatus(Object[] args) {
        return isProcSelfStatusCommandArgs(args);
    }

    private static boolean shouldReturnSanitizedProcSelfStatus(List<String> command) {
        return isProcSelfStatusCommand(command);
    }

    private static boolean isProcMountsCommandArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return isProcMountsCommand(args[0]);
    }

    private static boolean isProcMountsCommand(Object command) {
        if (command instanceof String) {
            String value = ((String) command).trim();
            return isProcMountsShellLine(value);
        }
        if (command instanceof String[]) {
            String[] values = (String[]) command;
            return isProcMountsCommandTokens(values);
        }
        if (command instanceof List) {
            List<?> values = (List<?>) command;
            return isProcMountsCommandTokens(values.toArray(new Object[0]));
        }
        return false;
    }

    private static boolean isGetpropCommandArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return isGetpropCommand(args[0]);
    }

    private static boolean isGetpropCommand(Object command) {
        if (command instanceof String) {
            return isGetpropShellLine(((String) command).trim());
        }
        if (command instanceof String[]) {
            return isGetpropCommandTokens((String[]) command);
        }
        if (command instanceof List) {
            List<?> values = (List<?>) command;
            return isGetpropCommandTokens(values.toArray(new Object[0]));
        }
        return false;
    }

    private static boolean isIdCommandArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return isIdCommand(args[0]);
    }

    private static boolean isIdCommand(Object command) {
        if (command instanceof String) {
            return isIdShellLine(((String) command).trim());
        }
        if (command instanceof String[]) {
            return isIdCommandTokens((String[]) command);
        }
        if (command instanceof List) {
            List<?> values = (List<?>) command;
            return isIdCommandTokens(values.toArray(new Object[0]));
        }
        return false;
    }

    private static boolean isProcSelfStatusCommandArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return isProcSelfStatusCommand(args[0]);
    }

    private static boolean isProcSelfStatusCommand(Object command) {
        if (command instanceof String) {
            return isProcSelfStatusShellLine(((String) command).trim());
        }
        if (command instanceof String[]) {
            return isProcSelfStatusCommandTokens((String[]) command);
        }
        if (command instanceof List) {
            List<?> values = (List<?>) command;
            return isProcSelfStatusCommandTokens(values.toArray(new Object[0]));
        }
        return false;
    }

    private static boolean isProcMountsCommandTokens(Object[] values) {
        if (values == null || values.length < 2) {
            return false;
        }
        String first = String.valueOf(values[0]);
        String last = String.valueOf(values[values.length - 1]);
        if (isCatExecutable(first) && "/proc/mounts".equals(last)) {
            return true;
        }
        return values.length >= 3
                && isShellExecutable(first)
                && "-c".equals(String.valueOf(values[1]))
                && isProcMountsShellLine(String.valueOf(values[2]).trim());
    }

    private static boolean isGetpropCommandTokens(Object[] values) {
        if (values == null || values.length == 0) {
            return false;
        }
        String first = String.valueOf(values[0]);
        if (values.length == 1 && isGetpropExecutable(first)) {
            return true;
        }
        return values.length >= 3
                && isShellExecutable(first)
                && "-c".equals(String.valueOf(values[1]))
                && isGetpropShellLine(String.valueOf(values[2]).trim());
    }

    private static boolean isIdCommandTokens(Object[] values) {
        if (values == null || values.length == 0) {
            return false;
        }
        String first = String.valueOf(values[0]);
        if (values.length == 1 && isIdExecutable(first)) {
            return true;
        }
        if (values.length >= 2 && isToyboxExecutable(first) && "id".equals(String.valueOf(values[1]))) {
            return true;
        }
        return values.length >= 3
                && isShellExecutable(first)
                && "-c".equals(String.valueOf(values[1]))
                && isIdShellLine(String.valueOf(values[2]).trim());
    }

    private static boolean isProcSelfStatusCommandTokens(Object[] values) {
        if (values == null || values.length < 2) {
            return false;
        }
        String first = String.valueOf(values[0]);
        String last = String.valueOf(values[values.length - 1]);
        if (isCatExecutable(first) && "/proc/self/status".equals(last)) {
            return true;
        }
        return values.length >= 3
                && isShellExecutable(first)
                && "-c".equals(String.valueOf(values[1]))
                && isProcSelfStatusShellLine(String.valueOf(values[2]).trim());
    }

    private static boolean isProcMountsShellLine(String value) {
        return "cat /proc/mounts".equals(value)
                || "/system/bin/cat /proc/mounts".equals(value)
                || "toybox cat /proc/mounts".equals(value);
    }

    private static boolean isGetpropShellLine(String value) {
        return "getprop".equals(value)
                || "/system/bin/getprop".equals(value);
    }

    private static boolean isIdShellLine(String value) {
        return "id".equals(value)
                || "/system/bin/id".equals(value)
                || "toybox id".equals(value)
                || "/system/bin/toybox id".equals(value);
    }

    private static boolean isProcSelfStatusShellLine(String value) {
        return "cat /proc/self/status".equals(value)
                || "/system/bin/cat /proc/self/status".equals(value)
                || "toybox cat /proc/self/status".equals(value);
    }

    private static boolean isCatExecutable(String value) {
        return "cat".equals(value)
                || "/system/bin/cat".equals(value)
                || "toybox".equals(value)
                || "/system/bin/toybox".equals(value);
    }

    private static boolean isShellExecutable(String value) {
        return "sh".equals(value)
                || "/system/bin/sh".equals(value)
                || "toybox".equals(value)
                || "/system/bin/toybox".equals(value);
    }

    private static boolean isGetpropExecutable(String value) {
        return "getprop".equals(value)
                || "/system/bin/getprop".equals(value);
    }

    private static boolean isIdExecutable(String value) {
        return "id".equals(value)
                || "/system/bin/id".equals(value);
    }

    private static boolean isToyboxExecutable(String value) {
        return "toybox".equals(value)
                || "/system/bin/toybox".equals(value);
    }

    private static String buildSanitizedId() {
        int uid = getVirtualUid();
        String appName = buildAndroidAppName(uid);
        StringBuilder builder = new StringBuilder();
        builder.append("uid=").append(uid).append('(').append(appName).append(')')
                .append(" gid=").append(uid).append('(').append(appName).append(')')
                .append(" groups=").append(buildVirtualGroups(uid, true))
                .append(" context=").append(buildVirtualSelinuxContext(uid))
                .append('\n');
        return builder.toString();
    }

    private static String buildSanitizedProcSelfStatus() {
        int uid = getVirtualUid();
        String uidLine = "Uid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid;
        String gidLine = "Gid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid;
        String groupsLine = "Groups:\t" + buildVirtualGroups(uid, false).replace(',', ' ');
        String source = readProcSelfStatus();
        StringBuilder builder = new StringBuilder();
        boolean sawName = false;
        boolean sawUid = false;
        boolean sawGid = false;
        boolean sawGroups = false;
        String[] lines = source.split("\n");
        for (String line : lines) {
            if (line.startsWith("Name:")) {
                builder.append("Name:\tcat\n");
                sawName = true;
            } else if (line.startsWith("Uid:")) {
                builder.append(uidLine).append('\n');
                sawUid = true;
            } else if (line.startsWith("Gid:")) {
                builder.append(gidLine).append('\n');
                sawGid = true;
            } else if (line.startsWith("Groups:")) {
                builder.append(groupsLine).append('\n');
                sawGroups = true;
            } else if (line.length() > 0) {
                builder.append(line).append('\n');
            }
        }
        if (!sawName) {
            builder.insert(0, "Name:\tcat\n");
        }
        if (!sawUid) {
            builder.append(uidLine).append('\n');
        }
        if (!sawGid) {
            builder.append(gidLine).append('\n');
        }
        if (!sawGroups) {
            builder.append(groupsLine).append('\n');
        }
        return builder.toString();
    }

    private static String readProcSelfStatus() {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/self/status"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Throwable e) {
            Slog.d(TAG, "read /proc/self/status failed: " + e);
        }
        return builder.toString();
    }

    private static int getVirtualUid() {
        int uid = BActivityThread.getBUid();
        if (uid <= 0) {
            uid = android.os.Process.myUid();
        }
        return uid;
    }

    private static String buildAndroidAppName(int uid) {
        int userId = BUserHandle.getUserId(uid);
        int appIndex = Math.max(0, BUserHandle.getAppId(uid) - BUserHandle.AID_APP_START);
        return "u" + userId + "_a" + appIndex;
    }

    private static String buildVirtualGroups(int uid, boolean includeAppGroup) {
        String appName = buildAndroidAppName(uid);
        int appIndex = Math.max(0, BUserHandle.getAppId(uid) - BUserHandle.AID_APP_START);
        int cacheGid = BUserHandle.getCacheAppGid(uid);
        int sharedGid = BUserHandle.getSharedAppGid(uid);
        StringBuilder builder = new StringBuilder();
        if (includeAppGroup) {
            builder.append(uid).append('(').append(appName).append(')');
        }
        appendGroup(builder, 3003, includeAppGroup ? "inet" : null);
        appendGroup(builder, 9997, includeAppGroup ? "everybody" : null);
        if (cacheGid > 0) {
            appendGroup(builder, cacheGid, includeAppGroup ? appName + "_cache" : null);
        }
        if (sharedGid > 0) {
            appendGroup(builder, sharedGid, includeAppGroup ? "all_a" + appIndex : null);
        }
        return builder.toString();
    }

    private static void appendGroup(StringBuilder builder, int gid, String name) {
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(gid);
        if (name != null) {
            builder.append('(').append(name).append(')');
        }
    }

    private static String buildVirtualSelinuxContext(int uid) {
        int firstCategory = Math.max(0, BUserHandle.getAppId(uid) - BUserHandle.AID_APP_START);
        return "u:r:untrusted_app:s0:c" + firstCategory + ",c256,c512,c768";
    }

    private static String buildSanitizedGetprop() {
        return buildFallbackGetprop();
    }

    private static String buildFallbackGetprop() {
        StringBuilder builder = new StringBuilder();
        for (String key : DEFAULT_GETPROP_KEYS) {
            String value = sanitizeGetpropValue(SystemPropertiesCompat.get(key));
            if (value == null || value.length() == 0) {
                continue;
            }
            builder.append('[')
                    .append(key)
                    .append("]: [")
                    .append(value)
                    .append("]\n");
        }
        return builder.toString();
    }

    private static String sanitizeGetpropValue(String value) {
        if (value == null) {
            return "";
        }
        String hostPackageName = getHostPackageName();
        if (hostPackageName != null && hostPackageName.length() > 0
                && value.contains(hostPackageName)) {
            return "";
        }
        return value;
    }

    private static String buildSanitizedProcMounts() {
        if (!isDynamicProcMountsEnabled()) {
            return buildDefaultProcMounts();
        }
        return sanitizeProcMounts(readProcMounts());
    }

    private static String buildDefaultProcMounts() {
        StringBuilder builder = new StringBuilder(12 * 1024);
        for (String line : DEFAULT_PROC_MOUNTS_LINES) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static boolean isDynamicProcMountsEnabled() {
        return isTruthy(System.getenv(DYNAMIC_PROC_MOUNTS_ENV))
                || isTruthy(System.getProperty(DYNAMIC_PROC_MOUNTS_JAVA_PROPERTY))
                || isTruthy(SystemPropertiesCompat.get(DYNAMIC_PROC_MOUNTS_PROPERTY));
    }

    private static boolean shouldTraceStaticProcess() {
        return isTruthy(System.getenv(STATIC_PROCESS_TRACE_ENV))
                || isTruthy(System.getProperty(STATIC_PROCESS_TRACE_JAVA_PROPERTY))
                || isTruthy(SystemPropertiesCompat.get(STATIC_PROCESS_TRACE_PROPERTY));
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "1".equals(normalized)
                || "true".equalsIgnoreCase(normalized)
                || "yes".equalsIgnoreCase(normalized)
                || "on".equalsIgnoreCase(normalized);
    }

    private static String readProcMounts() {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/mounts"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (Throwable e) {
            Slog.d(TAG, "read /proc/mounts failed: " + e);
            return DEFAULT_PROC_MOUNTS;
        }
        return builder.length() == 0 ? DEFAULT_PROC_MOUNTS : builder.toString();
    }

    private static String sanitizeProcMounts(String mounts) {
        String hostPackageName = getHostPackageName();
        StringBuilder builder = new StringBuilder();
        String[] lines = mounts.split("\n");
        for (String line : lines) {
            String value = line == null ? "" : line.trim();
            if (value.length() == 0 || shouldDropProcMountLine(value, hostPackageName)) {
                continue;
            }
            builder.append(value).append('\n');
        }
        return builder.length() == 0 ? DEFAULT_PROC_MOUNTS : builder.toString();
    }

    private static boolean shouldDropProcMountLine(String line, String hostPackageName) {
        if (hostPackageName == null || hostPackageName.length() == 0) {
            return false;
        }
        return line.contains("/data/user/0/" + hostPackageName)
                || line.contains("/data/data/" + hostPackageName)
                || (line.contains("/mnt/expand/")
                && line.contains("/" + hostPackageName));
    }

    private static String getHostPackageName() {
        try {
            if (BlackBoxCore.getContext() != null) {
                return BlackBoxCore.getContext().getPackageName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> processBuilderCommand(Object receiver) {
        if (!(receiver instanceof ProcessBuilder)) {
            return null;
        }
        try {
            return ((ProcessBuilder) receiver).command();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stackTraceSummary() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(RuntimeExecProxy.class.getName())
                    || className.startsWith("top.canyie.pine.")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(className)
                    .append('.')
                    .append(element.getMethodName())
                    .append(':')
                    .append(element.getLineNumber());
            count++;
            if (count >= 10) {
                break;
            }
        }
        return builder.toString();
    }

    private static void recordRuntimeExec(String ownerName, String methodName, String command,
                                          String stack, String stage) {
        BlackBoxBinderMonitor.recordProxyCall(
                RUNTIME_EXEC_SERVICE,
                ownerName,
                methodName,
                RuntimeExecProxy.class.getSimpleName(),
                "command=" + command + ", stage=" + stage + ", stack=" + stack,
                "logged target runtime exec",
                "handled",
                false,
                false,
                false);
    }

    private static final class StaticProcess extends Process {
        private final byte[] stdout;
        private final byte[] stderr = new byte[0];
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();

        private StaticProcess(String stdout) {
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            trace("static process getOutputStream");
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            if (!shouldTraceStaticProcess()) {
                return new ByteArrayInputStream(stdout);
            }
            trace("static process getInputStream stdoutBytes=" + stdout.length);
            return new TracingInputStream(processId(), "stdout", stdout);
        }

        @Override
        public InputStream getErrorStream() {
            if (!shouldTraceStaticProcess()) {
                return new ByteArrayInputStream(stderr);
            }
            trace("static process getErrorStream stderrBytes=" + stderr.length);
            return new TracingInputStream(processId(), "stderr", stderr);
        }

        @Override
        public int waitFor() {
            trace("static process waitFor exit=0");
            return 0;
        }

        @Override
        public int exitValue() {
            trace("static process exitValue exit=0");
            return 0;
        }

        @Override
        public void destroy() {
            trace("static process destroy");
        }

        private String processId() {
            return Integer.toHexString(System.identityHashCode(this));
        }

        private void trace(String message) {
            if (shouldTraceStaticProcess()) {
                Slog.d(TAG, message + " process=" + processId());
            }
        }
    }

    private static final class TracingInputStream extends ByteArrayInputStream {
        private final String processId;
        private final String streamName;
        private int totalRead;
        private boolean eofLogged;
        private boolean closeLogged;

        private TracingInputStream(String processId, String streamName, byte[] data) {
            super(data);
            this.processId = processId;
            this.streamName = streamName;
        }

        @Override
        public synchronized int read() {
            int result = super.read();
            if (result >= 0) {
                totalRead++;
            } else {
                traceEof();
            }
            return result;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int result = super.read(buffer, offset, length);
            if (result > 0) {
                totalRead += result;
            } else if (result < 0) {
                traceEof();
            }
            return result;
        }

        @Override
        public void close() {
            if (!closeLogged && shouldTraceStaticProcess()) {
                String event = "stdout".equals(streamName)
                        ? "static process stdout close"
                        : "static process " + streamName + " close";
                Slog.d(TAG, event
                        + " process=" + processId
                        + " bytesRead=" + totalRead
                        + " eof=" + eofLogged);
                closeLogged = true;
            }
        }

        private void traceEof() {
            if (!eofLogged && shouldTraceStaticProcess()) {
                String event = "stdout".equals(streamName)
                        ? "static process stdout EOF"
                        : "static process " + streamName + " EOF";
                Slog.d(TAG, event
                        + " process=" + processId
                        + " bytesRead=" + totalRead);
                eofLogged = true;
            }
        }
    }
}

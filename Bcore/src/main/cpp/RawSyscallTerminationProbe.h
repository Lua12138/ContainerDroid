#ifndef BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H
#define BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H

namespace blackbox {
namespace rawsyscall {

void installRawSyscallEnvironmentProbe();
void installRawSyscallTerminationProbe();
void refreshRawSyscallProbeMaps();
void setRawSyscallTerminationBlocking(bool enabled);
void setHostPackage(const char *host_package);

}
}

#endif // BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H

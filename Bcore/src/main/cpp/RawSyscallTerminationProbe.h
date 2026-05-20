#ifndef BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H
#define BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H

namespace blackbox {
namespace rawsyscall {

void installRawSyscallEnvironmentProbe();
void installRawSyscallTerminationProbe();
void refreshRawSyscallProbeMaps();
void setRawSyscallTerminationBlocking(bool enabled);

}
}

#endif // BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H

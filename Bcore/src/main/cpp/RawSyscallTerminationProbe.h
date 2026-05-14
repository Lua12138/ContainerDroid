#ifndef BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H
#define BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H

namespace blackbox {
namespace rawsyscall {

void installRawSyscallTerminationProbe();
void refreshRawSyscallProbeMaps();

}
}

#endif // BLACKBOX_RAW_SYSCALL_TERMINATION_PROBE_H

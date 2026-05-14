#ifndef BLACKBOX_SECCOMP_SHIELD_H
#define BLACKBOX_SECCOMP_SHIELD_H

namespace blackbox {
namespace seccomp {

void installSeccompShield();
void installTerminationOnlySeccompShield();
void installTerminationTrapSeccompShield();
void setVirtualUid(int virtual_uid);

}
}

#endif // BLACKBOX_SECCOMP_SHIELD_H

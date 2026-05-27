#pragma once

#include <stddef.h>
#include <stdint.h>

namespace bbguard {

constexpr uint8_t normalizeKey(uint32_t value) {
    return static_cast<uint8_t>((value & 0x7fu) + 1u);
}

template <size_t N, uint8_t KEY>
class XorString {
public:
    constexpr XorString(const char (&plain)[N]) : data_{}, decoded_(false) {
        for (size_t i = 0; i < N; ++i) {
            data_[i] = static_cast<char>(plain[i] ^ keyAt(i));
        }
    }

    const char *c_str() const {
        if (!decoded_) {
            for (size_t i = 0; i < N; ++i) {
                data_[i] = static_cast<char>(data_[i] ^ runtimeKeyAt(i));
            }
            decoded_ = true;
        }
        return data_;
    }

private:
    static constexpr uint8_t keyAt(size_t index) {
        return static_cast<uint8_t>(KEY + (index * 17u));
    }

    static uint8_t runtimeKeyAt(size_t index) {
        static volatile uint8_t runtimeSalt = 0;
        return static_cast<uint8_t>(KEY + (index * 17u) + runtimeSalt);
    }

    mutable char data_[N];
    mutable bool decoded_;
};

} // namespace bbguard

#define BB_GUARD_STR(value) \
    ([]() -> const ::bbguard::XorString<sizeof(value), \
        ::bbguard::normalizeKey(static_cast<uint32_t>(__LINE__) * 131u + 0x55u)> & { \
        static const ::bbguard::XorString<sizeof(value), \
            ::bbguard::normalizeKey(static_cast<uint32_t>(__LINE__) * 131u + 0x55u)> encrypted(value); \
        return encrypted; \
    }())

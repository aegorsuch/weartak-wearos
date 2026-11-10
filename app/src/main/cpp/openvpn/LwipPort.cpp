#include <cerrno> // for errno
#include <chrono> // for std::chrono::steady_clock
#include <cstdint> // for std::uint32_t Interger types
#include <cstdlib>
#include <sys/random.h>

#include "lwip/arch.h"

extern "C" {
// lwIP's sys_now() is expected to return milliseconds since boot.
//
u32_t sys_now(void) {
    const auto elapsed =
        std::chrono::steady_clock::now().time_since_epoch();

    const auto milliseconds =
        std::chrono::duration_cast<std::chrono::milliseconds>(
            elapsed
        ).count();

    return static_cast<u32_t>(milliseconds);
}
// lwIP's hook for random number generation is expected to return
unsigned int lwip_port_rand(void) {
    unsigned int value = 0;

    auto* destination =
        reinterpret_cast<unsigned char*>(&value);

    std::size_t remaining = sizeof(value);

    while (remaining != 0) {
        const ssize_t count =
            getrandom(destination, remaining, 0);

// getrandom() is documented to return -1 on error, and set errno.

        if (count < 0 && errno == EINTR) {
            continue;
        }

        if (count <= 0) {

            // lwIP's hook has no error return.
            // Do not silently substitute predictable randomness.

            std::abort();
        }

        destination += count;
        remaining -= static_cast<std::size_t>(count);
    }

    return value;
}

}
#include "OpenVpnTransport.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

int failures = 0;

void expectInvalid(
    std::span<const std::uint8_t> profile,
    std::string_view testName
) {
    const auto result =
        wearvpn::OpenVpnTransport::validateProfile(profile);

    if (result != wearvpn::TransportError::InvalidProfile) {
        std::cerr << "FAILED: " << testName << '\n';
        ++failures;
    }
}

} // namespace

int main() {
    const std::vector<std::uint8_t> empty;
    expectInvalid(
        std::span<const std::uint8_t>{empty.data(), empty.size()},
        "empty profile"
    );

    const std::vector<std::uint8_t> oversized(
        wearvpn::OpenVpnTransport::MaxProfileBytes + 1,
        static_cast<std::uint8_t>('x')
    );
    expectInvalid(
        std::span<const std::uint8_t>{
            oversized.data(),
            oversized.size()
        },
        "oversized profile"
    );

    const std::array<std::uint8_t, 2> malformedUtf8{
        0xC3,
        0x28
    };
    expectInvalid(
        std::span<const std::uint8_t>{malformedUtf8},
        "malformed UTF-8"
    );

    const std::array<std::uint8_t, 7> embeddedNul{
        'c', 'l', 'i', '\0', 'e', 'n', 't'
    };
    expectInvalid(
        std::span<const std::uint8_t>{embeddedNul},
        "embedded NUL"
    );

    if (failures != 0) {
        std::cerr << failures << " test(s) failed\n";
        return 1;
    }

    std::cout << "All profile-shape tests passed\n";
    return 0;
}

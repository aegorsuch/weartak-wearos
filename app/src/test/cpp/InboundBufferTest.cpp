#include "InboundBuffer.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <span>

int main() {
    wearvpn::InboundBuffer first(16, 4);
    const std::array<std::uint8_t, 8> packet{
        10, 11, 12, 13, 14, 15, 16, 17
    };

    assert(first.append(packet));
    assert(first.size() == packet.size());

    std::array<std::uint8_t, 3> prefix{};
    assert(first.read(prefix) == prefix.size());
    assert((prefix == std::array<std::uint8_t, 3>{10, 11, 12}));
    assert(first.size() == 5);

    std::array<std::uint8_t, 8> tail{};
    assert(first.read(tail) == 5);
    assert((std::array<std::uint8_t, 5>{
        tail[0], tail[1], tail[2], tail[3], tail[4]
    } == std::array<std::uint8_t, 5>{13, 14, 15, 16, 17}));
    assert(first.size() == 0);

    wearvpn::InboundBuffer second(4, 2);
    wearvpn::InboundBuffer third(4, 2);
    const std::array<std::uint8_t, 3> secondBytes{1, 2, 3};
    const std::array<std::uint8_t, 2> thirdBytes{8, 9};
    assert(second.append(secondBytes));
    assert(third.append(thirdBytes));

    std::array<std::uint8_t, 2> separateRead{};
    assert(second.read(separateRead) == separateRead.size());
    assert((separateRead == std::array<std::uint8_t, 2>{1, 2}));

    std::array<std::uint8_t, 4> siblingRead{};
    assert(third.read(siblingRead) == thirdBytes.size());
    assert(siblingRead[0] == 8);
    assert(siblingRead[1] == 9);
    assert(second.size() == 1);
    assert(third.size() == 0);

    const std::array<std::uint8_t, 4> overflow{4, 5, 6, 7};
    assert(!second.append(overflow));
    assert(second.size() == 1);
    assert(second.read(siblingRead) == 1);
    assert(siblingRead[0] == 3);

    first.clear();
    assert(first.size() == 0);
    assert(first.append(packet));
    return 0;
}

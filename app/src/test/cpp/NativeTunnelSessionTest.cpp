#include "NativeTunnelSession.h"

#include <array>
#include <atomic>
#include <cassert>
#include <cstdint>
#include <thread>
#include <vector>

int main() {
    wearvpn::NativeTunnelSession session;
    assert(session.state() == wearvpn::TunnelState::Stopped);
    assert(session.stop() == wearvpn::SessionError::None);
    assert(session.stop() == wearvpn::SessionError::None);

    const std::array<std::uint8_t, 1> payload{42};
    assert(session.closeStream(0) == wearvpn::SessionError::InvalidArgument);
    assert(session.readStream(0, 1, std::chrono::milliseconds{10}).error ==
           wearvpn::SessionError::InvalidArgument);
    assert(session.writeStream(
               0, payload, std::chrono::milliseconds{10}
           ).error == wearvpn::SessionError::InvalidArgument);
    assert(session.writeStream(
               1, {}, std::chrono::milliseconds{10}
           ).error == wearvpn::SessionError::InvalidArgument);

    for (const wearvpn::StreamId id : {1, 99, 4096}) {
        assert(session.closeStream(id) == wearvpn::SessionError::None);
        assert(session.closeStream(id) == wearvpn::SessionError::None);
        assert(session.readStream(id, 1, std::chrono::milliseconds{10}).error ==
               wearvpn::SessionError::StreamNotFound);
        assert(session.writeStream(
                   id, payload, std::chrono::milliseconds{10}
               ).error == wearvpn::SessionError::StreamNotFound);
    }

    std::atomic<bool> failed{false};
    std::vector<std::thread> callers;
    callers.reserve(8);

    for (int index = 0; index < 8; ++index) {
        callers.emplace_back([&] {
            for (int iteration = 0; iteration < 100; ++iteration) {
                if (session.state() != wearvpn::TunnelState::Stopped ||
                    session.stop() != wearvpn::SessionError::None ||
                    session.closeStream(7) != wearvpn::SessionError::None) {
                    failed.store(true);
                }
            }
        });
    }

    for (auto& caller : callers) {
        caller.join();
    }

    assert(!failed.load());
    return 0;
}

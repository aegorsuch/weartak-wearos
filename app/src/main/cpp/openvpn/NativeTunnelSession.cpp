//This class creates a native tunnel session for OpenVPN on WearOS devices. It manages the lifecycle of the OpenVPN connection, including starting and stopping the tunnel, opening and closing streams, and handling data transmission.
//The class uses lwIP for networking and ensures that only one session can exist at a time. It also provides error handling and synchronization mechanisms to ensure thread safety and proper resource management.

#include "NativeTunnelSession.h"
#include "InboundBuffer.h"
#include "OpenVpnTransport.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <limits>
#include <map>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <utility>

#include <arpa/inet.h>

#include "lwip/init.h"
#include "lwip/ip4.h"
#include "lwip/ip4_frag.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcp.h"
#include "lwip/timeouts.h"

// Isolated dependency on the pinned lwIP revision:
//
// purgeStack() uses these lists to remove detached closing/TIME_WAIT
// PCBs at a generation boundary.
#include "lwip/priv/tcp_priv.h"

namespace wearvpn {
namespace {

using Clock = std::chrono::steady_clock;
using Milliseconds = std::chrono::milliseconds;

// Application-owned queue and operation limits.
constexpr std::size_t kMaxCommands = 128;
constexpr std::size_t kMaxCommandBytes = 1024 * 1024;
constexpr std::size_t kMaxStreams = 8;
constexpr std::size_t kMaxCallBytes = 64 * 1024;
constexpr std::size_t kMaxInboundBytes = 256 * 1024;
constexpr std::size_t kMaxInboundChunks = 1024;
constexpr std::size_t kMaxOutboundPackets = 128;
constexpr std::size_t kMaxOutboundBytes = 1024 * 1024;

// Bound each loop's work so one source cannot monopolize the worker.
constexpr std::size_t kCommandsPerTurn = 8;
constexpr std::size_t kPacketsPerTurn = 32;
constexpr std::size_t kWriteChunksPerTurn = 4;

constexpr Milliseconds kPollInterval{2};
constexpr Milliseconds kMaxTimeout{60000};
constexpr Milliseconds kStartTimeout{30000};

// lwIP uses process-wide state. This lease permits one session owner.
std::atomic<bool> sessionOwned{false};
std::once_flag lwipOnce;

// Only the current owner's worker touches this counter.
//
// It deliberately survives tunnel restarts and C++ session recreation.
// Old IDs therefore cannot identify newly created streams.
StreamId nextStreamId = 1;

struct StackLease {
    StackLease() {
        bool expected = false;

        if (!sessionOwned.compare_exchange_strong(expected, true)) {
            throw std::runtime_error("SESSION_ALREADY_EXISTS");
        }
    }

    ~StackLease() {
        sessionOwned.store(false);
    }
};

// Overwrite application-owned byte storage before releasing it.
void wipe(std::uint8_t* bytes, std::size_t size) noexcept {
    volatile std::uint8_t* destination = bytes;

    while (size != 0) {
        *destination++ = 0;
        --size;
    }
}

// Move-only storage with automatic wiping.
//
// This wipes this wrapper's bytes. It does not wipe copies retained
// internally by lwIP, OpenVPN, the allocator, or the operating system.
struct Bytes {
    std::vector<std::uint8_t> value;

    Bytes() = default;

    explicit Bytes(std::size_t size)
        : value(size) {}

    explicit Bytes(std::span<const std::uint8_t> source)
        : value(source.begin(), source.end()) {}

    Bytes(const Bytes&) = delete;
    Bytes& operator=(const Bytes&) = delete;

    Bytes(Bytes&&) noexcept = default;

    Bytes& operator=(Bytes&& other) noexcept {
        if (this != &other) {
            clear();
            value = std::move(other.value);
        }

        return *this;
    }

    ~Bytes() {
        clear();
    }

    void clear() noexcept {
        wipe(value.data(), value.size());
        value.clear();
    }
};

bool validTimeout(Milliseconds timeout) noexcept {
    return timeout.count() > 0 && timeout <= kMaxTimeout;
}

// Only fixed errors cross the session interface.
// No arbitrary OpenVPN diagnostic text is retained here.
SessionError translate(TransportError error) noexcept {
    switch (error) {
    case TransportError::None:
        return SessionError::None;

    case TransportError::InvalidArgument:
        return SessionError::InvalidArgument;

    case TransportError::InvalidProfile:
        return SessionError::InvalidProfile;

    case TransportError::UnsupportedAuthentication:
        return SessionError::UnsupportedAuthentication;

    case TransportError::AlreadyRunning:
        return SessionError::AlreadyRunning;

    case TransportError::ConnectionTimeout:
        return SessionError::Timeout;

    case TransportError::EventQueueFull:
        return SessionError::QueueFull;

    case TransportError::InternalError:
        return SessionError::InternalError;

    default:
        return SessionError::NetworkFailure;
    }
}

} // namespace

struct NativeTunnelSession::Impl {
    enum class Kind {
        Start,
        Open,
        Write
    };

    struct Stream;

    struct Outcome {
        StreamId id = 0;
        std::size_t accepted = 0;
        SessionError error = SessionError::None;
    };

    struct Request {
        // Set before enqueueing, then treated as immutable metadata.
        Kind kind = Kind::Start;
        std::uint64_t generation = 0;
        std::uint32_t ipv4 = 0; // Network byte order.
        std::uint16_t port = 0;
        Clock::time_point deadline{};

        // Owned request bytes, wiped by the worker after completion.
        Bytes payload;
        std::shared_ptr<Stream> stream;

        // Caller/worker completion and cancellation synchronization.
        std::mutex mutex;
        std::condition_variable changed;
        bool done = false;
        bool cancelled = false;
        Outcome outcome;
    };

    struct Stream {
        StreamId id = 0;
        Impl* owner = nullptr;

        // Worker-only fields.
        //
        // Public callers must never inspect or operate on pcb.
        tcp_pcb* pcb = nullptr;
        std::shared_ptr<Request> opening;
        std::shared_ptr<Request> writing;

        // Shared fields: every access requires this stream's mutex.
        std::mutex mutex;
        std::condition_variable changed;

        bool connected = false;
        bool closeRequested = false;
        bool eof = false;
        SessionError error = SessionError::None;

        InboundBuffer inbound{kMaxInboundBytes, kMaxInboundChunks};
        std::size_t windowCredit = 0;
    };

    // Declared first, therefore destroyed last.
    // The lease is released only after the worker has been joined.
    StackLease lease;

    // Protects public state, command admission, and registry lookup.
    //
    // Never hold control while acquiring a stream/request mutex.
    mutable std::mutex control;
    std::condition_variable workChanged;
    std::condition_variable stateChanged;

    TunnelState tunnelState = TunnelState::Stopped;
    SessionError lastError = SessionError::None;
    std::uint64_t generation = 0;

    bool stopRequested = false;
    bool shutdownRequested = false;

    std::deque<std::shared_ptr<Request>> commands;
    std::size_t commandBytes = 0;

    // Only the worker inserts/erases records.
    // Public lookup uses control and returns shared ownership.
    std::map<StreamId, std::shared_ptr<Stream>> streams;

    // Worker-only fields below.
    OpenVpnTransport* transport = nullptr; // Borrows run()'s object.

    bool initialized = false;
    bool cleaning = false;
    SessionError terminalError = SessionError::None;

    netif interface{};
    bool interfaceAdded = false;

    std::deque<Bytes> outbound;
    std::size_t outboundBytes = 0;

    std::array<
        std::uint8_t,
        OpenVpnTransport::MaxIpPacketBytes
    > input{};

    // Declared last so all data exists before the worker starts.
    std::thread worker;

    Impl()
        : worker([this] {
            run();
        }) {}

    ~Impl() {
        {
            std::lock_guard lock(control);

            shutdownRequested = true;
            stopRequested = true;
            tunnelState = TunnelState::Stopping;
        }

        stateChanged.notify_all();
        workChanged.notify_one();

        // The worker keeps pumping transport shutdown until reap succeeds.
        worker.join();
    }

    TunnelState state() const {
        std::lock_guard lock(control);
        return tunnelState;
    }

    SessionError stop() {
        {
            std::lock_guard lock(control);

            if (tunnelState == TunnelState::Stopped) {
                return SessionError::None;
            }

            stopRequested = true;
            tunnelState = TunnelState::Stopping;
        }

        stateChanged.notify_all();
        workChanged.notify_one();

        return SessionError::None;
    }

    std::shared_ptr<Stream> lookup(StreamId id) {
        std::lock_guard lock(control);

        const auto found = streams.find(id);

        return found == streams.end()
            ? nullptr
            : found->second;
    }

    SessionError close(StreamId id) {
        if (id == 0) {
            return SessionError::InvalidArgument;
        }

        const auto stream = lookup(id);

        if (!stream) {
            return SessionError::None;
        }

        {
            std::lock_guard lock(stream->mutex);
            stream->closeRequested = true;
        }

        stream->changed.notify_all();
        workChanged.notify_one();

        return SessionError::None;
    }

    SessionError awaitConnected(Milliseconds timeout) {
        if (!validTimeout(timeout)) {
            return SessionError::InvalidArgument;
        }

        const auto deadline = Clock::now() + timeout;

        std::unique_lock lock(control);
        const auto observedGeneration = generation;

        if (!stateChanged.wait_until(lock, deadline, [&] {
                return tunnelState != TunnelState::Connecting ||
                       generation != observedGeneration;
            })) {
            return SessionError::Timeout;
        }

        // A waiter for one generation must not report success for another.
        if (generation != observedGeneration) {
            return SessionError::NotConnected;
        }

        if (tunnelState == TunnelState::Connected) {
            return SessionError::None;
        }

        if (tunnelState == TunnelState::Stopping) {
            return SessionError::Stopping;
        }

        return tunnelState == TunnelState::Error
            ? lastError
            : SessionError::NotConnected;
    }

    Outcome submit(
        Kind kind,
        std::span<const std::uint8_t> bytes,
        std::uint32_t ipv4,
        std::uint16_t port,
        const std::shared_ptr<Stream>& stream,
        Milliseconds timeout
    ) {
        const auto deadline = Clock::now() + timeout;
        std::shared_ptr<Request> request;

        {
            std::lock_guard lock(control);

            if (shutdownRequested ||
                tunnelState == TunnelState::Stopping) {
                return {0, 0, SessionError::Stopping};
            }

            if (kind == Kind::Start) {
                if (tunnelState == TunnelState::Connecting ||
                    tunnelState == TunnelState::Connected) {
                    return {0, 0, SessionError::AlreadyRunning};
                }

                if (generation ==
                    std::numeric_limits<std::uint64_t>::max()) {
                    return {0, 0, SessionError::InternalError};
                }
            } else if (tunnelState != TunnelState::Connected) {
                return {0, 0, SessionError::NotConnected};
            }

            // Recheck identity after lookup because stop/close may
            // have removed the stream before this submission.
            if (kind == Kind::Write) {
                const auto found = streams.find(stream->id);

                if (found == streams.end() ||
                    found->second != stream) {
                    return {0, 0, SessionError::StreamNotFound};
                }
            }

            if (commands.size() >= kMaxCommands ||
                bytes.size() > kMaxCommandBytes - commandBytes) {
                return {0, 0, SessionError::QueueFull};
            }

            if (Clock::now() >= deadline) {
                return {0, 0, SessionError::Timeout};
            }

            // Admission and copying share control, preventing simultaneous
            // callers from allocating past the queued-payload budget.
            request = std::make_shared<Request>();

            request->kind = kind;
            request->generation =
                generation + (kind == Kind::Start ? 1 : 0);

            request->ipv4 = ipv4;
            request->port = port;
            request->deadline = deadline;
            request->stream = stream;
            request->payload = Bytes(bytes);

            // Allocation may throw. Public state is unchanged until
            // the request has successfully entered the queue.
            commands.push_back(request);
            commandBytes += bytes.size();

            if (kind == Kind::Start) {
                generation = request->generation;
                tunnelState = TunnelState::Connecting;
                lastError = SessionError::None;
            }
        }

        stateChanged.notify_all();
        workChanged.notify_one();

        std::unique_lock lock(request->mutex);

        try {
            if (!request->changed.wait_until(lock, deadline, [&] {
                    return request->done;
                })) {
                // This mutex also covers tcp_write and its accepted-count
                // update. Cancellation prevents further byte acceptance.
                request->cancelled = true;
                request->done = true;
                request->outcome.id = 0;
                request->outcome.error = SessionError::Timeout;
            }
        } catch (...) {
            request->cancelled = true;
            request->done = true;
            request->outcome.id = 0;
            request->outcome.error = SessionError::InternalError;
        }

        const auto outcome = request->outcome;

        lock.unlock();
        workChanged.notify_one();

        return outcome;
    }

    StreamReadResult read(
        StreamId id,
        std::size_t maxBytes,
        Milliseconds timeout
    ) {
        if (id == 0 ||
            maxBytes == 0 ||
            maxBytes > kMaxCallBytes ||
            !validTimeout(timeout)) {
            return {
                StreamReadStatus::Error,
                SessionError::InvalidArgument,
                {}
            };
        }

        const auto deadline = Clock::now() + timeout;
        const auto stream = lookup(id);

        if (!stream) {
            return {
                StreamReadStatus::Error,
                SessionError::StreamNotFound,
                {}
            };
        }

        std::unique_lock lock(stream->mutex);

        if (!stream->changed.wait_until(lock, deadline, [&] {
                return stream->inbound.size() != 0 ||
                       stream->eof ||
                       stream->closeRequested ||
                       stream->error != SessionError::None;
            })) {
            return {
                StreamReadStatus::Timeout,
                SessionError::Timeout,
                {}
            };
        }

        if (stream->closeRequested) {
            return {
                StreamReadStatus::Error,
                SessionError::StreamClosed,
                {}
            };
        }

        // Return already received bytes before reporting peer EOF/error.
        if (stream->inbound.size() != 0) {
            StreamReadResult result{
                StreamReadStatus::Data,
                SessionError::None,
                {}
            };

            // Allocate before moving the cursor. Allocation failure cannot
            // consume bytes without returning them to the caller.
            result.bytes.resize(
                std::min(maxBytes, stream->inbound.size())
            );
            const auto copied = stream->inbound.read(result.bytes);
            stream->windowCredit += copied;

            lock.unlock();

            // Only the worker may call tcp_recved.
            workChanged.notify_one();

            return result;
        }

        if (stream->error != SessionError::None) {
            return {
                StreamReadStatus::Error,
                stream->error,
                {}
            };
        }

        return {
            StreamReadStatus::Eof,
            SessionError::None,
            {}
        };
    }

    // The remaining implementation helpers run on the worker.

    static void finish(
        const std::shared_ptr<Request>& request,
        SessionError error,
        StreamId id = 0
    ) {
        if (!request) {
            return;
        }

        {
            std::lock_guard lock(request->mutex);

            // Preserve an outcome already published by cancellation.
            if (!request->done) {
                request->outcome.id = id;
                request->outcome.error = error;
                request->done = true;
            }

            request->payload.clear();
        }

        request->changed.notify_all();
    }

    static bool expired(
        const std::shared_ptr<Request>& request
    ) {
        std::lock_guard lock(request->mutex);

        return request->cancelled ||
               Clock::now() >= request->deadline;
    }

    std::shared_ptr<Request> popCommand() {
        std::lock_guard lock(control);

        if (commands.empty()) {
            return {};
        }

        auto request = std::move(commands.front());
        commands.pop_front();

        commandBytes -= request->payload.value.size();

        return request;
    }

    // Keep stream records alive without retaining the registry lock
    // while running callbacks or acquiring stream mutexes.
    auto snapshot() {
        std::array<
            std::shared_ptr<Stream>,
            kMaxStreams
        > result{};

        std::lock_guard lock(control);

        std::size_t count = 0;

        for (const auto& entry : streams) {
            result[count++] = entry.second;
        }

        return result;
    }

    static void detach(tcp_pcb* pcb) {
        tcp_arg(pcb, nullptr);
        tcp_recv(pcb, nullptr);
        tcp_sent(pcb, nullptr);
        tcp_err(pcb, nullptr);
        tcp_poll(pcb, nullptr, 0);
    }

    static void releasePcb(
        Stream& stream,
        bool graceful
    ) {
        auto* pcb = std::exchange(stream.pcb, nullptr);

        if (!pcb) {
            return;
        }

        detach(pcb);

        // After successful tcp_close, lwIP owns the remaining lifetime.
        // Never inspect that pointer again after success.
        if (!graceful || tcp_close(pcb) != ERR_OK) {
            tcp_abort(pcb);
        }
    }

    void retire(
        const std::shared_ptr<Stream>& stream,
        SessionError reason,
        bool graceful
    ) {
        releasePcb(*stream, graceful);

        {
            std::lock_guard lock(stream->mutex);

            stream->connected = false;
            stream->closeRequested = true;
            stream->error = reason;

            stream->inbound.clear();
            stream->windowCredit = 0;
        }

        stream->changed.notify_all();

        finish(stream->opening, reason);
        finish(stream->writing, reason);

        stream->opening.reset();
        stream->writing.reset();

        std::lock_guard lock(control);
        streams.erase(stream->id);
    }

    void purgeStack() {
        // Exclusive ownership means these are all this session's PCBs,
        // including ones handed to lwIP by earlier graceful closes.
        while (tcp_active_pcbs) {
            detach(tcp_active_pcbs);
            tcp_abort(tcp_active_pcbs);
        }

        while (tcp_bound_pcbs) {
            detach(tcp_bound_pcbs);
            tcp_abort(tcp_bound_pcbs);
        }

        while (tcp_tw_pcbs) {
            detach(tcp_tw_pcbs);
            tcp_abort(tcp_tw_pcbs);
        }

#if IP_REASSEMBLY
        // This pinned revision has no public "flush fragments" function.
        //
        // Expire every incomplete datagram before reusing the interface.
        // Output is disabled during cleanup, so expiry emits no traffic.
        for (unsigned tick = 0;
             tick <= IP_REASS_MAXAGE;
             ++tick) {
            ip_reass_tmr();
        }
#endif
    }

    void beginStop(SessionError reason) {
        if (cleaning) {
            return;
        }

        cleaning = true;
        terminalError = reason;

        {
            std::lock_guard lock(control);
            tunnelState = TunnelState::Stopping;
        }

        stateChanged.notify_all();

        // Wake stream callers and release every owned PCB.
        for (const auto& stream : snapshot()) {
            if (stream) {
                retire(
                    stream,
                    reason == SessionError::None
                        ? SessionError::StreamClosed
                        : reason,
                    false
                );
            }
        }

        // Reject queued work and wipe queued profile/write bytes.
        while (auto request = popCommand()) {
            finish(request, SessionError::Stopping);
        }

        if (initialized) {
            purgeStack();
        }

        if (interfaceAdded) {
            netif_set_down(&interface);
            netif_set_link_down(&interface);
            netif_remove(&interface);

            interfaceAdded = false;
        }

        outbound.clear();
        outboundBytes = 0;

        wipe(input.data(), input.size());

        // This requests cancellation. It does not join the connection.
        transport->requestStop();
    }

    void completeStop() {
        if (!cleaning ||
            !transport->connectionThreadFinished()) {
            return;
        }

        // The connection thread publishes its final event before
        // publishing "finished". Drain again after observing completion.
        TransportEvent event;

        while (transport->takeEvent(event)) {
        }

        if (!transport->reap()) {
            return;
        }

        {
            std::lock_guard lock(control);

            // An explicit user stop is not an application failure.
            lastError = stopRequested || shutdownRequested
                ? SessionError::None
                : terminalError;

            stopRequested = false;

            tunnelState = lastError == SessionError::None
                ? TunnelState::Stopped
                : TunnelState::Error;
        }

        cleaning = false;

        stateChanged.notify_all();
    }

    static err_t initializeInterface(netif* target) {
        target->name[0] = 'w';
        target->name[1] = 'v';

        target->output = &outputPacket;

        // This is a layer-3 interface: no Ethernet or ARP.
        target->flags = 0;

        return ERR_OK;
    }

    bool configureInterface(
        const TunnelAddress& address
    ) {
        if (interfaceAdded ||
            address.ipv4 == 0 ||
            address.prefixLength > 32 ||
            address.mtu < 576) {
            return false;
        }

        ip4_addr_t local{};
        ip4_addr_t gateway{};
        ip4_addr_t mask{};

        // The adapter already supplies these in network byte order.
        local.addr = address.ipv4;
        gateway.addr = address.gateway;

        const std::uint32_t hostMask =
            address.prefixLength == 0
                ? 0
                : (0xffffffffu << (32 - address.prefixLength));

        mask.addr = htonl(hostMask);

        interface = {};

        if (!netif_add(
                &interface,
                &local,
                &mask,
                &gateway,
                this,
                &initializeInterface,
                &ip4_input
            )) {
            return false;
        }

        interfaceAdded = true;
        interface.mtu = address.mtu;

        netif_set_default(&interface);
        netif_set_up(&interface);
        netif_set_link_up(&interface);

        return true;
    }

    static err_t outputPacket(
        netif* target,
        pbuf* packet,
        const ip4_addr_t*
    ) noexcept {
        auto& self =
            *static_cast<Impl*>(target->state);

        if (self.cleaning || !self.interfaceAdded) {
            return ERR_IF;
        }

        if (!packet || packet->tot_len < 20) {
            return ERR_BUF;
        }

        const auto size =
            static_cast<std::size_t>(packet->tot_len);

        if (self.outbound.size() >= kMaxOutboundPackets ||
            size > kMaxOutboundBytes - self.outboundBytes) {
            return ERR_MEM;
        }

        try {
            Bytes bytes(size);

            if (pbuf_copy_partial(
                    packet,
                    bytes.value.data(),
                    packet->tot_len,
                    0
                ) != packet->tot_len) {
                return ERR_BUF;
            }

            self.outbound.push_back(std::move(bytes));
            self.outboundBytes += size;

            return ERR_OK;
        } catch (...) {
            // No allocation exception crosses the C callback boundary.
            return ERR_MEM;
        }
    }

    static err_t connected(
        void* argument,
        tcp_pcb*,
        err_t error
    ) noexcept {
        auto& stream =
            *static_cast<Stream*>(argument);

        {
            std::lock_guard lock(stream.mutex);

            stream.connected = error == ERR_OK;

            if (error != ERR_OK) {
                stream.error = SessionError::NetworkFailure;
            }
        }

        stream.changed.notify_all();
        stream.owner->workChanged.notify_one();

        return ERR_OK;
    }

    static void failed(
        void* argument,
        err_t
    ) noexcept {
        auto& stream =
            *static_cast<Stream*>(argument);

        // lwIP freed the PCB before invoking tcp_err.
        // Never attempt to free it again.
        stream.pcb = nullptr;

        {
            std::lock_guard lock(stream.mutex);

            stream.connected = false;
            stream.error = SessionError::NetworkFailure;
        }

        stream.changed.notify_all();
        stream.owner->workChanged.notify_one();
    }

    static err_t received(
        void* argument,
        tcp_pcb*,
        pbuf* packet,
        err_t error
    ) noexcept {
        auto& stream =
            *static_cast<Stream*>(argument);

        try {
            std::lock_guard lock(stream.mutex);

            if (error != ERR_OK) {
                if (packet) {
                    pbuf_free(packet);
                }

                stream.error = SessionError::NetworkFailure;
                stream.changed.notify_all();

                return ERR_OK;
            }

            if (!packet) {
                // FIN closes the peer's sending half.
                //
                // Keep the PCB so the application can still write
                // and then explicitly close the stream.
                stream.eof = true;
                stream.changed.notify_all();

                return ERR_OK;
            }

            const auto size =
                static_cast<std::size_t>(packet->tot_len);

            if (size == 0) {
                pbuf_free(packet);
                return ERR_OK;
            }

            Bytes bytes(size);

            if (pbuf_copy_partial(
                    packet,
                    bytes.value.data(),
                    packet->tot_len,
                    0
                ) != packet->tot_len) {
                return ERR_MEM;
            }

            if (!stream.inbound.append(std::move(bytes.value))) {
                // We did not accept or free this pbuf.
                // lwIP retains it and may retry later.
                return ERR_MEM;
            }

            pbuf_free(packet);
            stream.changed.notify_all();

            // tcp_recved is deferred until read() consumes these bytes.
            return ERR_OK;
        } catch (...) {
            // The packet was not accepted/freed on allocation failure.
            return ERR_MEM;
        }
    }

    static err_t acknowledged(
        void* argument,
        tcp_pcb*,
        u16_t
    ) noexcept {
        auto& stream =
            *static_cast<Stream*>(argument);

        stream.owner->workChanged.notify_one();

        return ERR_OK;
    }

    void processOpen(
        const std::shared_ptr<Request>& request
    ) {
        auto stream = std::make_shared<Stream>();

        stream->owner = this;
        stream->opening = request;

        {
            std::lock_guard lock(control);

            if (streams.size() >= kMaxStreams) {
                // Complete outside control, without nesting locks.
            } else if (
                nextStreamId <=
                static_cast<StreamId>(
                    std::numeric_limits<std::int64_t>::max()
                )
            ) {
                // Positive IDs also fit the future signed JNI long.
                stream->id = nextStreamId++;

                streams.emplace(stream->id, stream);
            }
        }

        if (stream->id == 0) {
            finish(request, SessionError::QueueFull);
            return;
        }

        stream->pcb = tcp_new_ip_type(IPADDR_TYPE_V4);

        if (!stream->pcb) {
            retire(stream, SessionError::QueueFull, false);
            return;
        }

        // stream.get() remains valid because the registry owns the
        // record until these callbacks have been detached.
        tcp_arg(stream->pcb, stream.get());
        tcp_recv(stream->pcb, &received);
        tcp_sent(stream->pcb, &acknowledged);
        tcp_err(stream->pcb, &failed);

        tcp_bind_netif(stream->pcb, &interface);

        ip_addr_t destination{};

        ip_addr_set_ip4_u32(
            &destination,
            request->ipv4
        );

        if (tcp_connect(
                stream->pcb,
                &destination,
                request->port,
                &connected
            ) != ERR_OK) {
            retire(
                stream,
                SessionError::NetworkFailure,
                false
            );
        }

        // serviceStreams() calls tcp_output and checks the open deadline.
    }

    void processWrite(
        const std::shared_ptr<Request>& request
    ) {
        const auto& stream = request->stream;

        SessionError error = SessionError::None;

        {
            std::lock_guard lock(stream->mutex);

            if (stream->closeRequested) {
                error = SessionError::StreamClosed;
            } else if (stream->error != SessionError::None) {
                error = stream->error;
            } else if (!stream->connected) {
                error = SessionError::NotConnected;
            }
        }

        if (error != SessionError::None) {
            finish(request, error);
        } else if (stream->writing) {
            // One active write per stream keeps ordering and memory
            // bounded. A competing write gets an explicit result.
            finish(request, SessionError::QueueFull);
        } else {
            stream->writing = request;
        }
    }

    void processCommands() {
        for (std::size_t count = 0;
             count < kCommandsPerTurn;
             ++count) {
            auto request = popCommand();

            if (!request) {
                break;
            }

            try {
                if (cleaning ||
                    state() == TunnelState::Stopping) {
                    finish(request, SessionError::Stopping);
                    continue;
                }

                if (expired(request)) {
                    finish(request, SessionError::Timeout);

                    if (request->kind == Kind::Start) {
                        beginStop(SessionError::Timeout);
                    }

                    continue;
                }

                bool current;

                {
                    std::lock_guard lock(control);
                    current =
                        request->generation == generation;
                }

                if (!current) {
                    finish(
                        request,
                        SessionError::NotConnected
                    );

                    continue;
                }

                if (request->kind == Kind::Start) {
                    const auto error = initialized
                        ? translate(
                            transport->start(
                                request->payload.value,
                                request->generation
                            )
                        )
                        : SessionError::InternalError;

                    // The transport has evaluated/copied the profile.
                    request->payload.clear();

                    if (expired(request)) {
                        finish(
                            request,
                            SessionError::Timeout
                        );

                        beginStop(SessionError::Timeout);
                    } else {
                        finish(request, error);

                        if (error != SessionError::None) {
                            beginStop(error);
                        }
                    }
                } else if (
                    state() != TunnelState::Connected
                ) {
                    finish(
                        request,
                        SessionError::NotConnected
                    );
                } else if (
                    request->kind == Kind::Open
                ) {
                    processOpen(request);
                } else {
                    processWrite(request);
                }
            } catch (...) {
                finish(
                    request,
                    SessionError::InternalError
                );

                beginStop(SessionError::InternalError);
            }
        }
    }

    void pollEvents() {
        TransportEvent event;

        for (std::size_t count = 0;
             count < OpenVpnTransport::MaxQueuedEvents &&
             transport->takeEvent(event);
             ++count) {
            bool current;

            {
                std::lock_guard lock(control);
                current =
                    event.generation == generation;
            }

            if (!current ||
                cleaning ||
                state() == TunnelState::Stopping) {
                continue;
            }

            if (event.type == TransportEventType::Connected) {
                if (state() != TunnelState::Connecting) {
                    continue;
                }

                if (!configureInterface(event.address)) {
                    beginStop(SessionError::NetworkFailure);
                    continue;
                }

                {
                    std::lock_guard lock(control);

                    // A concurrent stop must not be undone by a late
                    // Connected notification.
                    if (tunnelState == TunnelState::Connecting) {
                        tunnelState = TunnelState::Connected;
                    }
                }

                stateChanged.notify_all();
            } else {
                auto error = translate(event.error);

                if (error == SessionError::None) {
                    error = SessionError::NetworkFailure;
                }

                beginStop(error);
            }
        }
    }

    void receivePackets() {
        for (std::size_t count = 0;
             count < kPacketsPerTurn;
             ++count) {
            if (state() != TunnelState::Connected) {
                return;
            }

            std::size_t size = 0;

            const auto result =
                transport->receiveIpPacket(input, size);

            if (result == PacketIoResult::WouldBlock) {
                return;
            }

            if (result != PacketIoResult::Complete) {
                beginStop(SessionError::NetworkFailure);
                return;
            }

            auto* packet = pbuf_alloc(
                PBUF_RAW,
                static_cast<u16_t>(size),
                PBUF_POOL
            );

            if (!packet) {
                wipe(input.data(), size);

                // Drop this packet under memory pressure.
                // TCP can recover through retransmission.
                continue;
            }

            const auto copied = pbuf_take(
                packet,
                input.data(),
                static_cast<u16_t>(size)
            );

            wipe(input.data(), size);

            if (copied != ERR_OK) {
                pbuf_free(packet);
                continue;
            }

            if (ip4_input(packet, &interface) != ERR_OK) {
                pbuf_free(packet);
            }
        }
    }

    void flushPackets() {
        for (std::size_t count = 0;
             count < kPacketsPerTurn && !outbound.empty();
             ++count) {
            if (state() != TunnelState::Connected) {
                return;
            }

            const auto result =
                transport->sendIpPacket(
                    outbound.front().value
                );

            if (result == PacketIoResult::WouldBlock) {
                // Keep the complete datagram at the front.
                return;
            }

            if (result != PacketIoResult::Complete) {
                beginStop(SessionError::NetworkFailure);
                return;
            }

            outboundBytes -=
                outbound.front().value.size();

            outbound.pop_front();
        }
    }

    void progressWrite(
        const std::shared_ptr<Stream>& stream
    ) {
        const auto request = stream->writing;

        if (!request) {
            return;
        }

        SessionError result = SessionError::None;
        bool complete = false;

        for (std::size_t count = 0;
             count < kWriteChunksPerTurn;
             ++count) {
            if (state() != TunnelState::Connected) {
                return;
            }

            // Lock order: stream, then request.
            //
            // tcp_write copies/queues bytes. tcp_output is called
            // later, after these locks have been released.
            std::lock_guard streamLock(stream->mutex);
            std::lock_guard requestLock(request->mutex);

            if (request->cancelled ||
                Clock::now() >= request->deadline) {
                result = SessionError::Timeout;
                complete = true;
                break;
            }

            if (stream->closeRequested ||
                !stream->pcb ||
                stream->error != SessionError::None) {
                result =
                    stream->error == SessionError::None
                        ? SessionError::StreamClosed
                        : stream->error;

                complete = true;
                break;
            }

            const auto remaining =
                request->payload.value.size() -
                request->outcome.accepted;

            if (remaining == 0) {
                complete = true;
                break;
            }

            const auto countBytes = std::min({
                remaining,
                static_cast<std::size_t>(
                    tcp_sndbuf(stream->pcb)
                ),
                static_cast<std::size_t>(
                    tcp_mss(stream->pcb)
                )
            });

            if (countBytes == 0) {
                break;
            }

            const auto error = tcp_write(
                stream->pcb,
                request->payload.value.data() +
                    request->outcome.accepted,
                static_cast<u16_t>(countBytes),
                TCP_WRITE_FLAG_COPY
            );

            if (error == ERR_MEM) {
                // Retry after ACK/timer progress.
                // Never wait here and block the worker.
                break;
            }

            if (error != ERR_OK) {
                stream->error =
                    SessionError::NetworkFailure;

                result = stream->error;
                complete = true;
                break;
            }

            request->outcome.accepted += countBytes;

            if (request->outcome.accepted ==
                request->payload.value.size()) {
                complete = true;
                break;
            }
        }

        if (complete) {
            finish(request, result);
            stream->writing.reset();
        }
    }

    static void returnWindowCredit(
        Stream& stream,
        std::size_t credit
    ) {
        while (stream.pcb && credit != 0) {
            const auto count = static_cast<u16_t>(
                std::min<std::size_t>(credit, 65535)
            );

            tcp_recved(stream.pcb, count);
            credit -= count;
        }
    }

    void serviceStreams() {
        for (const auto& stream : snapshot()) {
            if (!stream ||
                state() != TunnelState::Connected) {
                continue;
            }

            bool closeRequested;
            bool isConnected;
            SessionError error;
            std::size_t credit;

            {
                std::lock_guard lock(stream->mutex);

                closeRequested = stream->closeRequested;
                isConnected = stream->connected;
                error = stream->error;

                credit = std::exchange(
                    stream->windowCredit,
                    0
                );
            }

            if (closeRequested) {
                // Credit already-read bytes before graceful close.
                returnWindowCredit(*stream, credit);

                retire(
                    stream,
                    SessionError::StreamClosed,
                    true
                );

                continue;
            }

            if (stream->opening) {
                if (expired(stream->opening)) {
                    retire(
                        stream,
                        SessionError::Timeout,
                        false
                    );

                    continue;
                }

                if (error != SessionError::None) {
                    retire(stream, error, false);
                    continue;
                }

                if (isConnected) {
                    const auto request =
                        stream->opening;

                    bool cancelled;

                    {
                        // Completion and caller cancellation share
                        // this mutex, including their final race.
                        std::lock_guard lock(
                            request->mutex
                        );

                        cancelled =
                            request->cancelled ||
                            Clock::now() >= request->deadline;

                        if (!cancelled) {
                            request->outcome.id =
                                stream->id;

                            request->outcome.error =
                                SessionError::None;

                            request->done = true;
                        }
                    }

                    if (cancelled) {
                        retire(
                            stream,
                            SessionError::Timeout,
                            false
                        );

                        continue;
                    }

                    request->changed.notify_all();
                    stream->opening.reset();
                }
            }

            if (error != SessionError::None) {
                releasePcb(*stream, false);

                finish(stream->writing, error);
                stream->writing.reset();

                stream->changed.notify_all();

                // Retain bounded terminal state until explicit close.
                // Later reads can drain buffered bytes and see the error.
                continue;
            }

            returnWindowCredit(*stream, credit);

            progressWrite(stream);

            if (stream->pcb) {
                const auto outputError =
                    tcp_output(stream->pcb);

                if (outputError != ERR_OK &&
                    outputError != ERR_MEM &&
                    outputError != ERR_BUF) {
                    std::lock_guard lock(stream->mutex);

                    stream->error =
                        SessionError::NetworkFailure;

                    stream->changed.notify_all();
                }
            }
        }
    }

    void step() {
        bool shouldStop;

        {
            std::lock_guard lock(control);

            shouldStop =
                stopRequested || shutdownRequested;
        }

        if (shouldStop && !cleaning) {
            beginStop(SessionError::None);
        }

        processCommands();
        pollEvents();

        if (state() == TunnelState::Connected &&
            !cleaning) {
            receivePackets();

            if (!cleaning) {
                serviceStreams();
                flushPackets();
            }
        }

        // Keep lwIP timers on the owner worker, including while idle.
        if (initialized) {
            sys_check_timeouts();
        }

        completeStop();
    }

    void run() noexcept {
        // Construction, all method calls, and destruction of this
        // transport happen on the same worker.
        OpenVpnTransport ownedTransport;
        transport = &ownedTransport;

        try {
            std::call_once(lwipOnce, [] {
                lwip_init();
            });

            initialized = true;

            // A previous session may have left cyclic timer schedules.
            // Avoid replaying a long inactive period on owner recreation.
            sys_restart_timeouts();
        } catch (...) {
            std::lock_guard lock(control);

            lastError = SessionError::InternalError;
            tunnelState = TunnelState::Error;

            stateChanged.notify_all();
        }

        for (;;) {
            try {
                step();
            } catch (...) {
                beginStop(SessionError::InternalError);
            }

            std::unique_lock lock(control);

            if (shutdownRequested &&
                tunnelState == TunnelState::Stopped) {
                break;
            }

            if (commands.empty()) {
                workChanged.wait_for(
                    lock,
                    kPollInterval
                );
            }
        }

        transport = nullptr;

        // ownedTransport is destroyed here after successful reap.
    }
};

NativeTunnelSession::NativeTunnelSession()
    : impl_(std::make_unique<Impl>()) {}

NativeTunnelSession::~NativeTunnelSession() = default;

SessionError NativeTunnelSession::validateProfile(
    std::span<const std::uint8_t> profile
) noexcept {
    return translate(
        OpenVpnTransport::validateProfile(profile)
    );
}

SessionError NativeTunnelSession::start(
    std::span<const std::uint8_t> profile
) noexcept {
    if (profile.empty() ||
        profile.size() > OpenVpnTransport::MaxProfileBytes) {
        return SessionError::InvalidProfile;
    }

    try {
        return impl_->submit(
            Impl::Kind::Start,
            profile,
            0,
            0,
            {},
            kStartTimeout
        ).error;
    } catch (...) {
        return SessionError::InternalError;
    }
}

SessionError NativeTunnelSession::awaitConnected(
    Milliseconds timeout
) noexcept {
    try {
        return impl_->awaitConnected(timeout);
    } catch (...) {
        return SessionError::InternalError;
    }
}

TunnelState NativeTunnelSession::state() const noexcept {
    try {
        return impl_->state();
    } catch (...) {
        return TunnelState::Error;
    }
}

SessionError NativeTunnelSession::stop() noexcept {
    try {
        return impl_->stop();
    } catch (...) {
        return SessionError::InternalError;
    }
}

StreamOpenResult NativeTunnelSession::openStream(
    std::string_view ipv4,
    std::uint16_t port,
    Milliseconds timeout
) noexcept {
    if (ipv4.empty() ||
        ipv4.size() >= INET_ADDRSTRLEN ||
        ipv4.find('\0') != std::string_view::npos ||
        port == 0 ||
        !validTimeout(timeout)) {
        return {
            0,
            SessionError::InvalidArgument
        };
    }

    std::array<char, INET_ADDRSTRLEN> text{};

    std::copy(
        ipv4.begin(),
        ipv4.end(),
        text.begin()
    );

    in_addr address{};

    if (inet_pton(
            AF_INET,
            text.data(),
            &address
        ) != 1) {
        return {
            0,
            SessionError::InvalidArgument
        };
    }

    try {
        const auto result = impl_->submit(
            Impl::Kind::Open,
            {},
            address.s_addr,
            port,
            {},
            timeout
        );

        return {
            result.id,
            result.error
        };
    } catch (...) {
        return {
            0,
            SessionError::InternalError
        };
    }
}

StreamWriteResult NativeTunnelSession::writeStream(
    StreamId id,
    std::span<const std::uint8_t> bytes,
    Milliseconds timeout
) noexcept {
    if (id == 0 ||
        bytes.empty() ||
        bytes.size() > kMaxCallBytes ||
        !validTimeout(timeout)) {
        return {
            0,
            SessionError::InvalidArgument
        };
    }

    try {
        const auto stream = impl_->lookup(id);

        if (!stream) {
            return {
                0,
                SessionError::StreamNotFound
            };
        }

        const auto result = impl_->submit(
            Impl::Kind::Write,
            bytes,
            0,
            0,
            stream,
            timeout
        );

        return {
            result.accepted,
            result.error
        };
    } catch (...) {
        return {
            0,
            SessionError::InternalError
        };
    }
}

StreamReadResult NativeTunnelSession::readStream(
    StreamId id,
    std::size_t maxBytes,
    Milliseconds timeout
) noexcept {
    try {
        return impl_->read(
            id,
            maxBytes,
            timeout
        );
    } catch (...) {
        return {
            StreamReadStatus::Error,
            SessionError::InternalError,
            {}
        };
    }
}

SessionError NativeTunnelSession::closeStream(
    StreamId id
) noexcept {
    try {
        return impl_->close(id);
    } catch (...) {
        return SessionError::InternalError;
    }
}

} // namespace wearvpn
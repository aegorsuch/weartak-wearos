#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <span>
#include <utility>
#include <vector>

namespace wearvpn {

class InboundBuffer final {
public:
    InboundBuffer(std::size_t maxBytes, std::size_t maxChunks)
        : maxBytes_(maxBytes),
          maxChunks_(maxChunks) {}

    InboundBuffer(const InboundBuffer&) = delete;
    InboundBuffer& operator=(const InboundBuffer&) = delete;

    ~InboundBuffer() {
        clear();
    }

    bool append(std::span<const std::uint8_t> bytes) {
        if (!canAppend(bytes.size())) {
            return false;
        }

        Chunk chunk(bytes);
        return store(std::move(chunk));
    }

    bool append(std::vector<std::uint8_t>&& bytes) {
        if (!canAppend(bytes.size())) {
            return false;
        }

        Chunk chunk(std::move(bytes));
        return store(std::move(chunk));
    }

    std::size_t read(std::span<std::uint8_t> destination) noexcept {
        std::size_t copied = 0;

        while (copied < destination.size() && !chunks_.empty()) {
            auto& front = chunks_.front().bytes;
            const auto count = std::min(
                destination.size() - copied,
                front.size() - frontOffset_
            );

            std::memcpy(
                destination.data() + copied,
                front.data() + frontOffset_,
                count
            );
            wipe(front.data() + frontOffset_, count);

            copied += count;
            frontOffset_ += count;
            size_ -= count;

            if (frontOffset_ == front.size()) {
                chunks_.pop_front();
                frontOffset_ = 0;
            }
        }

        return copied;
    }

    std::size_t size() const noexcept {
        return size_;
    }

    void clear() noexcept {
        chunks_.clear();
        frontOffset_ = 0;
        size_ = 0;
    }

private:
    static void wipe(std::uint8_t* bytes, std::size_t size) noexcept {
        volatile std::uint8_t* destination = bytes;

        while (size != 0) {
            *destination++ = 0;
            --size;
        }
    }

    bool canAppend(std::size_t count) const noexcept {
        return count == 0 ||
            (chunks_.size() < maxChunks_ &&
             count <= maxBytes_ - size_);
    }

    struct Chunk final {
        explicit Chunk(std::span<const std::uint8_t> source)
            : bytes(source.begin(), source.end()) {}

        explicit Chunk(std::vector<std::uint8_t>&& source) noexcept
            : bytes(std::move(source)) {}

        Chunk(Chunk&& other) noexcept {
            bytes.swap(other.bytes);
        }

        Chunk& operator=(Chunk&& other) noexcept {
            if (this != &other) {
                wipe(bytes.data(), bytes.size());
                bytes.clear();
                bytes.swap(other.bytes);
            }

            return *this;
        }

        Chunk(const Chunk&) = delete;
        Chunk& operator=(const Chunk&) = delete;

        ~Chunk() {
            wipe(bytes.data(), bytes.size());
        }

        std::vector<std::uint8_t> bytes;
    };

    bool store(Chunk&& chunk) {
        const auto count = chunk.bytes.size();
        if (count == 0) {
            return true;
        }

        chunks_.push_back(std::move(chunk));
        size_ += count;
        return true;
    }

    const std::size_t maxBytes_;
    const std::size_t maxChunks_;
    std::deque<Chunk> chunks_;
    std::size_t frontOffset_ = 0;
    std::size_t size_ = 0;
};

} // namespace wearvpn

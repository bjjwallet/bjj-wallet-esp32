// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <memory>
#include <new>
#include <string_view>

namespace airlock {
inline void wipe(void* memory, std::size_t size) noexcept {
    auto* p = static_cast<volatile unsigned char*>(memory);
    while (size--)
        *p++ = 0;
}

// Fixed storage, no implicit copies, no logging/stream operator. This only wipes
// buffers owned here; callers must also clear their UI/transport/crypto buffers.
template <std::size_t Capacity> class Secret {
  public:
    Secret() = default;
    ~Secret() {
        clear();
    }
    Secret(const Secret&) = delete;
    Secret& operator=(const Secret&) = delete;
    Secret(Secret&& other) noexcept {
        *this = static_cast<Secret&&>(other);
    }
    Secret& operator=(Secret&& other) noexcept {
        if (this != &other) {
            clear();
            data_ = other.data_;
            size_ = other.size_;
            other.clear();
        }
        return *this;
    }
    bool assign(std::string_view value) noexcept {
        if (value.size() > Capacity)
            return false;
        // Copy before clearing: assigning a substring of this buffer is valid.
        std::array<char, Capacity + 1> next{};
        for (std::size_t i = 0; i < value.size(); ++i)
            next[i] = value[i];
        const auto size = value.size();
        clear();
        data_ = next;
        size_ = size;
        wipe(next.data(), next.size());
        return true;
    }
    void clear() noexcept {
        wipe(data_.data(), data_.size());
        size_ = 0;
    }
    std::string_view view() const noexcept {
        return {data_.data(), size_};
    }
    bool equals(std::string_view value) const noexcept {
        if (value.size() > Capacity)
            return false;
        std::size_t diff = size_ ^ value.size();
        for (std::size_t i = 0; i < Capacity; ++i) {
            const auto rhs = i < value.size() ? static_cast<unsigned char>(value[i]) : 0;
            diff |= static_cast<unsigned char>(data_[i]) ^ rhs;
        }
        return diff == 0;
    }

  private:
    std::array<char, Capacity + 1> data_{};
    std::size_t size_ = 0;
};
using Credential = Secret<1024>;
using MnemonicWord = Secret<16>;

// Large packets belong on the heap, not an ESP32 FreeRTOS task's small stack.
// Moves transfer ownership and never duplicate the plaintext allocation.
class SecretBuffer {
  public:
    SecretBuffer() = default;
    ~SecretBuffer() {
        clear();
    }
    SecretBuffer(const SecretBuffer&) = delete;
    SecretBuffer& operator=(const SecretBuffer&) = delete;
    SecretBuffer(SecretBuffer&& other) noexcept {
        *this = static_cast<SecretBuffer&&>(other);
    }
    SecretBuffer& operator=(SecretBuffer&& other) noexcept {
        if (this != &other) {
            clear();
            data_ = std::move(other.data_);
            size_ = other.size_;
            other.size_ = 0;
        }
        return *this;
    }
    bool assign(std::string_view value, std::size_t limit = 1024) {
        if (value.size() > limit)
            return false;
        std::unique_ptr<char[]> next(new (std::nothrow) char[value.size() + 1]);
        if (!next)
            return false;
        for (std::size_t i = 0; i < value.size(); ++i)
            next[i] = value[i];
        next[value.size()] = '\0';
        const auto size = value.size();
        clear();
        data_ = std::move(next);
        size_ = size;
        return true;
    }
    void clear() noexcept {
        if (data_)
            wipe(data_.get(), size_ + 1);
        data_.reset();
        size_ = 0;
    }
    std::string_view view() const noexcept {
        return data_ ? std::string_view(data_.get(), size_) : std::string_view{};
    }

  private:
    std::unique_ptr<char[]> data_;
    std::size_t size_ = 0;
};
} // namespace airlock

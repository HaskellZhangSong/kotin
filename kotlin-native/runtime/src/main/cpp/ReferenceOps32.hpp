/*
 * Copyright 2010-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#pragma once

#include "Memory.h"
#include "std_support/Atomic.hpp"
#include <cstdint>

#if __has_feature(thread_sanitizer)
#include <sanitizer/tsan_interface.h>
#endif

// C++ memory model is in some sence stricter than the memmory model of real target CPUs.
// For example all the ptr-sized memory accesses on intel x86 and arm CPUs are atomic.
// Another case is the release-consume memory ordering, which can be achieved without additional memory fences on consume.
//
// However, LLVM often fails to properly optimize atomic operations.
// So we have to allow some imeplementation-defined UB here.
//
// Under this flag all tha operations with references in the kotlin heap
// are implemented in complete complience with C++ memory model.
#define STRICT_ATOMICS_IN_HEAP __has_feature(thread_sanitizer)

namespace kotlin::mm {
using Ptr32 = std::uint32_t;
// TODO: Make sure these operations work with any kind of thread stopping: safepoints and signals.

// TODO: Consider adding some kind of an `Object` type (that wraps `ObjHeader*`) which
//       will have these operations for a friendlier API.

/**
 * Represents direct low-level operations on Koltin references.
 * No GC barriers are inserted. Should be used with care!
 */
class DirectRefAccessor32 {
public:
    DirectRefAccessor32() = delete;
    DirectRefAccessor32& operator=(const DirectRefAccessor32&) = delete;

    explicit DirectRefAccessor32(Ptr32& fieldRef) noexcept : ref_(fieldRef) {}
    explicit DirectRefAccessor32(Ptr32* fieldPtr) noexcept : DirectRefAccessor32(*fieldPtr) {}
    DirectRefAccessor32(const DirectRefAccessor32& other) = default;

    Ptr32* location() const noexcept { return &ref_; }
    // conversion function
    PERFORMANCE_INLINE operator Ptr32() const noexcept { return load(); }
    PERFORMANCE_INLINE operator ObjHeader*() const noexcept { 
        Ptr32 value = load();
        return (ObjHeader*)(0L | value);
    }
    PERFORMANCE_INLINE Ptr32 operator=(Ptr32 desired) noexcept { store(desired); return desired; }

    PERFORMANCE_INLINE Ptr32 load() const noexcept {
#if STRICT_ATOMICS_IN_HEAP
        // Consume stores in the object, that were released on the object's allocation
        // See `ObjectOps.cpp`
        auto loaded = loadAtomic(std::memory_order_consume);
#if __has_feature(thread_sanitizer)
        // The stores were released by an atomic_thread_fence, TSAN doesn't support fences.
        __tsan_acquire(loaded);
#endif
        return loaded;
#else
        return ref_;
#endif
    }

    PERFORMANCE_INLINE void store(Ptr32 desired) noexcept {
#if STRICT_ATOMICS_IN_HEAP
        storeAtomic(desired, std::memory_order_relaxed);
#else
        ref_ = desired;
#endif
    }

    PERFORMANCE_INLINE auto atomic() noexcept {
        return std_support::atomic_ref{ref_};
    }
    PERFORMANCE_INLINE auto atomic() const noexcept {
        return std_support::atomic_ref{ref_};
    }

    PERFORMANCE_INLINE Ptr32 loadAtomic(std::memory_order order) const noexcept {
        return atomic().load(order);
    }
    PERFORMANCE_INLINE void storeAtomic(Ptr32 desired, std::memory_order order) noexcept {
        atomic().store(desired, order);
    }
    PERFORMANCE_INLINE Ptr32 exchange(Ptr32 desired, std::memory_order order) noexcept {
        return atomic().exchange(desired, order);
    }
    PERFORMANCE_INLINE bool compareAndExchange(Ptr32& expected, Ptr32 desired, std::memory_order order) noexcept {
        return atomic().compare_exchange_strong(expected, desired, order);
    }

private:
    Ptr32& ref_;
};

/**
 * Represents Koltin-level operations on Koltin references.
 * With all the necessary GC barriers etc.
 * Prefer using aliases below.
 */
template<bool kOnStack>
class RefAccessor32 {
public:
    RefAccessor32() = delete;
    RefAccessor32& operator=(const RefAccessor32&) = delete;

    explicit RefAccessor32(Ptr32& fieldRef) noexcept : direct_(fieldRef) {}
    explicit RefAccessor32(Ptr32* fieldPtr) noexcept : RefAccessor32(*fieldPtr) {}
    explicit RefAccessor32(ObjHeader** fieldPtr) noexcept : direct_(*reinterpret_cast<Ptr32*>(fieldPtr)) {}
    RefAccessor32(const RefAccessor32& other) noexcept : direct_(other.direct_) {}

    DirectRefAccessor32 direct() const noexcept { return direct_; }

//    void beforeLoad() noexcept;
//    void afterLoad() noexcept;
//    void beforeStore(Ptr32 value) noexcept;
//    void afterStore(Ptr32 value) noexcept;

    PERFORMANCE_INLINE operator Ptr32() noexcept { return load(); }

    PERFORMANCE_INLINE Ptr32 load() noexcept {
        AssertThreadState(ThreadState::kRunnable);
//        beforeLoad();
        auto result = direct_.load();
//        afterLoad();
        return result;
    }

    PERFORMANCE_INLINE Ptr32 loadAtomic(std::memory_order order) noexcept {
        AssertThreadState(ThreadState::kRunnable);
//        beforeLoad();
        auto result = direct_.loadAtomic(order);
//        afterLoad();
        return result;
    }

    PERFORMANCE_INLINE Ptr32 operator=(Ptr32 desired) noexcept { store(desired); return desired; }

    PERFORMANCE_INLINE void store(Ptr32 desired) noexcept {
        AssertThreadState(ThreadState::kRunnable);
//        beforeStore(desired);
        direct_.store(desired);
//        afterStore(desired);
    }

    PERFORMANCE_INLINE void storeAtomic(Ptr32 desired, std::memory_order order) noexcept {
        AssertThreadState(ThreadState::kRunnable);
//        beforeStore(desired);
        direct_.storeAtomic(desired, order);
//        afterStore(desired);
    }

    PERFORMANCE_INLINE Ptr32 exchange(Ptr32 desired, std::memory_order order) noexcept {
        AssertThreadState(ThreadState::kRunnable);
//        beforeLoad();
//        beforeStore(desired);
        auto result = direct_.exchange(desired, order);
//        afterStore(desired);
//        afterLoad();
        return result;
    }

    PERFORMANCE_INLINE bool compareAndExchange(Ptr32& expected, Ptr32 desired, std::memory_order order) noexcept {
        AssertThreadState(ThreadState::kRunnable);
//        beforeLoad();
//        beforeStore(desired);
        bool result = direct_.compareAndExchange(expected, desired, order);
//        afterStore(desired);
//        afterLoad();
        return result;
    }

private:
    DirectRefAccessor32 direct_;
};

using RefFieldAccessor32 = RefAccessor32<false>;
using GlobalRefAccessor32 = RefAccessor32<false>;
using StackRefAccessor32 = RefAccessor32<true>;

class RefField32 : private Pinned {
public:
    auto accessor() noexcept {
        return mm::RefFieldAccessor32(value_);
    }
    auto direct() noexcept {
        return accessor().direct();
    }
    // FIXME probably most of the uses should instead use accessor
    auto ptr() noexcept {
        return direct().location();
    }

    // TODO consider adding other operations
    Ptr32 operator=(Ptr32 value) noexcept {
        accessor() = value;
        return value_;
    }

    bool operator==(const RefField32& other) const noexcept {
        return value_ == other.value_;
    }

    bool operator!=(const RefField32& other) const noexcept {
        return !operator==(other);
    }

private:
    Ptr32 value_ = 0;
};

OBJ_GETTER(weakRefReadBarrier, std_support::atomic_ref<Ptr32> weakReferee) noexcept;

}

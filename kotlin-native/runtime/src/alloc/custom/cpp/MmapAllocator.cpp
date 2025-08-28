#if defined KONAN_OHOS || defined KONAN_LINUX
#include <MmapAllocator.hpp>
#include "Porting.h"
#include <sys/mman.h>
#include <KAssert.h>
#include <errno.h>
#include <string.h>
#include <algorithm>
#include <limits.h>
MmapAllocator::MmapAllocator(uintptr_t heapBase) : heapBase(heapBase), heapEnd(heapBase) {}

Ptr32_t MmapAllocator::Allocate(Size32_t size) {
    Size32_t alignedSize = (size + 0xFFF) & ~0xFFF;
    // Try to reuse free blocks first
    if (heapEnd.load() - heapBase >= 2 * GB) {
        Ptr32_t reusedPtr = TryReuseBlock(alignedSize);
        if (reusedPtr != PTR32_NULL) {
            return reusedPtr;
        }
    }
    // Allocate new memory if no suitable free block found
    return AllocateNewBlock(alignedSize);
}

void MmapAllocator::Deallocate(void* ptr) {
    if (!ptr) return;
    Ptr32_t ptr32 = ToPtr32(ptr);
    auto it = allocatedBlocks.find(ptr32);
    if (it == allocatedBlocks.end()) {
        konan::consoleErrorf("Deallocate at %p not found in allocated blocks\n", ptr);
        std::abort();
    }
    Size32_t size = it->second;
    AddToFreeList(ptr32, size);
}

void MmapAllocator::Withdraw() {
    if (allocatedBlocks.empty()) {
        RuntimeAssert(heapEnd.load() == heapBase,
                     "allocatedBlocks is empty but heapEnd is not equal to heapBase");
        return;
    }

    // Process blocks from the end of heap backwards
    bool foundFreeBlock = true;
    while (foundFreeBlock && !allocatedBlocks.empty()) {
        foundFreeBlock = false;

        // Find the highest address block
        auto lastBlock = std::max_element(allocatedBlocks.begin(), allocatedBlocks.end(),
            [](const auto& a, const auto& b) { return a.first < b.first; });

        if (lastBlock != allocatedBlocks.end()) {
            Ptr32_t blockPtr = lastBlock->first;
            Size32_t blockSize = lastBlock->second;

            // Check if this block is free
            if (IsBlockFree(blockPtr, blockSize)) {
                // Remove from free lists
                RemoveFromFreeList(blockPtr, blockSize);

                // Unmap the memory
                if (munmap(ToPtr(blockPtr), blockSize) == 0) {
                    heapEnd.fetch_sub(blockSize);
                    allocatedBlocks.erase(lastBlock);
                    foundFreeBlock = true;

                    konan::consolePrintf("[INFO] Withdrew block at: %p with size: %d\n",
                                       ToPtr(blockPtr), blockSize);
                } else {
                    konan::consoleErrorf("Failed to unmap block at %p: %s\n",
                                       ToPtr(blockPtr), strerror(errno));
                }
            }
        }
    }
}

uintptr_t MmapAllocator::GetHeapBase() {
    return heapBase;
}

Ptr32_t MmapAllocator::GetHeapEnd() {
    return heapEnd.load();
}

// Private helper methods
Ptr32_t MmapAllocator::TryReuseBlock(Size32_t alignedSize) {
    std::lock_guard<std::mutex> lock(mutex);
    // Try exact size matches first
    if (alignedSize == 16 * KB && !freeBlockSmallSize.empty()) {
        auto it = freeBlockSmallSize.begin();
        Ptr32_t ptr = *it;
        freeBlockSmallSize.erase(it);
        return ptr;
    }

    if (alignedSize == 64 * KB && !freeBlock64KB.empty()) {
        auto it = freeBlock64KB.begin();
        Ptr32_t ptr = *it;
        freeBlock64KB.erase(it);
        return ptr;
    }

    // For other sizes, find best fit from large blocks
    auto bestFit = freeBlockLargeSize.end();
    Size32_t bestSize = UINT_MAX;

    for (auto it = freeBlockLargeSize.begin(); it != freeBlockLargeSize.end(); ++it) {
        auto sizeIt = allocatedBlocks.find(*it);
        if (sizeIt != allocatedBlocks.end()) {
            Size32_t blockSize = sizeIt->second;
            if (blockSize >= alignedSize && blockSize < bestSize) {
                bestFit = it;
                bestSize = blockSize;
            }
        }
    }

    if (bestFit != freeBlockLargeSize.end()) {
        Ptr32_t ptr = *bestFit;
        freeBlockLargeSize.erase(bestFit);
        // If block is much larger, consider splitting (simple heuristic)
        if (bestSize > alignedSize * 2 && bestSize > 64 * KB) {
            SplitBlock(ptr, alignedSize, bestSize);
        }

        return ptr;
    }

    return PTR32_NULL;
}

Ptr32_t MmapAllocator::AllocateNewBlock(Size32_t alignedSize) {
#ifdef KONAN_WINDOWS
    RuntimeFail("mmap is not available on mingw");
#elif KONAN_LINUX
    int flags = MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED | MAP_POPULATE;
#else
    int flags = MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED;
#endif
    Ptr32_t old_base;
    Ptr32_t new_base;
    do {
      old_base = heapEnd.load(std::memory_order_relaxed);
      new_base = old_base + alignedSize;
    } while (!heapEnd.compare_exchange_weak(old_base, new_base));

    void* ptr = mmap(ToPtr(old_base), alignedSize,
                     PROT_READ | PROT_WRITE, flags, -1, 0);

    if (ptr == MAP_FAILED) {
        konan::consoleErrorf("Allocate at %p mmap failed: %s\n",
                           ToPtr(heapEnd.load()), strerror(errno));
        std::abort();
    }

    Ptr32_t ptr32 = ToPtr32(ptr);
    heapEnd.fetch_add(alignedSize);
    std::lock_guard<std::mutex> lock(allocatedBlocksMutex);
    allocatedBlocks[ptr32] = alignedSize;
    konan::consolePrintf("[INFO] Allocated new block at: %p with size: %d\n",
                        ptr, alignedSize);

    return ptr32;
}

void MmapAllocator::AddToFreeList(Ptr32_t ptr, Size32_t size) {
    if (size == 16 * KB) {
        freeBlockSmallSize.insert(ptr);
    } else if (size == 64 * KB) {
        freeBlock64KB.insert(ptr);
    } else {
        freeBlockLargeSize.insert(ptr);
    }
}

void MmapAllocator::RemoveFromFreeList(Ptr32_t ptr, Size32_t size) {
    if (size == 16 * KB) {
        freeBlockSmallSize.erase(ptr);
    } else if (size == 64 * KB) {
        freeBlock64KB.erase(ptr);
    } else {
        freeBlockLargeSize.erase(ptr);
    }
}

bool MmapAllocator::IsBlockFree(Ptr32_t ptr, Size32_t size) {
    if (size == 16 * KB) {
        return freeBlockSmallSize.find(ptr) != freeBlockSmallSize.end();
    } else if (size == 64 * KB) {
        return freeBlock64KB.find(ptr) != freeBlock64KB.end();
    } else {
        return freeBlockLargeSize.find(ptr) != freeBlockLargeSize.end();
    }
}

void MmapAllocator::SplitBlock(Ptr32_t ptr, Size32_t usedSize, Size32_t totalSize) {
    // Create a new block for the remaining space
    Ptr32_t remainingPtr = ptr + usedSize;
    Size32_t remainingSize = totalSize - usedSize;

    // Update the original block size
    allocatedBlocks[ptr] = usedSize;

    // Add the remaining part as a new free block
    allocatedBlocks[remainingPtr] = remainingSize;
    AddToFreeList(remainingPtr, remainingSize);

    konan::consolePrintf("[INFO] Split block: used %d bytes at %p, remaining %d bytes at %p\n",
                        usedSize, ToPtr(ptr), remainingSize, ToPtr(remainingPtr));
}

#endif // KONAN_OHOS || KONAN_LINUX
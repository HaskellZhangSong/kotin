#include <MmapAllocator.hpp>
#include "Porting.h"
#ifndef KONAN_WINDOWS
#include <sys/mman.h>
#endif
#include <KAssert.h>
#include <errno.h>
#include <string.h>

MmapAllocator::MmapAllocator(uintptr_t heapBase) : heapBase(heapBase), heapEnd(heapBase) {

}

Ptr32_t MmapAllocator::Allocate(Size32_t size) {
    // This lock is too heavy and need to be optimized
    // also this lock makes atomic headEnd useless
    std::lock_guard<std::mutex> lock(mutex);
    if (size == 64 * KB) {
        auto it = freeBlockSmallSize.begin();
        if (it != freeBlockSmallSize.end()) {
            Ptr32_t ptr = *it;
            freeBlockSmallSize.erase(it);
            return MmapAllocator::ToPtr32(reinterpret_cast<void*>(ptr));
        }
    } else if (size == 256 * KB) {
        if (!freeBlock256KB.empty()) {
            Ptr32_t ptr = *freeBlock256KB.begin();
            freeBlock256KB.erase(freeBlock256KB.begin());
            return MmapAllocator::ToPtr32(reinterpret_cast<void*>(ptr));
        }
    } else {
        Size32_t sizeInVec;
        // Linear search for free blocks that are larger than 256KB size
        for(auto it = freeBlockLargeSize.begin(); it != freeBlockLargeSize.end(); ++it) {
            sizeInVec = allocatedBlocks[*it];
            if (sizeInVec >= size) {
                Ptr32_t ptr = *it;
                freeBlockLargeSize.erase(it);
                return MmapAllocator::ToPtr32(reinterpret_cast<void*>(ptr));
            }
        }
    }
#ifdef KONAN_WINDOWS
    RuntimeFail("mmap is not available on mingw");
#elif KONAN_LINUX
    int flags = MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED | MAP_POPULATE;
#else
    int flags = MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED;
#endif
    // If there is no free blocks in llocate new memory
    void* ptr = mmap(reinterpret_cast<void*>(heapEnd.load()), size, PROT_READ | PROT_WRITE, flags, -1, 0);
    if (ptr == MAP_FAILED) {
        konan::consoleErrorf("Allocate at %p mmap failed: %s\n", ToPtr(heapEnd.load()), strerror(errno));
        std::abort();
        return PTR32_NULL; // Non-reachable.
    }
    konan::consolePrintf("[INFO] Allocate at: %p with size: %d\n", ptr, size);
    heapEnd.fetch_add(size);
    allocatedBlocks[reinterpret_cast<Ptr32_t>(ptr)] = size;
    return ToPtr32(ptr);
}

void MmapAllocator::Deallocate(void* ptr) {
    std::lock_guard<std::mutex> lock(mutex);
    Ptr32_t ptr32 = ToPtr32(ptr);
    auto it = allocatedBlocks.find(ptr32);
    if (it != allocatedBlocks.end()) {
        Size32_t size = it->second;
        if (size < 256 * KB) {
            freeBlockSmallSize.insert(ptr32);
        } else if (size == 256 * KB) {
            freeBlock256KB.insert(ptr32);
        } else {
            freeBlockLargeSize.insert(ptr32);
        }
    } else {
        konan::consoleErrorf("Deallocate at %p not found in allocated blocks\n", ptr);
        std::abort();
    }
}

/*
 * This will decrease the heapEnd when the last block is free but not allocated.
 * If the last block is not free, it will not change the heapEnd.
 * TODO: There should be a for loop to withdraw all unused blocks at the end.
 */
void MmapAllocator::Withdraw() {
    std::lock_guard<std::mutex> lock(mutex);
    if (allocatedBlocks.empty()) {
        RuntimeAssert(heapEnd.load() == heapBase, "allocatedBlocks is empty but heapEnd is not equal to heapBase");
        return;
    }
    for (auto it = allocatedBlocks.rbegin(); it != allocatedBlocks.rend(); ) {
        auto blockPtr = it->first;
        auto blockSize = it->second;

        if (blockSize == 64 * KB && freeBlockSmallSize.find(blockPtr) != freeBlockSmallSize.end()) {
            freeBlockSmallSize.erase(blockPtr);
            munmap(reinterpret_cast<void*>(blockPtr), blockSize);
            heapEnd.fetch_sub(blockSize);
            it = decltype(it)(allocatedBlocks.erase(std::next(it).base()));
            konan::consolePrintf("[INFO] Free small block at: %p with size: %d\n", ToPtr(blockPtr), blockSize);
        } else if (blockSize == 256 * KB && freeBlock256KB.find(blockPtr) != freeBlock256KB.end()) {
            freeBlock256KB.erase(blockPtr);
            munmap(reinterpret_cast<void*>(blockPtr), blockSize);
            heapEnd.fetch_sub(blockSize);
            it = decltype(it)(allocatedBlocks.erase(std::next(it).base()));
            konan::consolePrintf("[INFO] Free small block at: %p with size: %d\n", ToPtr(blockPtr), blockSize);
        } else if (blockSize > 256 * KB && freeBlockLargeSize.find(blockPtr) != freeBlockLargeSize.end()) {
            freeBlockLargeSize.erase(blockPtr);
            munmap(reinterpret_cast<void*>(blockPtr), blockSize);
            heapEnd.fetch_sub(blockSize);
            it = decltype(it)(allocatedBlocks.erase(std::next(it).base()));
            konan::consolePrintf("[INFO] Free small block at: %p with size: %d\n", ToPtr(blockPtr), blockSize);
        } else {
            break;
        }
    }
}

uintptr_t MmapAllocator::GetHeapBase() {
    return heapBase;
}

Ptr32_t MmapAllocator::GetHeapEnd() {
    return heapEnd.load();
}
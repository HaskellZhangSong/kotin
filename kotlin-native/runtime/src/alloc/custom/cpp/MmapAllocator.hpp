#if defined(__x86_64__) || defined(_M_X64) || defined(__aarch64__) || defined(__64BIT__) || defined(__LP64__) || defined(_WIN64)
    #define IS_64BIT_ARCH 1
#else
    #define IS_64BIT_ARCH 0
#endif


#if (KONAN_OHOS || KONAN_LINUX) && IS_64BIT_ARCH
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <sys/types.h>
#include <vector>
#include <map>
#include <set>
#include <cstdlib>
#include <mutex>
#include <atomic>

/*
 * This class is for pointer compression
 * It will use mmap to allocate memory with 32-bit address space
 *
 */

#define KB (1024)
#define MB (1024 * KB)
#define GB (1024 * MB)

using Ptr32_t = uintptr_t;
using Size32_t = uint32_t;

#define PTR32_NULL (0x0)

constexpr size_t maxHeapSize = 0x0000000100000000;
constexpr size_t heapMask = 0xFFFFFFFF00000000;
constexpr size_t heapAddressMask = 0x00000000FFFFFFFF;

class MmapAllocator {
private:
    std::mutex mutex; // mutex for thread safety
    // Need to initialize randomly to avoid attacks
    Ptr32_t heapBase = PTR32_NULL; //  start address of the heap
    std::atomic<Ptr32_t> heapEnd{PTR32_NULL}; // end address of the heap

    std::map<Ptr32_t, Size32_t> allocatedBlocks; // map of allocated blocks, size will not be changed before withdraw
    std::set<Ptr32_t> freeBlockSmallSize; // less than 256KB
    std::set<Ptr32_t> freeBlock256KB;
    std::set<Ptr32_t> freeBlockLargeSize; // more than 256KB
    Ptr32_t TryReuseBlock(Size32_t alignedSize);
    Ptr32_t AllocateNewBlock(Size32_t alignedSize);
    void AddToFreeList(Ptr32_t ptr, Size32_t size);
    void RemoveFromFreeList(Ptr32_t ptr, Size32_t size);
    bool IsBlockFree(Ptr32_t ptr, Size32_t size);
    void SplitBlock(Ptr32_t ptr, Size32_t usedSize, Size32_t totalSize);


public:
    MmapAllocator(uintptr_t heapBase);
    // ~MmapAllocator();
    Ptr32_t Allocate(Size32_t size);
    void Deallocate(void* ptr);
    void Withdraw();
    Ptr32_t GetHeapEnd();
    Ptr32_t GetHeapBase();

    inline static void* ToPtr(Ptr32_t ptr32) {
        return reinterpret_cast<void*>(ptr32 & heapAddressMask);
    }

    inline static Ptr32_t ToPtr32(void* ptr) {
        assert((reinterpret_cast<size_t>(ptr) & heapMask) == 0);
        return reinterpret_cast<Ptr32_t>(ptr) & heapAddressMask;
    }

    inline static bool IsNull(Ptr32_t ptr32) {
        return ptr32 == PTR32_NULL;
    }
};
#endif // KONAN_OHOS
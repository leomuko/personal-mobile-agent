// Synthetic host-only cases against the actual generated header helpers.
#include <cstdlib>
#include <iostream>
#include <new>
#include <tuple>
#include <type_traits>
#include <utility>
#if DPQ_WIRE
#include <dawn/wire/client/webgpu_cpp.h>
#else
#include <dawn/webgpu_cpp.h>
#endif

static int allocations = 0;
static int deletions = 0;
void* operator new(std::size_t n) {
    if (void* p = std::malloc(n)) { ++allocations; return p; }
    throw std::bad_alloc();
}
void operator delete(void* p) noexcept { if (p) { ++deletions; std::free(p); } }
void operator delete(void* p, std::size_t) noexcept { ::operator delete(p); }

struct Info {
    int mode = 0;
    int (*callback)(int, void*, void*) = nullptr;
    void* userdata1 = nullptr;
    void* userdata2 = nullptr;
};
struct Repeat {
    int (*callback)(int, void*, void*) = nullptr;
    void* userdata1 = nullptr;
    void* userdata2 = nullptr;
};
namespace wgpu::detail {
template<> struct CArgConverter<::Info, std::tuple<int>> {
    static std::tuple<int> Convert(int n) { return {n}; }
};
template<> struct CArgConverter<::Repeat, std::tuple<int>> {
    static std::tuple<int> Convert(int n) { return {n}; }
};
}
namespace detail = wgpu::detail;
static int calls = 0;
static int traits = 0;
static void must(bool v) { if (!v) std::abort(); }
struct Context { int value; };
static Context* expectedContext = nullptr;
static int raw(int x) { ++calls; return x + 20; }
static int rawNoexcept(int x) noexcept { ++calls; return x + 20; }
static int typed(int x, Context* c) {
    must(c == expectedContext); ++calls; return x + c->value;
}
static int typedNoexcept(int x, Context* c) noexcept {
    must(c == expectedContext); ++calls; return x + c->value;
}
struct Capture {
    int* destroyed;
    explicit Capture(int* d) : destroyed(d) {}
    Capture(Capture&& other) noexcept : destroyed(other.destroyed) { other.destroyed = nullptr; }
    Capture(const Capture&) = delete;
    ~Capture() { if (destroyed) ++*destroyed; }
    int operator()(int n) const noexcept { ++calls; return n + 20; }
};
struct CapturedTyped {
    int base;
    int operator()(int n, Context*) const { return n + base; }
};
struct CapturedTypedNoexcept {
    int base;
    int operator()(int n, Context*) const noexcept { return n + base; }
};

template<class F, class T = detail::Untyped>
void checkTraits(bool capturing) {
    using Traits = detail::CppFTraits<F,T>;
    static_assert(std::is_same_v<typename Traits::BaseArgsTuple, std::tuple<int>>);
    static_assert(std::is_same_v<typename Traits::ReturnT, int>);
    must(Traits::capturing == capturing); ++traits;
}
template<class F> void invoke(F f) {
    auto info = detail::CallbackInfoHelper<Info,F>::Create(std::move(f));
    must(info.userdata2 == nullptr);
    must(info.callback(2,info.userdata1,info.userdata2) == 22);
}
template<class F> void invokeTyped(F f, Context* c) {
    auto info = detail::CallbackInfoHelper<Info,F>::Create(std::move(f),c);
    must(info.userdata2 == c);
    must(info.callback(2,info.userdata1,info.userdata2) == 22);
}

int main() {
    int destroyed = 0;
#if DPQ_NEGATIVE_MODE
    auto bad = detail::CallbackInfoHelper<Repeat,Capture>::Create(Capture(&destroyed));
    (void)bad;
#elif DPQ_NEGATIVE_TYPED
    Context c{20};
    auto bad = detail::CallbackInfoHelper<Info,CapturedTyped>::Create(CapturedTyped{20},&c);
    (void)bad;
#else
    auto noncapturing = [](int n) { ++calls; return n + 20; };
    auto noncapturingNoexcept = [](int n) noexcept { ++calls; return n + 20; };
    auto typedLambda = [](int n, Context* c) { must(c == expectedContext); ++calls; return n + c->value; };
    auto typedLambdaNoexcept = [](int n, Context* c) noexcept { must(c == expectedContext); ++calls; return n + c->value; };
    auto capturing = [base=20](int n) { ++calls; return n + base; };
    checkTraits<decltype(&raw)>(false);
    checkTraits<decltype(&rawNoexcept)>(false);
    checkTraits<decltype(&typed),Context*>(false);
    checkTraits<decltype(&typedNoexcept),Context*>(false);
    checkTraits<decltype(noncapturing)>(false);
    checkTraits<decltype(noncapturingNoexcept)>(false);
    checkTraits<decltype(typedLambda),Context*>(false);
    checkTraits<decltype(typedLambdaNoexcept),Context*>(false);
    checkTraits<decltype(capturing)>(true);
    checkTraits<Capture>(true);
    checkTraits<CapturedTyped,Context*>(true);
    checkTraits<CapturedTypedNoexcept,Context*>(true);
    invoke(&raw); invoke(&rawNoexcept);
    invoke(noncapturing); invoke(noncapturingNoexcept); invoke(capturing);
    int beforeAlloc = allocations; int beforeDelete = deletions;
    invoke(Capture(&destroyed));
    int deltaAlloc = allocations - beforeAlloc; int deltaDelete = deletions - beforeDelete;
    must(deltaAlloc == 1 && deltaDelete == 1 && destroyed == 1);
    Context c{20}; expectedContext = &c;
    invokeTyped(&typed,&c); invokeTyped(&typedNoexcept,&c);
    invokeTyped(typedLambda,&c); invokeTyped(typedLambdaNoexcept,&c);
    std::byte bytes[3]{};
    std::span<std::byte> writable(bytes,3);
    std::span<const std::byte> readable(bytes,3);
    writable.data()[1] = std::byte{7};
    must(writable.size() == 3 && readable.size() == 3 && readable.data() == bytes &&
         readable.data()[1] == std::byte{7});
    must(calls == 10 && traits == 12);
    std::cout << "{\"calls\":" << calls << ",\"traits\":" << traits
              << ",\"allocations\":" << deltaAlloc << ",\"deletions\":" << deltaDelete
              << ",\"destructions\":" << destroyed << ",\"span\":true}\n";
#endif
}

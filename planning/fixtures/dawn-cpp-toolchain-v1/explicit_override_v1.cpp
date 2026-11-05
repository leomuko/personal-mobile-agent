// Supplemental compile-only API-differential witnesses. Never an equivalence pass.
#include <tuple>
#include <type_traits>
#include <utility>
#if !defined(DPQ_WIRE) || !defined(DPQ_CANDIDATE) || !defined(DPQ_WITNESS)
#error explicit isolated witness configuration required
#endif
#if (DPQ_WIRE != 0 && DPQ_WIRE != 1) || (DPQ_CANDIDATE != 0 && DPQ_CANDIDATE != 1) || DPQ_WITNESS < 1 || DPQ_WITNESS > 8
#error invalid witness configuration
#endif
#if DPQ_WIRE
#include <dawn/wire/client/webgpu_cpp.h>
#else
#include <dawn/webgpu_cpp.h>
#endif
namespace detail=wgpu::detail;
struct Context { int base=20; };
struct Info { int mode=0; int(*callback)(int,void*,void*)=nullptr; void* userdata1=nullptr; void* userdata2=nullptr; };
namespace wgpu::detail { template<> struct CArgConverter<::Info,std::tuple<int>> { static auto Convert(int n) { return std::tuple<int>{n}; } }; }
static int raw(int n,Context* c) { return n+c->base; }
using Raw=decltype(&raw);
struct Captured { int base=20; int operator()(int n,Context*) const { return n+base; } };
struct CapturedPlus {
    int base=99;
    int operator()(int n,Context*) const { return n+base; }
    Raw operator+() const { return &raw; }
};
static_assert(detail::CppFTraits<CapturedPlus>::capturing);
template<class F,class=void> struct Deduced:std::false_type {};
template<class F> struct Deduced<F,std::void_t<decltype(detail::CallbackInfoHelper<Info,F>::template Create<Context*>(std::declval<F>(),std::declval<Context*>()))>>:std::true_type {};
template<class F,class=void> struct OverrideF:std::false_type {};
template<class F> struct OverrideF<F,std::void_t<decltype(detail::CallbackInfoHelper<Info,F>::template Create<Context*,Raw>(std::declval<F>(),std::declval<Context*>()))>>:std::true_type {};
template<class F,class=void> struct OverrideAll:std::false_type {};
template<class F> struct OverrideAll<F,std::void_t<decltype(detail::CallbackInfoHelper<Info,F>::template Create<Context*,F,void>(std::declval<F>(),std::declval<Context*>()))>>:std::true_type {};
#if DPQ_WITNESS == 1
static_assert(!Deduced<Captured>::value);
#elif DPQ_WITNESS == 2
static_assert(OverrideF<Captured>::value==bool(DPQ_CANDIDATE));
#elif DPQ_WITNESS == 3
static_assert(OverrideAll<Captured>::value==bool(DPQ_CANDIDATE));
#elif DPQ_WITNESS == 4
auto witness(Context* c) { return detail::CallbackInfoHelper<Info,Raw>::template Create<Context*,Raw>(&raw,c); }
#elif DPQ_WITNESS == 5
auto witness(Context* c) { return detail::CallbackInfoHelper<Info,Raw>::template Create<Context*,Raw,void>(&raw,c); }
#elif DPQ_WITNESS == 6
auto witness(Context* c) { return detail::CallbackInfoHelper<Info,Captured>::template Create<Context*,Raw>(Captured{},c); }
#elif DPQ_WITNESS == 7
auto witness(Context* c) { return detail::CallbackInfoHelper<Info,CapturedPlus>::template Create<Context*,Raw>(CapturedPlus{},c); }
#elif DPQ_WITNESS == 8
auto witness(Context* c) { return detail::CallbackInfoHelper<Info,CapturedPlus>::template Create<Context*,CapturedPlus,void>(CapturedPlus{},c); }
#endif

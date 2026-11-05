// Versioned synthetic harness; includes complete generated headers, never projections.
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <new>
#include <tuple>
#include <type_traits>
#include <utility>
#ifndef DPQ_WIRE
#error DPQ_WIRE must be explicitly frozen
#endif
#ifndef DPQ_NEGATIVE
#define DPQ_NEGATIVE 0
#endif
#if (DPQ_WIRE != 0 && DPQ_WIRE != 1) || DPQ_NEGATIVE < 0 || DPQ_NEGATIVE > 2
#error invalid isolated harness configuration
#endif
#if DPQ_WIRE
#include <dawn/wire/client/webgpu_cpp.h>
#else
#include <dawn/webgpu_cpp.h>
#endif

static int allocations=0, deletions=0, calls=0, traits=0, cases=0, ownedDestroyed=0;
void* operator new(std::size_t n) {
    if (void* p=std::malloc(n ? n : 1)) { ++allocations; return p; }
    throw std::bad_alloc();
}
void operator delete(void* p) noexcept { if(p) { ++deletions; std::free(p); } }
void operator delete(void* p,std::size_t) noexcept { ::operator delete(p); }
static void must(bool v) { if(!v) std::abort(); }
struct Info { int mode=0; int(*callback)(int,void*,void*)=nullptr; void* userdata1=nullptr; void* userdata2=nullptr; };
struct Repeat { int(*callback)(int,void*,void*)=nullptr; void* userdata1=nullptr; void* userdata2=nullptr; };
struct Zero { int mode=0; int(*callback)(void*,void*)=nullptr; void* userdata1=nullptr; void* userdata2=nullptr; };
struct VoidInfo { int mode=0; void(*callback)(int,void*,void*)=nullptr; void* userdata1=nullptr; void* userdata2=nullptr; };
namespace wgpu::detail {
template<> struct CArgConverter<::Info,std::tuple<int>> { static auto Convert(int n) { return std::tuple<int>{n}; } };
template<> struct CArgConverter<::Repeat,std::tuple<int>> { static auto Convert(int n) { return std::tuple<int>{n}; } };
template<> struct CArgConverter<::Zero,std::tuple<>> { static auto Convert() { return std::tuple<>{}; } };
template<> struct CArgConverter<::VoidInfo,std::tuple<int>> { static auto Convert(int n) { return std::tuple<int>{n}; } };
}
namespace detail=wgpu::detail;
struct Context { int base=20; int seen=0; };
static Context* expected=nullptr;
static int raw(int n) { ++calls; return n+20; }
static int rawN(int n) noexcept { ++calls; return n+20; }
static int typed(int n,Context* c) { must(c==expected); ++c->seen; ++calls; return n+c->base; }
static int typedN(int n,Context* c) noexcept { return typed(n,c); }
static int heteroRaw(int,double,char*) { return 0; }
static int heteroRawN(int,double,char*) noexcept { return 0; }
static int heteroRawT(int,double,char*,Context*) { return 0; }
static int heteroRawTN(int,double,char*,Context*) noexcept { return 0; }
static int zero() { ++calls; return 20; }
static int zeroN() noexcept { ++calls; return 20; }
static int zeroT(Context* c) { must(c==expected); ++c->seen; ++calls; return c->base; }
static int zeroTN(Context* c) noexcept { return zeroT(c); }
static void voidRaw(int n) { must(n==2); ++calls; }
static void voidRawN(int n) noexcept { voidRaw(n); }
static void voidTyped(int n,Context* c) { must(c==expected && n==2); ++c->seen; ++calls; }
struct CapturedTyped { int base; int operator()(int n,Context*) const { return n+base; } };
struct CapturedTypedN { int base; int operator()(int n,Context*) const noexcept { return n+base; } };
struct Owned {
    bool owner=true;
    Owned()=default;
    Owned(Owned&& from) noexcept : owner(from.owner) { from.owner=false; }
    Owned(const Owned&)=delete;
    ~Owned() { if(owner) ++ownedDestroyed; }
};
struct Capture : Owned { int operator()(int n) const { ++calls; return n+20; } };
struct CaptureN : Owned { int operator()(int n) const noexcept { ++calls; return n+20; } };
struct Capture0 : Owned { int operator()() const { ++calls; return 20; } };
struct Capture0N : Owned { int operator()() const noexcept { ++calls; return 20; } };
static_assert(alignof(Capture)<=alignof(std::max_align_t));
static_assert(alignof(CaptureN)<=alignof(std::max_align_t));
static_assert(alignof(Capture0)<=alignof(std::max_align_t));
static_assert(alignof(Capture0N)<=alignof(std::max_align_t));

template<class F,class Tuple,class Ptr,class R,class T=detail::Untyped>
static void checkTraits(bool capturing) {
    using V=detail::CppFTraits<F,T>;
    static_assert(std::is_same_v<typename V::BaseArgsTuple,Tuple>);
    static_assert(std::is_same_v<typename V::PtrT,Ptr>);
    static_assert(std::is_same_v<typename V::ReturnT,R>);
    must(V::capturing==capturing); ++traits;
}
template<class F> static void invoke(F f) {
    int a=allocations,d=deletions;
    auto info=detail::CallbackInfoHelper<Info,F>::Create(std::move(f));
    must(info.callback && info.userdata2==nullptr);
    must(info.callback(2,info.userdata1,info.userdata2)==22);
    must(allocations==a && deletions==d); ++cases;
}
template<class F> static void invokeTyped(F f,Context* c,bool explicitT=false) {
    int a=allocations,d=deletions,seen=c->seen;
    auto info=explicitT ? detail::CallbackInfoHelper<Info,F>::template Create<Context*>(std::move(f),c)
                       : detail::CallbackInfoHelper<Info,F>::Create(std::move(f),c);
    must(info.callback && info.userdata2==c && info.callback(2,info.userdata1,info.userdata2)==22);
    must(c->seen==seen+1 && allocations==a && deletions==d); ++cases;
}
template<class F> static void repeat(F f) {
    int a=allocations,d=deletions;
    auto info=detail::CallbackInfoHelper<Repeat,F>::Create(std::move(f));
    must(info.callback && info.userdata2==nullptr);
    must(info.callback(2,info.userdata1,info.userdata2)==22);
    must(info.callback(3,info.userdata1,info.userdata2)==23);
    must(allocations==a && deletions==d); ++cases;
}
template<class F> static void zeroInvoke(F f) {
    int a=allocations,d=deletions;
    auto info=detail::CallbackInfoHelper<Zero,F>::Create(std::move(f));
    must(info.callback && !info.userdata2 && info.callback(info.userdata1,info.userdata2)==20);
    must(allocations==a && deletions==d); ++cases;
}
template<class F> static void zeroTyped(F f,Context* c) {
    int a=allocations,d=deletions,seen=c->seen;
    auto info=detail::CallbackInfoHelper<Zero,F>::Create(std::move(f),c);
    must(info.callback && info.userdata2==c && info.callback(info.userdata1,info.userdata2)==20);
    must(c->seen==seen+1 && allocations==a && deletions==d); ++cases;
}
template<class F,class I> static void capture() {
    int a=allocations,d=deletions,destroyed=ownedDestroyed,before=calls;
    auto info=detail::CallbackInfoHelper<I,F>::Create(F{});
    must(info.callback && info.userdata1 && !info.userdata2);
    must(allocations==a+1 && deletions==d && ownedDestroyed==destroyed);
    if constexpr(std::is_same_v<I,Zero>) must(info.callback(info.userdata1,info.userdata2)==20);
    else must(info.callback(2,info.userdata1,info.userdata2)==22);
    must(calls==before+1 && allocations==a+1 && deletions==d+1 && ownedDestroyed==destroyed+1); ++cases;
    // One-shot: never call again after the capture has been destroyed.
}
static uint8_t keyBytes[2]={3,4}, valueBytes[3]={5,6,7};
static std::size_t loadCache(std::span<const std::byte> k,std::span<std::byte> v) {
    must(k.data()==reinterpret_cast<const std::byte*>(keyBytes) && k.size()==2);
    must(v.data()==reinterpret_cast<std::byte*>(valueBytes) && v.size()==3);
    must(k[0]==std::byte{3} && v[0]==std::byte{5}); v[1]=std::byte{9}; ++calls; return 3;
}
static void storeCache(std::span<const std::byte> k,std::span<const std::byte> v) {
    must(k.data()==reinterpret_cast<const std::byte*>(keyBytes) && k.size()==2);
    must(v.data()==reinterpret_cast<const std::byte*>(valueBytes) && v.size()==3 && v[1]==std::byte{9}); ++calls;
}
int main() {
#if DPQ_NEGATIVE == 1
    auto bad=detail::CallbackInfoHelper<Repeat,Capture>::Create(Capture{}); (void)bad;
#elif DPQ_NEGATIVE == 2
    Context c;
    auto bad=detail::CallbackInfoHelper<Info,CapturedTyped>::Create(CapturedTyped{20},&c); (void)bad;
#else
    Context context; expected=&context;
    auto lam=[](int n){++calls; return n+20;};
    auto lamN=[](int n) noexcept {++calls; return n+20;};
    auto lamT=[](int n,Context* c){return typed(n,c);};
    auto lamTN=[](int n,Context* c) noexcept {return typed(n,c);};
    auto captured=[base=20](int n){++calls; return n+base;};
    auto l0=[](){return zero();}; auto l0N=[]() noexcept {return zero();};
    auto l0T=[](Context* c){return zeroT(c);}; auto l0TN=[](Context* c) noexcept {return zeroT(c);};
    auto hetero=[](int,double,char*){return 0;}; auto heteroN=[](int,double,char*) noexcept {return 0;};
    auto heteroT=[](int,double,char*,Context*){return 0;}; auto heteroTN=[](int,double,char*,Context*) noexcept {return 0;};
    using One=std::tuple<int>; using Empty=std::tuple<>; using Ordered=std::tuple<int,double,char*>;
#define CHECK(F,BASE,PTR,R,TYPE,CAPTURED) checkTraits<F,BASE,PTR,R,TYPE>(CAPTURED)
    CHECK(decltype(&raw),One,int(*)(int),int,detail::Untyped,false);
    CHECK(decltype(&rawN),One,int(*)(int) noexcept,int,detail::Untyped,false);
    CHECK(decltype(&typed),One,int(*)(int,Context*),int,Context*,false);
    CHECK(decltype(&typedN),One,int(*)(int,Context*) noexcept,int,Context*,false);
    CHECK(decltype(lam),One,int(*)(int),int,detail::Untyped,false);
    CHECK(decltype(lamN),One,int(*)(int) noexcept,int,detail::Untyped,false);
    CHECK(decltype(lamT),One,int(*)(int,Context*),int,Context*,false);
    CHECK(decltype(lamTN),One,int(*)(int,Context*) noexcept,int,Context*,false);
    CHECK(decltype(captured),One,int(*)(int),int,detail::Untyped,true);
    CHECK(CaptureN,One,int(*)(int) noexcept,int,detail::Untyped,true);
    CHECK(CapturedTyped,One,int(*)(int,Context*),int,Context*,true);
    CHECK(CapturedTypedN,One,int(*)(int,Context*) noexcept,int,Context*,true);
    CHECK(decltype(&zero),Empty,int(*)(),int,detail::Untyped,false);
    CHECK(decltype(&zeroN),Empty,int(*)() noexcept,int,detail::Untyped,false);
    CHECK(decltype(&zeroT),Empty,int(*)(Context*),int,Context*,false);
    CHECK(decltype(&zeroTN),Empty,int(*)(Context*) noexcept,int,Context*,false);
    CHECK(decltype(l0),Empty,int(*)(),int,detail::Untyped,false);
    CHECK(decltype(l0N),Empty,int(*)() noexcept,int,detail::Untyped,false);
    CHECK(decltype(l0T),Empty,int(*)(Context*),int,Context*,false);
    CHECK(decltype(l0TN),Empty,int(*)(Context*) noexcept,int,Context*,false);
    CHECK(Capture0,Empty,int(*)(),int,detail::Untyped,true);
    CHECK(Capture0N,Empty,int(*)() noexcept,int,detail::Untyped,true);
    CHECK(decltype(&heteroRaw),Ordered,int(*)(int,double,char*),int,detail::Untyped,false);
    CHECK(decltype(&heteroRawN),Ordered,int(*)(int,double,char*) noexcept,int,detail::Untyped,false);
    CHECK(decltype(&heteroRawT),Ordered,int(*)(int,double,char*,Context*),int,Context*,false);
    CHECK(decltype(&heteroRawTN),Ordered,int(*)(int,double,char*,Context*) noexcept,int,Context*,false);
    CHECK(decltype(hetero),Ordered,int(*)(int,double,char*),int,detail::Untyped,false);
    CHECK(decltype(heteroN),Ordered,int(*)(int,double,char*) noexcept,int,detail::Untyped,false);
    CHECK(decltype(heteroT),Ordered,int(*)(int,double,char*,Context*),int,Context*,false);
    CHECK(decltype(heteroTN),Ordered,int(*)(int,double,char*,Context*) noexcept,int,Context*,false);
    CHECK(decltype(&voidRaw),One,void(*)(int),void,detail::Untyped,false);
    CHECK(decltype(&voidRawN),One,void(*)(int) noexcept,void,detail::Untyped,false);
    auto vl=[](int n){voidRaw(n);}; auto vln=[](int n) noexcept {voidRaw(n);};
    CHECK(decltype(vl),One,void(*)(int),void,detail::Untyped,false);
    CHECK(decltype(vln),One,void(*)(int) noexcept,void,detail::Untyped,false);
#undef CHECK
    invoke(&raw); invoke(&rawN); invoke(lam); invoke(lamN);
    // Capturing lambda uses owned allocation; exclude it from allocation-free invoke.
    capture<Capture,Info>(); capture<CaptureN,Info>(); capture<Capture0,Zero>(); capture<Capture0N,Zero>();
    invokeTyped(&typed,&context); invokeTyped(&typedN,&context); invokeTyped(lamT,&context); invokeTyped(lamTN,&context);
    invokeTyped(&typed,&context,true); invokeTyped(lamT,&context,true);
    repeat(&raw); repeat(&rawN); repeat(lam); repeat(lamN);
    zeroInvoke(&zero); zeroInvoke(&zeroN); zeroInvoke(l0); zeroInvoke(l0N);
    zeroTyped(&zeroT,&context); zeroTyped(&zeroTN,&context); zeroTyped(l0T,&context); zeroTyped(l0TN,&context);
    { int a=allocations,d=deletions; auto v=detail::CallbackInfoHelper<VoidInfo,decltype(&voidRaw)>::Create(&voidRaw); must(v.callback && !v.userdata2); v.callback(2,v.userdata1,v.userdata2); must(a==allocations && d==deletions); ++cases; }
    { int a=allocations,d=deletions,seen=context.seen; auto v=detail::CallbackInfoHelper<VoidInfo,decltype(&voidTyped)>::Create(&voidTyped,&context); must(v.callback && v.userdata2==&context); v.callback(2,v.userdata1,v.userdata2); must(context.seen==seen+1 && a==allocations && d==deletions); ++cases; }
    { int a=allocations,d=deletions; auto v=detail::CallbackInfoHelper<WGPUDawnLoadCacheDataCallbackInfo,decltype(&loadCache)>::Create(&loadCache); must(v.callback && !v.userdata2); must(v.callback(2,keyBytes,3,valueBytes,v.userdata1,v.userdata2)==3); must(valueBytes[1]==9 && a==allocations && d==deletions); ++cases; }
    { int a=allocations,d=deletions; auto v=detail::CallbackInfoHelper<WGPUDawnStoreCacheDataCallbackInfo,decltype(&storeCache)>::Create(&storeCache); must(v.callback && !v.userdata2); v.callback(2,keyBytes,3,valueBytes,v.userdata1,v.userdata2); must(a==allocations && d==deletions); ++cases; }
    std::span<std::byte> empty; std::span<std::byte> writable(reinterpret_cast<std::byte*>(valueBytes),3);
    std::span<const std::byte> readable=writable;
    must(empty.size()==0 && writable.data()==readable.data() && readable.size()==3 && readable[1]==std::byte{9});
    must(calls==34 && cases==30 && traits==34 && ownedDestroyed==4 && context.seen==11);
    std::cout << "{\"cases\":" << cases << ",\"calls\":" << calls << ",\"traits\":" << traits
              << ",\"ownedDestructions\":" << ownedDestroyed << ",\"typedEffects\":" << context.seen
              << ",\"generatedSpanConverters\":true}\n";
#endif
}

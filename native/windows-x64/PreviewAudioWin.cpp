// Process-local AAC adapter for OpenJFX's Windows DirectShow backend.
// No registry entries, system DLLs, or PJSIP libraries are changed.
#include <windows.h>
#include <objbase.h>
#include <jni.h>
#include <string>

#include <LAVAudioSettings.h>

namespace {
// REGCLS_AGILE is available on Windows 10+, but absent from some MinGW headers.
constexpr DWORD agileMultipleUse = REGCLS_MULTIPLEUSE | 0x10;

class Factory final : public IClassFactory {
  LONG references = 1;
  IClassFactory *target;
  IUnknown *marshaler = nullptr;
public:
  HRESULT marshalingResult;
  explicit Factory(IClassFactory *value) : target(value) {
    marshalingResult = CoCreateFreeThreadedMarshaler(this, &marshaler);
  }
  ~Factory() {
    if (marshaler) marshaler->Release();
    if (target) target->Release();
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **out) override {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (id == IID_IUnknown || id == IID_IClassFactory) {
      *out = static_cast<IClassFactory *>(this);
      AddRef();
      return S_OK;
    }
    if (id == IID_IMarshal && marshaler) return marshaler->QueryInterface(id, out);
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&references); }
  ULONG STDMETHODCALLTYPE Release() override {
    LONG count = InterlockedDecrement(&references);
    if (!count) delete this;
    return count;
  }
  HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override { return target ? target->LockServer(lock) : S_OK; }
  HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown *outer, REFIID id, void **out) override {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (!target) return REGDB_E_CLASSNOTREG;
    HRESULT hr = target->CreateInstance(outer, id, out);
    if (FAILED(hr)) return hr;
    // Ignore saved codec-pack settings and never persist changes to the registry.
    GUID settingsId;
    CLSIDFromString(L"{4158A22B-6553-45D0-8069-24716F8FF171}", &settingsId);
    ILAVAudioSettings *settings = nullptr;
    hr = static_cast<IUnknown *>(*out)->QueryInterface(settingsId, reinterpret_cast<void **>(&settings));
    if (SUCCEEDED(hr)) {
      hr = settings->SetRuntimeConfig(TRUE);
      // GStreamer controls the timeline, including paused seeks. Keep the
      // timestamps supplied by that pipeline instead of adding LAV A/V sync.
      if (SUCCEEDED(hr)) hr = settings->SetAutoAVSync(FALSE);
      settings->Release();
    }
    if (FAILED(hr)) {
      static_cast<IUnknown *>(*out)->Release();
      *out = nullptr;
    }
    return hr;
  }
};

struct Runtime {
  std::wstring decoder;
  HANDLE ready = nullptr, stop = nullptr, thread = nullptr;
  HRESULT result = E_FAIL;
};
Runtime *active = nullptr;

HRESULT registerFactory(REFCLSID id, IClassFactory *target, DWORD *cookie) {
  auto *factory = new Factory(target); // Takes ownership of target's reference.
  HRESULT hr = factory->marshalingResult;
  if (SUCCEEDED(hr)) hr = CoRegisterClassObject(id, factory, CLSCTX_INPROC_SERVER, agileMultipleUse, cookie);
  factory->Release();
  return hr;
}

DWORD WINAPI audioThread(void *argument) {
  auto *runtime = static_cast<Runtime *>(argument);
  HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  HRESULT hr = initialized;
  HMODULE module = nullptr;
  DWORD alternativeCookie = 0, bypassCookie = 0;
  if (SUCCEEDED(hr)) {
    module = LoadLibraryExW(runtime->decoder.c_str(), nullptr,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!module) hr = HRESULT_FROM_WIN32(GetLastError());
  }
  if (SUCCEEDED(hr)) {
    using GetFactory = HRESULT (WINAPI *)(REFCLSID, REFIID, void **);
    auto get = reinterpret_cast<GetFactory>(GetProcAddress(module, "DllGetClassObject"));
    if (!get) hr = HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
    else {
      CLSID lav, microsoft, alternative;
      CLSIDFromString(L"{E8E73B6B-4CB3-44A4-BE99-4F7BCB96E491}", &lav);
      CLSIDFromString(L"{E1F1A0B8-BEEE-490d-BA7C-066C40B5E2B9}", &microsoft);
      CLSIDFromString(L"{19987CEE-DEE8-49DC-98EC-F21380AA9E68}", &alternative);
      IClassFactory *target = nullptr;
      hr = get(lav, IID_IClassFactory, reinterpret_cast<void **>(&target));
      // OpenJFX has a fixed decoder list. Its first slot also requires input-type
      // enumeration, which LAV does not implement. Use the existing alternative
      // slot and bypass the broken Microsoft AAC factory, in THIS PROCESS only.
      if (SUCCEEDED(hr)) hr = registerFactory(alternative, target, &alternativeCookie);
      if (SUCCEEDED(hr)) hr = registerFactory(microsoft, nullptr, &bypassCookie);
    }
  }
  runtime->result = hr;
  SetEvent(runtime->ready);
  if (SUCCEEDED(hr)) WaitForSingleObject(runtime->stop, INFINITE);
  if (bypassCookie) CoRevokeClassObject(bypassCookie);
  if (alternativeCookie) CoRevokeClassObject(alternativeCookie);
  // Keep a successful decoder module loaded until process termination: existing
  // MediaPlayers may still be releasing filters during JVM shutdown.
  if (FAILED(hr) && module) FreeLibrary(module);
  if (SUCCEEDED(initialized)) CoUninitialize();
  return 0;
}

void dispose(Runtime *runtime) {
  if (runtime->thread) CloseHandle(runtime->thread);
  if (runtime->ready) CloseHandle(runtime->ready);
  if (runtime->stop) CloseHandle(runtime->stop);
  delete runtime;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_lab_sip_client_WindowsPreviewAudio_initialize0(JNIEnv *env, jclass, jstring decoder) {
  if (active) return S_OK;
  const jchar *text = env->GetStringChars(decoder, nullptr);
  if (!text) return E_OUTOFMEMORY;
  auto *runtime = new Runtime;
  runtime->decoder.assign(reinterpret_cast<const wchar_t *>(text), env->GetStringLength(decoder));
  env->ReleaseStringChars(decoder, text);
  runtime->ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  runtime->stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (!runtime->ready || !runtime->stop) {
    HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
    dispose(runtime);
    return hr;
  }
  runtime->thread = CreateThread(nullptr, 0, audioThread, runtime, 0, nullptr);
  if (!runtime->thread) {
    HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
    dispose(runtime);
    return hr;
  }
  WaitForSingleObject(runtime->ready, INFINITE);
  HRESULT hr = runtime->result;
  if (FAILED(hr)) {
    WaitForSingleObject(runtime->thread, INFINITE);
    dispose(runtime);
  } else active = runtime;
  return hr;
}

extern "C" JNIEXPORT void JNICALL
Java_lab_sip_client_WindowsPreviewAudio_shutdown0(JNIEnv *, jclass) {
  Runtime *runtime = active;
  if (!runtime) return;
  active = nullptr;
  SetEvent(runtime->stop);
  WaitForSingleObject(runtime->thread, INFINITE);
  dispose(runtime);
}

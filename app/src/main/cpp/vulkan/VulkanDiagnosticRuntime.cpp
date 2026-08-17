#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <dlfcn.h>
#include <poll.h>
#include <unistd.h>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "CameraShaders.generated.h"

namespace {

constexpr std::uint32_t kFramesInFlight = 2;
constexpr std::uint64_t kAcquireTimeoutNs = 1'000'000'000ULL;
constexpr std::chrono::milliseconds kDiagnosticFrameInterval{100};
constexpr std::uint32_t kCameraMaxImages = 4;
constexpr std::size_t kTransformElementCount = 16;
constexpr std::size_t kCameraMetadataCount = 9;
constexpr std::size_t kTemporalRoiElementCount = 4;
constexpr std::size_t kTemporalResultElementCount = 8;
constexpr std::uint32_t kTemporalPyramidLevels = 3;
constexpr std::uint32_t kTemporalPyramidSize = 192;
constexpr std::uint32_t kTemporalFlowPointCount = 48;

struct alignas(16) TemporalPushConstants final {
    std::array<float, kTransformElementCount> uvTransform{};
    std::array<float, kTemporalRoiElementCount> roi{};
    std::array<std::int32_t, 4> parameters{};
};

static_assert(sizeof(TemporalPushConstants) == 96U);

struct MediaDispatch final {
    using NewWithUsage = media_status_t (*)(
        std::int32_t,
        std::int32_t,
        std::int32_t,
        std::uint64_t,
        std::int32_t,
        AImageReader**
    );
    using DeleteReader = void (*)(AImageReader*);
    using GetWindow = media_status_t (*)(AImageReader*, ANativeWindow**);
    using AcquireLatestAsync = media_status_t (*)(AImageReader*, AImage**, int*);
    using GetHardwareBuffer = media_status_t (*)(const AImage*, AHardwareBuffer**);
    using GetTimestamp = media_status_t (*)(const AImage*, std::int64_t*);
    using DeleteImage = void (*)(AImage*);
    using DescribeHardwareBuffer = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
    using ToJavaHardwareBuffer = jobject (*)(JNIEnv*, AHardwareBuffer*);
    using NativeWindowToSurface = jobject (*)(JNIEnv*, ANativeWindow*);

    void* mediaLibrary = nullptr;
    void* androidLibrary = nullptr;
    NewWithUsage newWithUsage = nullptr;
    DeleteReader deleteReader = nullptr;
    GetWindow getWindow = nullptr;
    AcquireLatestAsync acquireLatestAsync = nullptr;
    GetHardwareBuffer getHardwareBuffer = nullptr;
    GetTimestamp getTimestamp = nullptr;
    DeleteImage deleteImage = nullptr;
    DescribeHardwareBuffer describeHardwareBuffer = nullptr;
    ToJavaHardwareBuffer toJavaHardwareBuffer = nullptr;
    NativeWindowToSurface nativeWindowToSurface = nullptr;

    [[nodiscard]] bool load() {
        if (isReady()) {
            return true;
        }
        mediaLibrary = dlopen("libmediandk.so", RTLD_NOW | RTLD_LOCAL);
        androidLibrary = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (mediaLibrary == nullptr || androidLibrary == nullptr) {
            close();
            return false;
        }
        newWithUsage = loadSymbol<NewWithUsage>(mediaLibrary, "AImageReader_newWithUsage");
        deleteReader = loadSymbol<DeleteReader>(mediaLibrary, "AImageReader_delete");
        getWindow = loadSymbol<GetWindow>(mediaLibrary, "AImageReader_getWindow");
        acquireLatestAsync = loadSymbol<AcquireLatestAsync>(
            mediaLibrary,
            "AImageReader_acquireLatestImageAsync"
        );
        getHardwareBuffer = loadSymbol<GetHardwareBuffer>(
            mediaLibrary,
            "AImage_getHardwareBuffer"
        );
        getTimestamp = loadSymbol<GetTimestamp>(mediaLibrary, "AImage_getTimestamp");
        deleteImage = loadSymbol<DeleteImage>(mediaLibrary, "AImage_delete");
        describeHardwareBuffer = loadSymbol<DescribeHardwareBuffer>(
            androidLibrary,
            "AHardwareBuffer_describe"
        );
        toJavaHardwareBuffer = loadSymbol<ToJavaHardwareBuffer>(
            androidLibrary,
            "AHardwareBuffer_toHardwareBuffer"
        );
        nativeWindowToSurface = loadSymbol<NativeWindowToSurface>(
            androidLibrary,
            "ANativeWindow_toSurface"
        );
        if (!isReady()) {
            close();
            return false;
        }
        return true;
    }

    [[nodiscard]] bool isReady() const {
        return newWithUsage != nullptr &&
            deleteReader != nullptr &&
            getWindow != nullptr &&
            acquireLatestAsync != nullptr &&
            getHardwareBuffer != nullptr &&
            getTimestamp != nullptr &&
            deleteImage != nullptr &&
            describeHardwareBuffer != nullptr &&
            toJavaHardwareBuffer != nullptr &&
            nativeWindowToSurface != nullptr;
    }

    void close() {
        newWithUsage = nullptr;
        deleteReader = nullptr;
        getWindow = nullptr;
        acquireLatestAsync = nullptr;
        getHardwareBuffer = nullptr;
        getTimestamp = nullptr;
        deleteImage = nullptr;
        describeHardwareBuffer = nullptr;
        toJavaHardwareBuffer = nullptr;
        nativeWindowToSurface = nullptr;
        if (mediaLibrary != nullptr) {
            dlclose(mediaLibrary);
            mediaLibrary = nullptr;
        }
        if (androidLibrary != nullptr) {
            dlclose(androidLibrary);
            androidLibrary = nullptr;
        }
    }

private:
    template <typename Function>
    [[nodiscard]] static Function loadSymbol(void* library, const char* name) {
        return reinterpret_cast<Function>(dlsym(library, name));
    }
};

class VulkanDiagnosticRuntime final {
public:
    VulkanDiagnosticRuntime(
        JNIEnv* environment,
        jobject surface,
        std::uint32_t requestedWidth,
        std::uint32_t requestedHeight
    ) : requestedWidth_(requestedWidth), requestedHeight_(requestedHeight) {
        window_ = ANativeWindow_fromSurface(environment, surface);
        if (window_ == nullptr) {
            setError("native_window_unavailable");
            return;
        }
        initialize();
    }

    ~VulkanDiagnosticRuntime() {
        stop();
        closeCamera();
        destroyVulkan();
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    VulkanDiagnosticRuntime(const VulkanDiagnosticRuntime&) = delete;
    VulkanDiagnosticRuntime& operator=(const VulkanDiagnosticRuntime&) = delete;

    [[nodiscard]] bool isReady() const { return ready_.load(); }

    [[nodiscard]] bool isCameraBridgeReady() const {
        std::lock_guard lock(cameraMutex_);
        return cameraReader_ != nullptr && cameraWindow_ != nullptr && cameraPipelineReady_;
    }

    bool start() {
        std::lock_guard lock(threadMutex_);
        if (!ready_.load() || renderThread_.joinable()) {
            return ready_.load();
        }
        stopRequested_.store(false);
        renderThread_ = std::thread(&VulkanDiagnosticRuntime::renderLoop, this);
        return true;
    }

    void stop() {
        {
            std::lock_guard lock(threadMutex_);
            if (!renderThread_.joinable()) {
                return;
            }
            stopRequested_.store(true);
        }
        wakeCondition_.notify_all();
        renderThread_.join();
        if (device_ != VK_NULL_HANDLE && deviceWaitIdle_ != nullptr) {
            deviceWaitIdle_(device_);
        }
    }

    [[nodiscard]] std::string diagnostic() const {
        std::lock_guard lock(statusMutex_);
        std::ostringstream output;
        output << "status=" << status_
               << " device=" << deviceName_
               << " api=" << versionName(deviceApiVersion_)
               << " format=" << formatName(surfaceFormat_.format)
               << " extent=" << extent_.width << 'x' << extent_.height
               << " images=" << swapchainImages_.size()
               << " frames=" << presentedFrames_.load()
               << " cameraImported=" << cameraImportedFrames_.load()
               << " cameraRendered=" << cameraRenderedFrames_.load()
               << " cameraDelivered=" << cameraDeliveredFrames_.load()
               << " cameraReleased=" << cameraReleasedFrames_.load()
               << " cameraDropped=" << cameraDroppedFrames_.load()
               << " acquireFences=" << cameraAcquireFences_.load()
               << " releaseFences=" << cameraReleaseFences_.load()
               << " temporalComputed=" << temporalComputedFrames_.load()
               << " temporalAccepted=" << temporalAcceptedFrames_.load()
               << " temporalRejected=" << temporalRejectedFrames_.load();
        if (lastCameraWidth_.load() > 0U) {
            output << " camera=" << lastCameraWidth_.load() << 'x' << lastCameraHeight_.load()
                   << " ahbFormat=" << lastCameraFormat_.load()
                   << " vkFormat=" << formatName(lastCameraVkFormat_.load())
                   << " externalFormat=" << lastCameraExternalFormat_.load();
        }
        if (!errorReason_.empty()) {
            output << " reason=" << errorReason_;
        }
        if (!cameraError_.empty()) {
            output << " cameraReason=" << cameraError_;
        }
        if (!temporalError_.empty()) {
            output << " temporalReason=" << temporalError_;
        }
        return output.str();
    }

    jobject configureCamera(JNIEnv* environment, std::uint32_t width, std::uint32_t height) {
        std::lock_guard lock(cameraMutex_);
        if (!ready_.load() || width == 0U || height == 0U) {
            return nullptr;
        }
        if (cameraReader_ != nullptr) {
            if (cameraWidth_ != width || cameraHeight_ != height) {
                return nullptr;
            }
            return mediaDispatch_.nativeWindowToSurface(environment, cameraWindow_);
        }
        if (!mediaDispatch_.load()) {
            setCameraError("media_dispatch_unavailable");
            return nullptr;
        }
        AImageReader* reader = nullptr;
        const media_status_t status = mediaDispatch_.newWithUsage(
            static_cast<std::int32_t>(width),
            static_cast<std::int32_t>(height),
            AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            static_cast<std::int32_t>(kCameraMaxImages),
            &reader
        );
        if (status != AMEDIA_OK || reader == nullptr) {
            setCameraError("image_reader_create_" + std::to_string(status));
            return nullptr;
        }
        ANativeWindow* cameraWindow = nullptr;
        const media_status_t windowStatus = mediaDispatch_.getWindow(reader, &cameraWindow);
        if (windowStatus != AMEDIA_OK || cameraWindow == nullptr) {
            mediaDispatch_.deleteReader(reader);
            setCameraError("image_reader_window_" + std::to_string(windowStatus));
            return nullptr;
        }
        jobject surface = mediaDispatch_.nativeWindowToSurface(environment, cameraWindow);
        if (surface == nullptr) {
            mediaDispatch_.deleteReader(reader);
            setCameraError("camera_surface_unavailable");
            return nullptr;
        }
        cameraReader_ = reader;
        cameraWindow_ = cameraWindow;
        cameraWidth_ = width;
        cameraHeight_ = height;
        cameraPipelineReady_ = true;
        cameraError_.clear();
        return surface;
    }

    jobject acquireCameraFrame(
        JNIEnv* environment,
        jlongArray metadata,
        jfloatArray uvTransform,
        jfloatArray trackingRoi,
        jfloatArray temporalValues
    ) {
        if (metadata == nullptr || uvTransform == nullptr || trackingRoi == nullptr ||
            temporalValues == nullptr ||
            environment->GetArrayLength(metadata) < static_cast<jsize>(kCameraMetadataCount) ||
            environment->GetArrayLength(uvTransform) !=
                static_cast<jsize>(kTransformElementCount) ||
            environment->GetArrayLength(trackingRoi) !=
                static_cast<jsize>(kTemporalRoiElementCount) ||
            environment->GetArrayLength(temporalValues) <
                static_cast<jsize>(kTemporalResultElementCount)) {
            return nullptr;
        }
        std::array<float, kTransformElementCount> transform{};
        std::array<float, kTemporalRoiElementCount> roi{};
        environment->GetFloatArrayRegion(
            uvTransform,
            0,
            static_cast<jsize>(transform.size()),
            transform.data()
        );
        environment->GetFloatArrayRegion(
            trackingRoi,
            0,
            static_cast<jsize>(roi.size()),
            roi.data()
        );
        if (environment->ExceptionCheck() == JNI_TRUE) {
            return nullptr;
        }

        std::lock_guard lock(cameraMutex_);
        if (!ready_.load() || cameraReader_ == nullptr || !cameraPipelineReady_) {
            return nullptr;
        }
        if (pendingCameraFrame_.image != nullptr) {
            if (!finalizePendingCameraFrame()) {
                return nullptr;
            }
            return deliverPendingCameraFrame(environment, metadata, temporalValues);
        }
        if (deliveredCameraFrames_.size() >= kCameraMaxImages - 1U) {
            return nullptr;
        }

        AImage* image = nullptr;
        int acquireFenceFd = -1;
        const media_status_t status = mediaDispatch_.acquireLatestAsync(
            cameraReader_,
            &image,
            &acquireFenceFd
        );
        if (status == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
            return nullptr;
        }
        if (status != AMEDIA_OK || image == nullptr) {
            closeFileDescriptor(acquireFenceFd);
            setCameraError("acquire_latest_" + std::to_string(status));
            return nullptr;
        }

        AHardwareBuffer* hardwareBuffer = nullptr;
        std::int64_t timestampNs = 0;
        if (mediaDispatch_.getHardwareBuffer(image, &hardwareBuffer) != AMEDIA_OK ||
            hardwareBuffer == nullptr ||
            mediaDispatch_.getTimestamp(image, &timestampNs) != AMEDIA_OK) {
            closeFileDescriptor(acquireFenceFd);
            mediaDispatch_.deleteImage(image);
            cameraDroppedFrames_.fetch_add(1);
            setCameraError("camera_image_metadata_unavailable");
            return nullptr;
        }
        AHardwareBuffer_Desc description{};
        mediaDispatch_.describeHardwareBuffer(hardwareBuffer, &description);
        pendingCameraFrame_.image = image;
        pendingCameraFrame_.hardwareBuffer = hardwareBuffer;
        pendingCameraFrame_.timestampNs = timestampNs;
        pendingCameraFrame_.description = description;
        pendingCameraFrame_.token = nextCameraToken_++;
        pendingCameraFrame_.transform = transform;
        pendingCameraFrame_.temporalRoi = roi;
        pendingCameraFrame_.acquireFenceImported = acquireFenceFd >= 0;

        if (!importAndRenderCameraFrame(acquireFenceFd)) {
            closeFileDescriptor(acquireFenceFd);
            destroyPendingCameraFrame(/* deleteImage = */ true);
            cameraDroppedFrames_.fetch_add(1);
            return nullptr;
        }
        if (!finalizePendingCameraFrame()) {
            return nullptr;
        }
        return deliverPendingCameraFrame(environment, metadata, temporalValues);
    }

    void releaseCameraFrame(std::uint64_t token) {
        std::lock_guard lock(cameraMutex_);
        const auto iterator = deliveredCameraFrames_.find(token);
        if (iterator == deliveredCameraFrames_.end()) {
            return;
        }
        if (iterator->second != nullptr && mediaDispatch_.deleteImage != nullptr) {
            mediaDispatch_.deleteImage(iterator->second);
        }
        deliveredCameraFrames_.erase(iterator);
        cameraReleasedFrames_.fetch_add(1);
    }

    void closeCamera() {
        std::lock_guard lock(cameraMutex_);
        std::lock_guard renderLock(renderMutex_);
        if (device_ != VK_NULL_HANDLE && deviceWaitIdle_ != nullptr) {
            deviceWaitIdle_(device_);
        }
        destroyPendingCameraFrame(/* deleteImage = */ true);
        if (mediaDispatch_.deleteImage != nullptr) {
            for (const auto& entry : deliveredCameraFrames_) {
                if (entry.second != nullptr) {
                    mediaDispatch_.deleteImage(entry.second);
                }
            }
        }
        deliveredCameraFrames_.clear();
        destroyCameraPipeline();
        if (cameraReader_ != nullptr && mediaDispatch_.deleteReader != nullptr) {
            mediaDispatch_.deleteReader(cameraReader_);
        }
        cameraReader_ = nullptr;
        cameraWindow_ = nullptr;
        cameraWidth_ = 0;
        cameraHeight_ = 0;
        cameraPipelineReady_ = false;
        mediaDispatch_.close();
    }

private:
    struct PendingCameraFrame final {
        AImage* image = nullptr;
        AHardwareBuffer* hardwareBuffer = nullptr;
        AHardwareBuffer_Desc description{};
        std::int64_t timestampNs = 0;
        std::uint64_t token = 0;
        std::array<float, kTransformElementCount> transform{};
        std::array<float, kTemporalRoiElementCount> temporalRoi{};
        std::array<float, kTemporalResultElementCount> temporalResult{};
        std::int64_t temporalFromTimestampNs = 0;
        std::uint32_t temporalWriteIndex = 0;
        bool temporalComputed = false;
        bool acquireFenceImported = false;
        bool releaseFenceExported = false;
        int releaseFenceFd = -1;
        VkImage importedImage = VK_NULL_HANDLE;
        VkDeviceMemory importedMemory = VK_NULL_HANDLE;
        VkImageView importedImageView = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkDescriptorSet temporalDescriptorSet = VK_NULL_HANDLE;
        VkSemaphore acquireSemaphore = VK_NULL_HANDLE;
        VkSemaphore releaseSemaphore = VK_NULL_HANDLE;
        VkFormat vkFormat = VK_FORMAT_UNDEFINED;
        std::uint64_t externalFormat = 0;
    };

    template <typename Function>
    [[nodiscard]] Function loadGlobal(const char* name) const {
        return reinterpret_cast<Function>(getInstanceProcAddress_(VK_NULL_HANDLE, name));
    }

    template <typename Function>
    [[nodiscard]] Function loadInstance(const char* name) const {
        return reinterpret_cast<Function>(getInstanceProcAddress_(instance_, name));
    }

    template <typename Function>
    [[nodiscard]] Function loadDevice(const char* name) const {
        return reinterpret_cast<Function>(getDeviceProcAddress_(device_, name));
    }

    void initialize() {
        vulkanLibrary_ = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (vulkanLibrary_ == nullptr) {
            setError("loader_missing");
            return;
        }
        getInstanceProcAddress_ = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(vulkanLibrary_, "vkGetInstanceProcAddr")
        );
        if (getInstanceProcAddress_ == nullptr) {
            setError("get_instance_proc_addr_missing");
            return;
        }

        const auto createInstance = loadGlobal<PFN_vkCreateInstance>("vkCreateInstance");
        if (createInstance == nullptr) {
            setError("create_instance_missing");
            return;
        }

        const VkApplicationInfo applicationInfo{
            .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
            .pNext = nullptr,
            .pApplicationName = "ARMakeup",
            .applicationVersion = VK_MAKE_API_VERSION(0, 2, 1, 0),
            .pEngineName = "ARMakeupVulkan",
            .engineVersion = VK_MAKE_API_VERSION(0, 2, 1, 0),
            .apiVersion = VK_API_VERSION_1_1,
        };
        const std::vector<const char*> instanceExtensions{
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        const VkInstanceCreateInfo instanceCreateInfo{
            .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .pApplicationInfo = &applicationInfo,
            .enabledLayerCount = 0,
            .ppEnabledLayerNames = nullptr,
            .enabledExtensionCount = static_cast<std::uint32_t>(instanceExtensions.size()),
            .ppEnabledExtensionNames = instanceExtensions.data(),
        };
        VkResult result = createInstance(&instanceCreateInfo, nullptr, &instance_);
        if (result != VK_SUCCESS) {
            setVulkanError("create_instance", result);
            return;
        }
        if (!loadInstanceDispatch()) {
            return;
        }

        const VkAndroidSurfaceCreateInfoKHR surfaceCreateInfo{
            .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
            .pNext = nullptr,
            .flags = 0,
            .window = window_,
        };
        result = createAndroidSurface_(instance_, &surfaceCreateInfo, nullptr, &surface_);
        if (result != VK_SUCCESS) {
            setVulkanError("create_android_surface", result);
            return;
        }
        if (!selectPhysicalDevice()) {
            return;
        }
        if (!createLogicalDevice()) {
            return;
        }
        if (!createSwapchainResources()) {
            return;
        }
        if (!createSynchronization()) {
            return;
        }

        {
            std::lock_guard lock(statusMutex_);
            status_ = "ready";
        }
        ready_.store(true);
    }

    bool loadInstanceDispatch() {
        destroyInstance_ = loadInstance<PFN_vkDestroyInstance>("vkDestroyInstance");
        createAndroidSurface_ =
            loadInstance<PFN_vkCreateAndroidSurfaceKHR>("vkCreateAndroidSurfaceKHR");
        destroySurface_ = loadInstance<PFN_vkDestroySurfaceKHR>("vkDestroySurfaceKHR");
        enumeratePhysicalDevices_ =
            loadInstance<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
        getPhysicalDeviceProperties_ = loadInstance<PFN_vkGetPhysicalDeviceProperties>(
            "vkGetPhysicalDeviceProperties"
        );
        getPhysicalDeviceMemoryProperties_ =
            loadInstance<PFN_vkGetPhysicalDeviceMemoryProperties>(
                "vkGetPhysicalDeviceMemoryProperties"
            );
        getPhysicalDeviceQueueFamilyProperties_ =
            loadInstance<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
                "vkGetPhysicalDeviceQueueFamilyProperties"
            );
        getPhysicalDeviceSurfaceSupport_ =
            loadInstance<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
                "vkGetPhysicalDeviceSurfaceSupportKHR"
            );
        getPhysicalDeviceSurfaceCapabilities_ =
            loadInstance<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
                "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"
            );
        getPhysicalDeviceSurfaceFormats_ =
            loadInstance<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
                "vkGetPhysicalDeviceSurfaceFormatsKHR"
            );
        getPhysicalDeviceSurfacePresentModes_ =
            loadInstance<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
                "vkGetPhysicalDeviceSurfacePresentModesKHR"
            );
        enumerateDeviceExtensionProperties_ =
            loadInstance<PFN_vkEnumerateDeviceExtensionProperties>(
                "vkEnumerateDeviceExtensionProperties"
            );
        createDevice_ = loadInstance<PFN_vkCreateDevice>("vkCreateDevice");
        getDeviceProcAddress_ = loadInstance<PFN_vkGetDeviceProcAddr>("vkGetDeviceProcAddr");

        if (destroyInstance_ == nullptr ||
            createAndroidSurface_ == nullptr ||
            destroySurface_ == nullptr ||
            enumeratePhysicalDevices_ == nullptr ||
            getPhysicalDeviceProperties_ == nullptr ||
            getPhysicalDeviceMemoryProperties_ == nullptr ||
            getPhysicalDeviceQueueFamilyProperties_ == nullptr ||
            getPhysicalDeviceSurfaceSupport_ == nullptr ||
            getPhysicalDeviceSurfaceCapabilities_ == nullptr ||
            getPhysicalDeviceSurfaceFormats_ == nullptr ||
            getPhysicalDeviceSurfacePresentModes_ == nullptr ||
            enumerateDeviceExtensionProperties_ == nullptr ||
            createDevice_ == nullptr ||
            getDeviceProcAddress_ == nullptr) {
            setError("instance_dispatch_incomplete");
            return false;
        }
        return true;
    }

    bool selectPhysicalDevice() {
        std::uint32_t deviceCount = 0;
        VkResult result = enumeratePhysicalDevices_(instance_, &deviceCount, nullptr);
        if (result != VK_SUCCESS || deviceCount == 0) {
            setVulkanError("physical_device_missing", result);
            return false;
        }
        std::vector<VkPhysicalDevice> devices(deviceCount);
        result = enumeratePhysicalDevices_(instance_, &deviceCount, devices.data());
        if (result != VK_SUCCESS) {
            setVulkanError("enumerate_physical_devices", result);
            return false;
        }

        for (const VkPhysicalDevice candidate : devices) {
            if (!supportsDeviceExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ||
                !supportsDeviceExtension(
                    candidate,
                    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
                ) ||
                !supportsDeviceExtension(candidate, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) ||
                !supportsDeviceExtension(candidate, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
                continue;
            }
            std::uint32_t queueFamilyCount = 0;
            getPhysicalDeviceQueueFamilyProperties_(candidate, &queueFamilyCount, nullptr);
            std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
            getPhysicalDeviceQueueFamilyProperties_(
                candidate,
                &queueFamilyCount,
                queueFamilies.data()
            );
            for (std::uint32_t index = 0; index < queueFamilyCount; ++index) {
                VkBool32 supportsPresent = VK_FALSE;
                result = getPhysicalDeviceSurfaceSupport_(
                    candidate,
                    index,
                    surface_,
                    &supportsPresent
                );
                if (result == VK_SUCCESS &&
                    supportsPresent == VK_TRUE &&
                    (queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0U) {
                    physicalDevice_ = candidate;
                    queueFamilyIndex_ = index;
                    VkPhysicalDeviceProperties properties{};
                    getPhysicalDeviceProperties_(candidate, &properties);
                    getPhysicalDeviceMemoryProperties_(candidate, &memoryProperties_);
                    deviceName_ = properties.deviceName;
                    deviceApiVersion_ = properties.apiVersion;
                    return true;
                }
            }
        }
        setError("graphics_present_queue_missing");
        return false;
    }

    bool supportsDeviceExtension(VkPhysicalDevice device, const char* requested) const {
        std::uint32_t extensionCount = 0;
        VkResult result = enumerateDeviceExtensionProperties_(
            device,
            nullptr,
            &extensionCount,
            nullptr
        );
        if (result != VK_SUCCESS) {
            return false;
        }
        std::vector<VkExtensionProperties> extensions(extensionCount);
        result = enumerateDeviceExtensionProperties_(
            device,
            nullptr,
            &extensionCount,
            extensions.data()
        );
        if (result != VK_SUCCESS) {
            return false;
        }
        return std::any_of(
            extensions.begin(),
            extensions.end(),
            [requested](const VkExtensionProperties& extension) {
                return std::string(extension.extensionName) == requested;
            }
        );
    }

    bool createLogicalDevice() {
        constexpr float queuePriority = 1.0F;
        const VkDeviceQueueCreateInfo queueCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .queueFamilyIndex = queueFamilyIndex_,
            .queueCount = 1,
            .pQueuePriorities = &queuePriority,
        };
        const std::array<const char*, 4> deviceExtensions{
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        };
        const VkPhysicalDeviceFeatures features{};
        const VkDeviceCreateInfo deviceCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &queueCreateInfo,
            .enabledLayerCount = 0,
            .ppEnabledLayerNames = nullptr,
            .enabledExtensionCount = static_cast<std::uint32_t>(deviceExtensions.size()),
            .ppEnabledExtensionNames = deviceExtensions.data(),
            .pEnabledFeatures = &features,
        };
        const VkResult result = createDevice_(
            physicalDevice_,
            &deviceCreateInfo,
            nullptr,
            &device_
        );
        if (result != VK_SUCCESS) {
            setVulkanError("create_device", result);
            return false;
        }
        if (!loadDeviceDispatch()) {
            return false;
        }
        getDeviceQueue_(device_, queueFamilyIndex_, 0, &graphicsQueue_);
        return true;
    }

    bool loadDeviceDispatch() {
        destroyDevice_ = loadDevice<PFN_vkDestroyDevice>("vkDestroyDevice");
        getDeviceQueue_ = loadDevice<PFN_vkGetDeviceQueue>("vkGetDeviceQueue");
        createSwapchain_ = loadDevice<PFN_vkCreateSwapchainKHR>("vkCreateSwapchainKHR");
        destroySwapchain_ = loadDevice<PFN_vkDestroySwapchainKHR>("vkDestroySwapchainKHR");
        getSwapchainImages_ =
            loadDevice<PFN_vkGetSwapchainImagesKHR>("vkGetSwapchainImagesKHR");
        createImageView_ = loadDevice<PFN_vkCreateImageView>("vkCreateImageView");
        destroyImageView_ = loadDevice<PFN_vkDestroyImageView>("vkDestroyImageView");
        createRenderPass_ = loadDevice<PFN_vkCreateRenderPass>("vkCreateRenderPass");
        destroyRenderPass_ = loadDevice<PFN_vkDestroyRenderPass>("vkDestroyRenderPass");
        createFramebuffer_ = loadDevice<PFN_vkCreateFramebuffer>("vkCreateFramebuffer");
        destroyFramebuffer_ = loadDevice<PFN_vkDestroyFramebuffer>("vkDestroyFramebuffer");
        createCommandPool_ = loadDevice<PFN_vkCreateCommandPool>("vkCreateCommandPool");
        destroyCommandPool_ = loadDevice<PFN_vkDestroyCommandPool>("vkDestroyCommandPool");
        allocateCommandBuffers_ =
            loadDevice<PFN_vkAllocateCommandBuffers>("vkAllocateCommandBuffers");
        beginCommandBuffer_ = loadDevice<PFN_vkBeginCommandBuffer>("vkBeginCommandBuffer");
        endCommandBuffer_ = loadDevice<PFN_vkEndCommandBuffer>("vkEndCommandBuffer");
        resetCommandBuffer_ = loadDevice<PFN_vkResetCommandBuffer>("vkResetCommandBuffer");
        cmdBeginRenderPass_ = loadDevice<PFN_vkCmdBeginRenderPass>("vkCmdBeginRenderPass");
        cmdEndRenderPass_ = loadDevice<PFN_vkCmdEndRenderPass>("vkCmdEndRenderPass");
        cmdPipelineBarrier_ = loadDevice<PFN_vkCmdPipelineBarrier>("vkCmdPipelineBarrier");
        cmdBindPipeline_ = loadDevice<PFN_vkCmdBindPipeline>("vkCmdBindPipeline");
        cmdBindDescriptorSets_ =
            loadDevice<PFN_vkCmdBindDescriptorSets>("vkCmdBindDescriptorSets");
        cmdPushConstants_ = loadDevice<PFN_vkCmdPushConstants>("vkCmdPushConstants");
        cmdDraw_ = loadDevice<PFN_vkCmdDraw>("vkCmdDraw");
        cmdDispatch_ = loadDevice<PFN_vkCmdDispatch>("vkCmdDispatch");
        createSemaphore_ = loadDevice<PFN_vkCreateSemaphore>("vkCreateSemaphore");
        destroySemaphore_ = loadDevice<PFN_vkDestroySemaphore>("vkDestroySemaphore");
        createFence_ = loadDevice<PFN_vkCreateFence>("vkCreateFence");
        destroyFence_ = loadDevice<PFN_vkDestroyFence>("vkDestroyFence");
        waitForFences_ = loadDevice<PFN_vkWaitForFences>("vkWaitForFences");
        resetFences_ = loadDevice<PFN_vkResetFences>("vkResetFences");
        acquireNextImage_ = loadDevice<PFN_vkAcquireNextImageKHR>("vkAcquireNextImageKHR");
        queueSubmit_ = loadDevice<PFN_vkQueueSubmit>("vkQueueSubmit");
        queuePresent_ = loadDevice<PFN_vkQueuePresentKHR>("vkQueuePresentKHR");
        deviceWaitIdle_ = loadDevice<PFN_vkDeviceWaitIdle>("vkDeviceWaitIdle");
        createImage_ = loadDevice<PFN_vkCreateImage>("vkCreateImage");
        destroyImage_ = loadDevice<PFN_vkDestroyImage>("vkDestroyImage");
        getImageMemoryRequirements_ =
            loadDevice<PFN_vkGetImageMemoryRequirements>("vkGetImageMemoryRequirements");
        allocateMemory_ = loadDevice<PFN_vkAllocateMemory>("vkAllocateMemory");
        freeMemory_ = loadDevice<PFN_vkFreeMemory>("vkFreeMemory");
        bindImageMemory_ = loadDevice<PFN_vkBindImageMemory>("vkBindImageMemory");
        createBuffer_ = loadDevice<PFN_vkCreateBuffer>("vkCreateBuffer");
        destroyBuffer_ = loadDevice<PFN_vkDestroyBuffer>("vkDestroyBuffer");
        getBufferMemoryRequirements_ =
            loadDevice<PFN_vkGetBufferMemoryRequirements>("vkGetBufferMemoryRequirements");
        bindBufferMemory_ = loadDevice<PFN_vkBindBufferMemory>("vkBindBufferMemory");
        mapMemory_ = loadDevice<PFN_vkMapMemory>("vkMapMemory");
        unmapMemory_ = loadDevice<PFN_vkUnmapMemory>("vkUnmapMemory");
        getAndroidHardwareBufferProperties_ =
            loadDevice<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                "vkGetAndroidHardwareBufferPropertiesANDROID"
            );
        createSamplerYcbcrConversion_ =
            loadDevice<PFN_vkCreateSamplerYcbcrConversion>("vkCreateSamplerYcbcrConversion");
        destroySamplerYcbcrConversion_ =
            loadDevice<PFN_vkDestroySamplerYcbcrConversion>("vkDestroySamplerYcbcrConversion");
        createSampler_ = loadDevice<PFN_vkCreateSampler>("vkCreateSampler");
        destroySampler_ = loadDevice<PFN_vkDestroySampler>("vkDestroySampler");
        createShaderModule_ = loadDevice<PFN_vkCreateShaderModule>("vkCreateShaderModule");
        destroyShaderModule_ = loadDevice<PFN_vkDestroyShaderModule>("vkDestroyShaderModule");
        createDescriptorSetLayout_ =
            loadDevice<PFN_vkCreateDescriptorSetLayout>("vkCreateDescriptorSetLayout");
        destroyDescriptorSetLayout_ =
            loadDevice<PFN_vkDestroyDescriptorSetLayout>("vkDestroyDescriptorSetLayout");
        createDescriptorPool_ =
            loadDevice<PFN_vkCreateDescriptorPool>("vkCreateDescriptorPool");
        destroyDescriptorPool_ =
            loadDevice<PFN_vkDestroyDescriptorPool>("vkDestroyDescriptorPool");
        allocateDescriptorSets_ =
            loadDevice<PFN_vkAllocateDescriptorSets>("vkAllocateDescriptorSets");
        freeDescriptorSets_ = loadDevice<PFN_vkFreeDescriptorSets>("vkFreeDescriptorSets");
        updateDescriptorSets_ =
            loadDevice<PFN_vkUpdateDescriptorSets>("vkUpdateDescriptorSets");
        createPipelineLayout_ =
            loadDevice<PFN_vkCreatePipelineLayout>("vkCreatePipelineLayout");
        destroyPipelineLayout_ =
            loadDevice<PFN_vkDestroyPipelineLayout>("vkDestroyPipelineLayout");
        createGraphicsPipelines_ =
            loadDevice<PFN_vkCreateGraphicsPipelines>("vkCreateGraphicsPipelines");
        createComputePipelines_ =
            loadDevice<PFN_vkCreateComputePipelines>("vkCreateComputePipelines");
        destroyPipeline_ = loadDevice<PFN_vkDestroyPipeline>("vkDestroyPipeline");
        importSemaphoreFd_ =
            loadDevice<PFN_vkImportSemaphoreFdKHR>("vkImportSemaphoreFdKHR");
        getSemaphoreFd_ = loadDevice<PFN_vkGetSemaphoreFdKHR>("vkGetSemaphoreFdKHR");

        if (destroyDevice_ == nullptr ||
            getDeviceQueue_ == nullptr ||
            createSwapchain_ == nullptr ||
            destroySwapchain_ == nullptr ||
            getSwapchainImages_ == nullptr ||
            createImageView_ == nullptr ||
            destroyImageView_ == nullptr ||
            createRenderPass_ == nullptr ||
            destroyRenderPass_ == nullptr ||
            createFramebuffer_ == nullptr ||
            destroyFramebuffer_ == nullptr ||
            createCommandPool_ == nullptr ||
            destroyCommandPool_ == nullptr ||
            allocateCommandBuffers_ == nullptr ||
            beginCommandBuffer_ == nullptr ||
            endCommandBuffer_ == nullptr ||
            resetCommandBuffer_ == nullptr ||
            cmdBeginRenderPass_ == nullptr ||
            cmdEndRenderPass_ == nullptr ||
            cmdPipelineBarrier_ == nullptr ||
            cmdBindPipeline_ == nullptr ||
            cmdBindDescriptorSets_ == nullptr ||
            cmdPushConstants_ == nullptr ||
            cmdDraw_ == nullptr ||
            cmdDispatch_ == nullptr ||
            createSemaphore_ == nullptr ||
            destroySemaphore_ == nullptr ||
            createFence_ == nullptr ||
            destroyFence_ == nullptr ||
            waitForFences_ == nullptr ||
            resetFences_ == nullptr ||
            acquireNextImage_ == nullptr ||
            queueSubmit_ == nullptr ||
            queuePresent_ == nullptr ||
            deviceWaitIdle_ == nullptr ||
            createImage_ == nullptr ||
            destroyImage_ == nullptr ||
            getImageMemoryRequirements_ == nullptr ||
            allocateMemory_ == nullptr ||
            freeMemory_ == nullptr ||
            bindImageMemory_ == nullptr ||
            createBuffer_ == nullptr ||
            destroyBuffer_ == nullptr ||
            getBufferMemoryRequirements_ == nullptr ||
            bindBufferMemory_ == nullptr ||
            mapMemory_ == nullptr ||
            unmapMemory_ == nullptr ||
            getAndroidHardwareBufferProperties_ == nullptr ||
            createSamplerYcbcrConversion_ == nullptr ||
            destroySamplerYcbcrConversion_ == nullptr ||
            createSampler_ == nullptr ||
            destroySampler_ == nullptr ||
            createShaderModule_ == nullptr ||
            destroyShaderModule_ == nullptr ||
            createDescriptorSetLayout_ == nullptr ||
            destroyDescriptorSetLayout_ == nullptr ||
            createDescriptorPool_ == nullptr ||
            destroyDescriptorPool_ == nullptr ||
            allocateDescriptorSets_ == nullptr ||
            freeDescriptorSets_ == nullptr ||
            updateDescriptorSets_ == nullptr ||
            createPipelineLayout_ == nullptr ||
            destroyPipelineLayout_ == nullptr ||
            createGraphicsPipelines_ == nullptr ||
            createComputePipelines_ == nullptr ||
            destroyPipeline_ == nullptr ||
            importSemaphoreFd_ == nullptr ||
            getSemaphoreFd_ == nullptr) {
            setError("device_dispatch_incomplete");
            return false;
        }
        return true;
    }

    bool createSwapchainResources() {
        VkSurfaceCapabilitiesKHR capabilities{};
        VkResult result = getPhysicalDeviceSurfaceCapabilities_(
            physicalDevice_,
            surface_,
            &capabilities
        );
        if (result != VK_SUCCESS) {
            setVulkanError("surface_capabilities", result);
            return false;
        }
        if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0U) {
            setError("surface_color_attachment_unsupported");
            return false;
        }

        std::uint32_t formatCount = 0;
        result = getPhysicalDeviceSurfaceFormats_(
            physicalDevice_,
            surface_,
            &formatCount,
            nullptr
        );
        if (result != VK_SUCCESS || formatCount == 0) {
            setVulkanError("surface_formats", result);
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        result = getPhysicalDeviceSurfaceFormats_(
            physicalDevice_,
            surface_,
            &formatCount,
            formats.data()
        );
        if (result != VK_SUCCESS) {
            setVulkanError("read_surface_formats", result);
            return false;
        }
        surfaceFormat_ = chooseSurfaceFormat(formats);
        extent_ = chooseExtent(capabilities);

        std::uint32_t imageCount = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
            imageCount = capabilities.maxImageCount;
        }
        const VkCompositeAlphaFlagBitsKHR compositeAlpha = chooseCompositeAlpha(capabilities);
        const VkSwapchainCreateInfoKHR swapchainCreateInfo{
            .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
            .pNext = nullptr,
            .flags = 0,
            .surface = surface_,
            .minImageCount = imageCount,
            .imageFormat = surfaceFormat_.format,
            .imageColorSpace = surfaceFormat_.colorSpace,
            .imageExtent = extent_,
            .imageArrayLayers = 1,
            .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
            .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = nullptr,
            .preTransform = capabilities.currentTransform,
            .compositeAlpha = compositeAlpha,
            .presentMode = VK_PRESENT_MODE_FIFO_KHR,
            .clipped = VK_TRUE,
            .oldSwapchain = VK_NULL_HANDLE,
        };
        result = createSwapchain_(device_, &swapchainCreateInfo, nullptr, &swapchain_);
        if (result != VK_SUCCESS) {
            setVulkanError("create_swapchain", result);
            return false;
        }

        result = getSwapchainImages_(device_, swapchain_, &imageCount, nullptr);
        if (result != VK_SUCCESS || imageCount == 0) {
            setVulkanError("swapchain_images", result);
            return false;
        }
        swapchainImages_.resize(imageCount);
        result = getSwapchainImages_(
            device_,
            swapchain_,
            &imageCount,
            swapchainImages_.data()
        );
        if (result != VK_SUCCESS) {
            setVulkanError("read_swapchain_images", result);
            return false;
        }
        if (!createImageViews() || !createRenderPass() || !createFramebuffers()) {
            return false;
        }
        return createAndRecordCommandBuffers();
    }

    [[nodiscard]] VkSurfaceFormatKHR chooseSurfaceFormat(
        const std::vector<VkSurfaceFormatKHR>& formats
    ) const {
        if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED) {
            return VkSurfaceFormatKHR{
                .format = VK_FORMAT_R8G8B8A8_UNORM,
                .colorSpace = formats.front().colorSpace,
            };
        }
        const auto preferred = std::find_if(
            formats.begin(),
            formats.end(),
            [](const VkSurfaceFormatKHR& format) {
                return format.format == VK_FORMAT_R8G8B8A8_UNORM &&
                    format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            }
        );
        if (preferred != formats.end()) {
            return *preferred;
        }
        return formats.front();
    }

    [[nodiscard]] VkExtent2D chooseExtent(
        const VkSurfaceCapabilitiesKHR& capabilities
    ) const {
        if (capabilities.currentExtent.width != std::numeric_limits<std::uint32_t>::max()) {
            return capabilities.currentExtent;
        }
        return VkExtent2D{
            .width = std::clamp(
                requestedWidth_,
                capabilities.minImageExtent.width,
                capabilities.maxImageExtent.width
            ),
            .height = std::clamp(
                requestedHeight_,
                capabilities.minImageExtent.height,
                capabilities.maxImageExtent.height
            ),
        };
    }

    [[nodiscard]] static VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(
        const VkSurfaceCapabilitiesKHR& capabilities
    ) {
        constexpr VkCompositeAlphaFlagBitsKHR preferredModes[] = {
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
        };
        for (const VkCompositeAlphaFlagBitsKHR mode : preferredModes) {
            if ((capabilities.supportedCompositeAlpha & mode) != 0U) {
                return mode;
            }
        }
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    bool createImageViews() {
        imageViews_.resize(swapchainImages_.size(), VK_NULL_HANDLE);
        for (std::size_t index = 0; index < swapchainImages_.size(); ++index) {
            const VkImageViewCreateInfo createInfo{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = swapchainImages_[index],
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = surfaceFormat_.format,
                .components = VkComponentMapping{
                    .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                    .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                    .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                    .a = VK_COMPONENT_SWIZZLE_IDENTITY,
                },
                .subresourceRange = VkImageSubresourceRange{
                    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                    .baseMipLevel = 0,
                    .levelCount = 1,
                    .baseArrayLayer = 0,
                    .layerCount = 1,
                },
            };
            const VkResult result = createImageView_(
                device_,
                &createInfo,
                nullptr,
                &imageViews_[index]
            );
            if (result != VK_SUCCESS) {
                setVulkanError("create_image_view", result);
                return false;
            }
        }
        return true;
    }

    bool createRenderPass() {
        const VkAttachmentDescription colorAttachment{
            .flags = 0,
            .format = surfaceFormat_.format,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
            .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        };
        const VkAttachmentReference colorReference{
            .attachment = 0,
            .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        };
        const VkSubpassDescription subpass{
            .flags = 0,
            .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .inputAttachmentCount = 0,
            .pInputAttachments = nullptr,
            .colorAttachmentCount = 1,
            .pColorAttachments = &colorReference,
            .pResolveAttachments = nullptr,
            .pDepthStencilAttachment = nullptr,
            .preserveAttachmentCount = 0,
            .pPreserveAttachments = nullptr,
        };
        const VkSubpassDependency dependency{
            .srcSubpass = VK_SUBPASS_EXTERNAL,
            .dstSubpass = 0,
            .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            .dependencyFlags = 0,
        };
        const VkRenderPassCreateInfo createInfo{
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .attachmentCount = 1,
            .pAttachments = &colorAttachment,
            .subpassCount = 1,
            .pSubpasses = &subpass,
            .dependencyCount = 1,
            .pDependencies = &dependency,
        };
        const VkResult result = createRenderPass_(device_, &createInfo, nullptr, &renderPass_);
        if (result != VK_SUCCESS) {
            setVulkanError("create_render_pass", result);
            return false;
        }
        return true;
    }

    bool createFramebuffers() {
        framebuffers_.resize(imageViews_.size(), VK_NULL_HANDLE);
        for (std::size_t index = 0; index < imageViews_.size(); ++index) {
            const VkImageView attachment = imageViews_[index];
            const VkFramebufferCreateInfo createInfo{
                .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .renderPass = renderPass_,
                .attachmentCount = 1,
                .pAttachments = &attachment,
                .width = extent_.width,
                .height = extent_.height,
                .layers = 1,
            };
            const VkResult result = createFramebuffer_(
                device_,
                &createInfo,
                nullptr,
                &framebuffers_[index]
            );
            if (result != VK_SUCCESS) {
                setVulkanError("create_framebuffer", result);
                return false;
            }
        }
        return true;
    }

    bool createAndRecordCommandBuffers() {
        const VkCommandPoolCreateInfo poolCreateInfo{
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = queueFamilyIndex_,
        };
        VkResult result = createCommandPool_(device_, &poolCreateInfo, nullptr, &commandPool_);
        if (result != VK_SUCCESS) {
            setVulkanError("create_command_pool", result);
            return false;
        }
        commandBuffers_.resize(framebuffers_.size(), VK_NULL_HANDLE);
        const VkCommandBufferAllocateInfo allocateInfo{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .pNext = nullptr,
            .commandPool = commandPool_,
            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
            .commandBufferCount = static_cast<std::uint32_t>(commandBuffers_.size()),
        };
        result = allocateCommandBuffers_(device_, &allocateInfo, commandBuffers_.data());
        if (result != VK_SUCCESS) {
            setVulkanError("allocate_command_buffers", result);
            return false;
        }

        for (std::size_t index = 0; index < commandBuffers_.size(); ++index) {
            if (!recordClearCommandBuffer(static_cast<std::uint32_t>(index), false)) {
                return false;
            }
        }
        return true;
    }

    bool recordClearCommandBuffer(std::uint32_t imageIndex, bool reset) {
        VkCommandBuffer commandBuffer = commandBuffers_[imageIndex];
        if (reset) {
            const VkResult resetResult = resetCommandBuffer_(commandBuffer, 0);
            if (resetResult != VK_SUCCESS) {
                setVulkanError("reset_clear_command_buffer", resetResult);
                return false;
            }
        }
        const VkCommandBufferBeginInfo beginInfo{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
            .pInheritanceInfo = nullptr,
        };
        VkResult result = beginCommandBuffer_(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            setVulkanError("begin_clear_command_buffer", result);
            return false;
        }
        const VkClearValue clearValue{
            .color = VkClearColorValue{
                .float32 = {0.82F, 0.12F, 0.34F, 1.0F},
            },
        };
        const VkRenderPassBeginInfo renderPassInfo{
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .pNext = nullptr,
            .renderPass = renderPass_,
            .framebuffer = framebuffers_[imageIndex],
            .renderArea = VkRect2D{
                .offset = VkOffset2D{.x = 0, .y = 0},
                .extent = extent_,
            },
            .clearValueCount = 1,
            .pClearValues = &clearValue,
        };
        cmdBeginRenderPass_(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        cmdEndRenderPass_(commandBuffer);
        result = endCommandBuffer_(commandBuffer);
        if (result != VK_SUCCESS) {
            setVulkanError("end_clear_command_buffer", result);
            return false;
        }
        return true;
    }

    bool createSynchronization() {
        imageAvailableSemaphores_.resize(kFramesInFlight, VK_NULL_HANDLE);
        renderFinishedSemaphores_.resize(kFramesInFlight, VK_NULL_HANDLE);
        frameFences_.resize(kFramesInFlight, VK_NULL_HANDLE);
        imageFences_.resize(swapchainImages_.size(), VK_NULL_HANDLE);
        const VkSemaphoreCreateInfo semaphoreCreateInfo{
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
        };
        const VkFenceCreateInfo fenceCreateInfo{
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        for (std::uint32_t index = 0; index < kFramesInFlight; ++index) {
            VkResult result = createSemaphore_(
                device_,
                &semaphoreCreateInfo,
                nullptr,
                &imageAvailableSemaphores_[index]
            );
            if (result != VK_SUCCESS) {
                setVulkanError("create_image_available_semaphore", result);
                return false;
            }
            result = createSemaphore_(
                device_,
                &semaphoreCreateInfo,
                nullptr,
                &renderFinishedSemaphores_[index]
            );
            if (result != VK_SUCCESS) {
                setVulkanError("create_render_finished_semaphore", result);
                return false;
            }
            result = createFence_(device_, &fenceCreateInfo, nullptr, &frameFences_[index]);
            if (result != VK_SUCCESS) {
                setVulkanError("create_frame_fence", result);
                return false;
            }
        }
        return true;
    }

    bool createCameraPipeline(
        const VkAndroidHardwareBufferFormatPropertiesANDROID& formatProperties,
        VkFormat imageFormat,
        std::uint64_t externalFormat
    ) {
        if (cameraPipeline_ != VK_NULL_HANDLE &&
            cameraPipelineVkFormat_ == imageFormat &&
            cameraPipelineExternalFormat_ == externalFormat) {
            return true;
        }
        if (cameraPipeline_ != VK_NULL_HANDLE) {
            std::lock_guard renderLock(renderMutex_);
            deviceWaitIdle_(device_);
            destroyCameraPipeline();
        }

        const bool usesExternalFormat = imageFormat == VK_FORMAT_UNDEFINED;
        VkExternalFormatANDROID conversionExternalFormat{
            .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
            .pNext = nullptr,
            .externalFormat = externalFormat,
        };
        const VkFilter chromaFilter =
            (formatProperties.formatFeatures &
             VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT) != 0U
            ? VK_FILTER_LINEAR
            : VK_FILTER_NEAREST;
        const VkSamplerYcbcrConversionCreateInfo conversionCreateInfo{
            .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO,
            .pNext = usesExternalFormat ? &conversionExternalFormat : nullptr,
            .format = imageFormat,
            .ycbcrModel = formatProperties.suggestedYcbcrModel,
            .ycbcrRange = formatProperties.suggestedYcbcrRange,
            .components = formatProperties.samplerYcbcrConversionComponents,
            .xChromaOffset = formatProperties.suggestedXChromaOffset,
            .yChromaOffset = formatProperties.suggestedYChromaOffset,
            .chromaFilter = chromaFilter,
            .forceExplicitReconstruction = VK_FALSE,
        };
        VkResult result = createSamplerYcbcrConversion_(
            device_,
            &conversionCreateInfo,
            nullptr,
            &cameraYcbcrConversion_
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_ycbcr_conversion", result);
            return false;
        }

        const VkSamplerYcbcrConversionInfo samplerConversionInfo{
            .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
            .pNext = nullptr,
            .conversion = cameraYcbcrConversion_,
        };
        const VkSamplerCreateInfo samplerCreateInfo{
            .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .pNext = &samplerConversionInfo,
            .flags = 0,
            .magFilter = chromaFilter,
            .minFilter = chromaFilter,
            .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
            .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .mipLodBias = 0.0F,
            .anisotropyEnable = VK_FALSE,
            .maxAnisotropy = 1.0F,
            .compareEnable = VK_FALSE,
            .compareOp = VK_COMPARE_OP_ALWAYS,
            .minLod = 0.0F,
            .maxLod = 0.0F,
            .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
            .unnormalizedCoordinates = VK_FALSE,
        };
        result = createSampler_(device_, &samplerCreateInfo, nullptr, &cameraSampler_);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_camera_sampler", result);
            destroyCameraPipeline();
            return false;
        }

        const VkDescriptorSetLayoutBinding descriptorBinding{
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .pImmutableSamplers = &cameraSampler_,
        };
        const VkDescriptorSetLayoutCreateInfo descriptorLayoutCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .bindingCount = 1,
            .pBindings = &descriptorBinding,
        };
        result = createDescriptorSetLayout_(
            device_,
            &descriptorLayoutCreateInfo,
            nullptr,
            &cameraDescriptorSetLayout_
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_camera_descriptor_layout", result);
            destroyCameraPipeline();
            return false;
        }

        const VkDescriptorPoolSize descriptorPoolSize{
            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = kCameraMaxImages,
        };
        const VkDescriptorPoolCreateInfo descriptorPoolCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
            .maxSets = kCameraMaxImages,
            .poolSizeCount = 1,
            .pPoolSizes = &descriptorPoolSize,
        };
        result = createDescriptorPool_(
            device_,
            &descriptorPoolCreateInfo,
            nullptr,
            &cameraDescriptorPool_
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_camera_descriptor_pool", result);
            destroyCameraPipeline();
            return false;
        }

        const VkPushConstantRange pushConstantRange{
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .offset = 0,
            .size = static_cast<std::uint32_t>(sizeof(float) * kTransformElementCount),
        };
        const VkPipelineLayoutCreateInfo pipelineLayoutCreateInfo{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .setLayoutCount = 1,
            .pSetLayouts = &cameraDescriptorSetLayout_,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushConstantRange,
        };
        result = createPipelineLayout_(
            device_,
            &pipelineLayoutCreateInfo,
            nullptr,
            &cameraPipelineLayout_
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_camera_pipeline_layout", result);
            destroyCameraPipeline();
            return false;
        }

        VkShaderModule vertexModule = VK_NULL_HANDLE;
        VkShaderModule fragmentModule = VK_NULL_HANDLE;
        if (!createShaderModule(
                armakeup::shaders::kCameraVertex,
                sizeof(armakeup::shaders::kCameraVertex),
                &vertexModule
            ) ||
            !createShaderModule(
                armakeup::shaders::kCameraFragment,
                sizeof(armakeup::shaders::kCameraFragment),
                &fragmentModule
            )) {
            if (vertexModule != VK_NULL_HANDLE) {
                destroyShaderModule_(device_, vertexModule, nullptr);
            }
            if (fragmentModule != VK_NULL_HANDLE) {
                destroyShaderModule_(device_, fragmentModule, nullptr);
            }
            destroyCameraPipeline();
            return false;
        }

        const std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages{
            VkPipelineShaderStageCreateInfo{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .stage = VK_SHADER_STAGE_VERTEX_BIT,
                .module = vertexModule,
                .pName = "main",
                .pSpecializationInfo = nullptr,
            },
            VkPipelineShaderStageCreateInfo{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
                .module = fragmentModule,
                .pName = "main",
                .pSpecializationInfo = nullptr,
            },
        };
        const VkPipelineVertexInputStateCreateInfo vertexInputState{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .vertexBindingDescriptionCount = 0,
            .pVertexBindingDescriptions = nullptr,
            .vertexAttributeDescriptionCount = 0,
            .pVertexAttributeDescriptions = nullptr,
        };
        const VkPipelineInputAssemblyStateCreateInfo inputAssemblyState{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
            .primitiveRestartEnable = VK_FALSE,
        };
        const VkViewport viewport{
            .x = 0.0F,
            .y = 0.0F,
            .width = static_cast<float>(extent_.width),
            .height = static_cast<float>(extent_.height),
            .minDepth = 0.0F,
            .maxDepth = 1.0F,
        };
        const VkRect2D scissor{
            .offset = VkOffset2D{.x = 0, .y = 0},
            .extent = extent_,
        };
        const VkPipelineViewportStateCreateInfo viewportState{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .viewportCount = 1,
            .pViewports = &viewport,
            .scissorCount = 1,
            .pScissors = &scissor,
        };
        const VkPipelineRasterizationStateCreateInfo rasterizationState{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .depthClampEnable = VK_FALSE,
            .rasterizerDiscardEnable = VK_FALSE,
            .polygonMode = VK_POLYGON_MODE_FILL,
            .cullMode = VK_CULL_MODE_NONE,
            .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
            .depthBiasEnable = VK_FALSE,
            .depthBiasConstantFactor = 0.0F,
            .depthBiasClamp = 0.0F,
            .depthBiasSlopeFactor = 0.0F,
            .lineWidth = 1.0F,
        };
        const VkPipelineMultisampleStateCreateInfo multisampleState{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
            .sampleShadingEnable = VK_FALSE,
            .minSampleShading = 0.0F,
            .pSampleMask = nullptr,
            .alphaToCoverageEnable = VK_FALSE,
            .alphaToOneEnable = VK_FALSE,
        };
        const VkPipelineColorBlendAttachmentState colorBlendAttachment{
            .blendEnable = VK_FALSE,
            .srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
            .dstColorBlendFactor = VK_BLEND_FACTOR_ZERO,
            .colorBlendOp = VK_BLEND_OP_ADD,
            .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE,
            .dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO,
            .alphaBlendOp = VK_BLEND_OP_ADD,
            .colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                VK_COLOR_COMPONENT_G_BIT |
                VK_COLOR_COMPONENT_B_BIT |
                VK_COLOR_COMPONENT_A_BIT,
        };
        const VkPipelineColorBlendStateCreateInfo colorBlendState{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .logicOpEnable = VK_FALSE,
            .logicOp = VK_LOGIC_OP_COPY,
            .attachmentCount = 1,
            .pAttachments = &colorBlendAttachment,
            .blendConstants = {0.0F, 0.0F, 0.0F, 0.0F},
        };
        const VkGraphicsPipelineCreateInfo pipelineCreateInfo{
            .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stageCount = static_cast<std::uint32_t>(shaderStages.size()),
            .pStages = shaderStages.data(),
            .pVertexInputState = &vertexInputState,
            .pInputAssemblyState = &inputAssemblyState,
            .pTessellationState = nullptr,
            .pViewportState = &viewportState,
            .pRasterizationState = &rasterizationState,
            .pMultisampleState = &multisampleState,
            .pDepthStencilState = nullptr,
            .pColorBlendState = &colorBlendState,
            .pDynamicState = nullptr,
            .layout = cameraPipelineLayout_,
            .renderPass = renderPass_,
            .subpass = 0,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1,
        };
        result = createGraphicsPipelines_(
            device_,
            VK_NULL_HANDLE,
            1,
            &pipelineCreateInfo,
            nullptr,
            &cameraPipeline_
        );
        destroyShaderModule_(device_, fragmentModule, nullptr);
        destroyShaderModule_(device_, vertexModule, nullptr);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_camera_pipeline", result);
            destroyCameraPipeline();
            return false;
        }

        cameraPipelineVkFormat_ = imageFormat;
        cameraPipelineExternalFormat_ = externalFormat;
        temporalReady_ = createTemporalPipeline();
        return true;
    }

    bool createShaderModule(
        const std::uint8_t* byteCode,
        std::size_t byteCount,
        VkShaderModule* output
    ) {
        if (byteCode == nullptr || output == nullptr || byteCount == 0U || byteCount % 4U != 0U) {
            setCameraError("invalid_spirv");
            return false;
        }
        const VkShaderModuleCreateInfo createInfo{
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .codeSize = byteCount,
            .pCode = reinterpret_cast<const std::uint32_t*>(byteCode),
        };
        const VkResult result = createShaderModule_(device_, &createInfo, nullptr, output);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_shader_module", result);
            return false;
        }
        return true;
    }

    [[nodiscard]] std::uint32_t findMemoryType(
        std::uint32_t compatibleTypes,
        VkMemoryPropertyFlags requiredProperties
    ) const {
        for (std::uint32_t index = 0; index < memoryProperties_.memoryTypeCount; ++index) {
            if ((compatibleTypes & (1U << index)) != 0U &&
                (memoryProperties_.memoryTypes[index].propertyFlags & requiredProperties) ==
                    requiredProperties) {
                return index;
            }
        }
        return std::numeric_limits<std::uint32_t>::max();
    }

    bool createOwnedBuffer(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags memoryProperties,
        VkBuffer* buffer,
        VkDeviceMemory* memory
    ) {
        const VkBufferCreateInfo createInfo{
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .size = size,
            .usage = usage,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = nullptr,
        };
        VkResult result = createBuffer_(device_, &createInfo, nullptr, buffer);
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_buffer", result);
            return false;
        }
        VkMemoryRequirements requirements{};
        getBufferMemoryRequirements_(device_, *buffer, &requirements);
        const std::uint32_t memoryType = findMemoryType(
            requirements.memoryTypeBits,
            memoryProperties
        );
        if (memoryType == std::numeric_limits<std::uint32_t>::max()) {
            setTemporalError("buffer_memory_type_unavailable");
            return false;
        }
        const VkMemoryAllocateInfo allocateInfo{
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .pNext = nullptr,
            .allocationSize = requirements.size,
            .memoryTypeIndex = memoryType,
        };
        result = allocateMemory_(device_, &allocateInfo, nullptr, memory);
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("allocate_buffer_memory", result);
            return false;
        }
        result = bindBufferMemory_(device_, *buffer, *memory, 0);
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("bind_buffer_memory", result);
            return false;
        }
        return true;
    }

    bool createTemporalPyramid(std::uint32_t index) {
        const VkImageCreateInfo createInfo{
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .imageType = VK_IMAGE_TYPE_2D,
            .format = VK_FORMAT_R32_SFLOAT,
            .extent = VkExtent3D{
                .width = kTemporalPyramidSize,
                .height = kTemporalPyramidSize,
                .depth = 1,
            },
            .mipLevels = kTemporalPyramidLevels,
            .arrayLayers = 1,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .tiling = VK_IMAGE_TILING_OPTIMAL,
            .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = nullptr,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        VkResult result = createImage_(device_, &createInfo, nullptr, &temporalPyramids_[index]);
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_pyramid", result);
            return false;
        }
        VkMemoryRequirements requirements{};
        getImageMemoryRequirements_(device_, temporalPyramids_[index], &requirements);
        const std::uint32_t memoryType = findMemoryType(
            requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        if (memoryType == std::numeric_limits<std::uint32_t>::max()) {
            setTemporalError("pyramid_memory_type_unavailable");
            return false;
        }
        const VkMemoryAllocateInfo allocateInfo{
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .pNext = nullptr,
            .allocationSize = requirements.size,
            .memoryTypeIndex = memoryType,
        };
        result = allocateMemory_(
            device_,
            &allocateInfo,
            nullptr,
            &temporalPyramidMemory_[index]
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("allocate_pyramid_memory", result);
            return false;
        }
        result = bindImageMemory_(
            device_,
            temporalPyramids_[index],
            temporalPyramidMemory_[index],
            0
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("bind_pyramid_memory", result);
            return false;
        }
        for (std::uint32_t level = 0; level < kTemporalPyramidLevels; ++level) {
            const VkImageViewCreateInfo viewInfo{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = temporalPyramids_[index],
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = VK_FORMAT_R32_SFLOAT,
                .components = VkComponentMapping{
                    .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                    .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                    .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                    .a = VK_COMPONENT_SWIZZLE_IDENTITY,
                },
                .subresourceRange = VkImageSubresourceRange{
                    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                    .baseMipLevel = level,
                    .levelCount = 1,
                    .baseArrayLayer = 0,
                    .layerCount = 1,
                },
            };
            result = createImageView_(
                device_,
                &viewInfo,
                nullptr,
                &temporalPyramidLevelViews_[index][level]
            );
            if (result != VK_SUCCESS) {
                setTemporalVulkanError("create_pyramid_level_view", result);
                return false;
            }
        }
        const VkImageViewCreateInfo sampledViewInfo{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .image = temporalPyramids_[index],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R32_SFLOAT,
            .components = VkComponentMapping{
                .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                .a = VK_COMPONENT_SWIZZLE_IDENTITY,
            },
            .subresourceRange = VkImageSubresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = kTemporalPyramidLevels,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        result = createImageView_(
            device_,
            &sampledViewInfo,
            nullptr,
            &temporalPyramidSampledViews_[index]
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_pyramid_sampled_view", result);
            return false;
        }
        return true;
    }

    bool createComputePipeline(
        const std::uint8_t* byteCode,
        std::size_t byteCount,
        VkPipeline* pipeline
    ) {
        VkShaderModule shaderModule = VK_NULL_HANDLE;
        if (!createShaderModule(byteCode, byteCount, &shaderModule)) {
            return false;
        }
        const VkComputePipelineCreateInfo createInfo{
            .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VkPipelineShaderStageCreateInfo{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                .module = shaderModule,
                .pName = "main",
                .pSpecializationInfo = nullptr,
            },
            .layout = temporalPipelineLayout_,
            .basePipelineHandle = VK_NULL_HANDLE,
            .basePipelineIndex = -1,
        };
        const VkResult result = createComputePipelines_(
            device_,
            VK_NULL_HANDLE,
            1,
            &createInfo,
            nullptr,
            pipeline
        );
        destroyShaderModule_(device_, shaderModule, nullptr);
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_compute_pipeline", result);
            return false;
        }
        return true;
    }

    bool createTemporalPipeline() {
        temporalError_.clear();
        temporalHistoryValid_ = false;
        temporalPyramidInitialized_.fill(false);
        for (std::uint32_t index = 0; index < temporalPyramids_.size(); ++index) {
            if (!createTemporalPyramid(index)) {
                destroyTemporalPipeline();
                return false;
            }
        }
        const VkSamplerCreateInfo samplerCreateInfo{
            .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .magFilter = VK_FILTER_NEAREST,
            .minFilter = VK_FILTER_NEAREST,
            .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
            .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .mipLodBias = 0.0F,
            .anisotropyEnable = VK_FALSE,
            .maxAnisotropy = 1.0F,
            .compareEnable = VK_FALSE,
            .compareOp = VK_COMPARE_OP_ALWAYS,
            .minLod = 0.0F,
            .maxLod = static_cast<float>(kTemporalPyramidLevels - 1U),
            .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
            .unnormalizedCoordinates = VK_FALSE,
        };
        VkResult result = createSampler_(
            device_,
            &samplerCreateInfo,
            nullptr,
            &temporalSampler_
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_sampler", result);
            destroyTemporalPipeline();
            return false;
        }
        const std::array<VkDescriptorSetLayoutBinding, 8> bindings{
            VkDescriptorSetLayoutBinding{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, &cameraSampler_},
            VkDescriptorSetLayoutBinding{1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            VkDescriptorSetLayoutBinding{2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            VkDescriptorSetLayoutBinding{3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            VkDescriptorSetLayoutBinding{4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, &temporalSampler_},
            VkDescriptorSetLayoutBinding{5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, &temporalSampler_},
            VkDescriptorSetLayoutBinding{6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
            VkDescriptorSetLayoutBinding{7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        };
        const VkDescriptorSetLayoutCreateInfo layoutInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .bindingCount = static_cast<std::uint32_t>(bindings.size()),
            .pBindings = bindings.data(),
        };
        result = createDescriptorSetLayout_(
            device_,
            &layoutInfo,
            nullptr,
            &temporalDescriptorSetLayout_
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_descriptor_layout", result);
            destroyTemporalPipeline();
            return false;
        }
        const std::array<VkDescriptorPoolSize, 3> poolSizes{
            VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                kCameraMaxImages * 3U},
            VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                kCameraMaxImages * kTemporalPyramidLevels},
            VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                kCameraMaxImages * 2U},
        };
        const VkDescriptorPoolCreateInfo poolInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
            .maxSets = kCameraMaxImages,
            .poolSizeCount = static_cast<std::uint32_t>(poolSizes.size()),
            .pPoolSizes = poolSizes.data(),
        };
        result = createDescriptorPool_(device_, &poolInfo, nullptr, &temporalDescriptorPool_);
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_descriptor_pool", result);
            destroyTemporalPipeline();
            return false;
        }
        const VkPushConstantRange pushRange{
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .offset = 0,
            .size = static_cast<std::uint32_t>(sizeof(TemporalPushConstants)),
        };
        const VkPipelineLayoutCreateInfo pipelineLayoutInfo{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .setLayoutCount = 1,
            .pSetLayouts = &temporalDescriptorSetLayout_,
            .pushConstantRangeCount = 1,
            .pPushConstantRanges = &pushRange,
        };
        result = createPipelineLayout_(
            device_,
            &pipelineLayoutInfo,
            nullptr,
            &temporalPipelineLayout_
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("create_pipeline_layout", result);
            destroyTemporalPipeline();
            return false;
        }
        if (!createOwnedBuffer(
                sizeof(float) * 4U * kTemporalFlowPointCount,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                &temporalFlowBuffer_,
                &temporalFlowMemory_
            ) ||
            !createOwnedBuffer(
                sizeof(float) * kTemporalResultElementCount,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                &temporalFitBuffer_,
                &temporalFitMemory_
            )) {
            destroyTemporalPipeline();
            return false;
        }
        result = mapMemory_(
            device_,
            temporalFitMemory_,
            0,
            sizeof(float) * kTemporalResultElementCount,
            0,
            &temporalFitMapped_
        );
        if (result != VK_SUCCESS || temporalFitMapped_ == nullptr) {
            setTemporalVulkanError("map_fit_buffer", result);
            destroyTemporalPipeline();
            return false;
        }
        if (!createComputePipeline(
                armakeup::shaders::kTemporalLuma,
                sizeof(armakeup::shaders::kTemporalLuma),
                &temporalLumaPipeline_
            ) ||
            !createComputePipeline(
                armakeup::shaders::kTemporalDownsample,
                sizeof(armakeup::shaders::kTemporalDownsample),
                &temporalDownsamplePipeline_
            ) ||
            !createComputePipeline(
                armakeup::shaders::kTemporalFlow,
                sizeof(armakeup::shaders::kTemporalFlow),
                &temporalFlowPipeline_
            ) ||
            !createComputePipeline(
                armakeup::shaders::kTemporalFit,
                sizeof(armakeup::shaders::kTemporalFit),
                &temporalFitPipeline_
            )) {
            destroyTemporalPipeline();
            return false;
        }
        return true;
    }

    bool prepareTemporalDescriptorSet() {
        if (!temporalReady_) {
            return false;
        }
        pendingCameraFrame_.temporalWriteIndex = temporalHistoryValid_
            ? 1U - temporalHistoryIndex_
            : 0U;
        const std::uint32_t writeIndex = pendingCameraFrame_.temporalWriteIndex;
        const std::uint32_t previousIndex = temporalHistoryValid_
            ? temporalHistoryIndex_
            : 1U - writeIndex;
        const VkDescriptorSetAllocateInfo allocateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .pNext = nullptr,
            .descriptorPool = temporalDescriptorPool_,
            .descriptorSetCount = 1,
            .pSetLayouts = &temporalDescriptorSetLayout_,
        };
        VkResult result = allocateDescriptorSets_(
            device_,
            &allocateInfo,
            &pendingCameraFrame_.temporalDescriptorSet
        );
        if (result != VK_SUCCESS) {
            setTemporalVulkanError("allocate_descriptor", result);
            return false;
        }
        std::array<VkDescriptorImageInfo, 6> imageInfos{};
        imageInfos[0] = VkDescriptorImageInfo{
            .sampler = VK_NULL_HANDLE,
            .imageView = pendingCameraFrame_.importedImageView,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        for (std::uint32_t level = 0; level < kTemporalPyramidLevels; ++level) {
            imageInfos[level + 1U] = VkDescriptorImageInfo{
                .sampler = VK_NULL_HANDLE,
                .imageView = temporalPyramidLevelViews_[writeIndex][level],
                .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
            };
        }
        imageInfos[4] = VkDescriptorImageInfo{
            .sampler = VK_NULL_HANDLE,
            .imageView = temporalPyramidSampledViews_[previousIndex],
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        imageInfos[5] = VkDescriptorImageInfo{
            .sampler = VK_NULL_HANDLE,
            .imageView = temporalPyramidSampledViews_[writeIndex],
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        const std::array<VkDescriptorBufferInfo, 2> bufferInfos{
            VkDescriptorBufferInfo{
                .buffer = temporalFlowBuffer_,
                .offset = 0,
                .range = sizeof(float) * 4U * kTemporalFlowPointCount,
            },
            VkDescriptorBufferInfo{
                .buffer = temporalFitBuffer_,
                .offset = 0,
                .range = sizeof(float) * kTemporalResultElementCount,
            },
        };
        std::array<VkWriteDescriptorSet, 8> writes{};
        for (std::uint32_t binding = 0; binding < 6U; ++binding) {
            writes[binding] = VkWriteDescriptorSet{
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = pendingCameraFrame_.temporalDescriptorSet,
                .dstBinding = binding,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = binding == 0U || binding >= 4U
                    ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                .pImageInfo = &imageInfos[binding],
                .pBufferInfo = nullptr,
                .pTexelBufferView = nullptr,
            };
        }
        for (std::uint32_t index = 0; index < bufferInfos.size(); ++index) {
            const std::uint32_t binding = index + 6U;
            writes[binding] = VkWriteDescriptorSet{
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = pendingCameraFrame_.temporalDescriptorSet,
                .dstBinding = binding,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                .pImageInfo = nullptr,
                .pBufferInfo = &bufferInfos[index],
                .pTexelBufferView = nullptr,
            };
        }
        updateDescriptorSets_(
            device_,
            static_cast<std::uint32_t>(writes.size()),
            writes.data(),
            0,
            nullptr
        );
        return true;
    }

    bool importCameraImage() {
        VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{};
        formatProperties.sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID bufferProperties{};
        bufferProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        bufferProperties.pNext = &formatProperties;
        VkResult result = getAndroidHardwareBufferProperties_(
            device_,
            pendingCameraFrame_.hardwareBuffer,
            &bufferProperties
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("get_ahb_properties", result);
            return false;
        }
        const bool useExternalFormat = formatProperties.externalFormat != 0U;
        const VkFormat imageFormat = useExternalFormat
            ? VK_FORMAT_UNDEFINED
            : formatProperties.format;
        if (imageFormat == VK_FORMAT_UNDEFINED && !useExternalFormat) {
            setCameraError("camera_format_unavailable");
            return false;
        }
        if ((formatProperties.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0U) {
            setCameraError("camera_format_not_sampled");
            return false;
        }
        if (!createCameraPipeline(
                formatProperties,
                imageFormat,
                formatProperties.externalFormat
            )) {
            return false;
        }

        VkExternalMemoryImageCreateInfo externalMemoryInfo{
            .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
            .pNext = nullptr,
            .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
        };
        VkExternalFormatANDROID externalFormatInfo{
            .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
            .pNext = &externalMemoryInfo,
            .externalFormat = formatProperties.externalFormat,
        };
        const VkImageCreateInfo imageCreateInfo{
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            .pNext = useExternalFormat
                ? static_cast<const void*>(&externalFormatInfo)
                : static_cast<const void*>(&externalMemoryInfo),
            .flags = 0,
            .imageType = VK_IMAGE_TYPE_2D,
            .format = imageFormat,
            .extent = VkExtent3D{
                .width = pendingCameraFrame_.description.width,
                .height = pendingCameraFrame_.description.height,
                .depth = 1,
            },
            .mipLevels = 1,
            .arrayLayers = 1,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .tiling = VK_IMAGE_TILING_OPTIMAL,
            .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = nullptr,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        result = createImage_(
            device_,
            &imageCreateInfo,
            nullptr,
            &pendingCameraFrame_.importedImage
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_imported_image", result);
            return false;
        }

        VkMemoryRequirements imageMemoryRequirements{};
        getImageMemoryRequirements_(
            device_,
            pendingCameraFrame_.importedImage,
            &imageMemoryRequirements
        );
        const std::uint32_t compatibleMemoryTypes =
            imageMemoryRequirements.memoryTypeBits & bufferProperties.memoryTypeBits;
        const std::uint32_t memoryTypeIndex = firstSetBit(compatibleMemoryTypes);
        if (memoryTypeIndex == std::numeric_limits<std::uint32_t>::max()) {
            setCameraError("camera_memory_type_unavailable");
            return false;
        }
        VkMemoryDedicatedAllocateInfo dedicatedAllocateInfo{
            .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
            .pNext = nullptr,
            .image = pendingCameraFrame_.importedImage,
            .buffer = VK_NULL_HANDLE,
        };
        VkImportAndroidHardwareBufferInfoANDROID importInfo{
            .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
            .pNext = &dedicatedAllocateInfo,
            .buffer = pendingCameraFrame_.hardwareBuffer,
        };
        const VkMemoryAllocateInfo memoryAllocateInfo{
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .pNext = &importInfo,
            .allocationSize = bufferProperties.allocationSize,
            .memoryTypeIndex = memoryTypeIndex,
        };
        result = allocateMemory_(
            device_,
            &memoryAllocateInfo,
            nullptr,
            &pendingCameraFrame_.importedMemory
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("allocate_imported_memory", result);
            return false;
        }
        result = bindImageMemory_(
            device_,
            pendingCameraFrame_.importedImage,
            pendingCameraFrame_.importedMemory,
            0
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("bind_imported_memory", result);
            return false;
        }

        const VkSamplerYcbcrConversionInfo conversionInfo{
            .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
            .pNext = nullptr,
            .conversion = cameraYcbcrConversion_,
        };
        const VkImageViewCreateInfo imageViewCreateInfo{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .pNext = &conversionInfo,
            .flags = 0,
            .image = pendingCameraFrame_.importedImage,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = imageFormat,
            .components = VkComponentMapping{
                .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                .a = VK_COMPONENT_SWIZZLE_IDENTITY,
            },
            .subresourceRange = VkImageSubresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        result = createImageView_(
            device_,
            &imageViewCreateInfo,
            nullptr,
            &pendingCameraFrame_.importedImageView
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_imported_image_view", result);
            return false;
        }

        const VkDescriptorSetAllocateInfo descriptorAllocateInfo{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .pNext = nullptr,
            .descriptorPool = cameraDescriptorPool_,
            .descriptorSetCount = 1,
            .pSetLayouts = &cameraDescriptorSetLayout_,
        };
        result = allocateDescriptorSets_(
            device_,
            &descriptorAllocateInfo,
            &pendingCameraFrame_.descriptorSet
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("allocate_camera_descriptor", result);
            return false;
        }
        const VkDescriptorImageInfo descriptorImageInfo{
            .sampler = VK_NULL_HANDLE,
            .imageView = pendingCameraFrame_.importedImageView,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        const VkWriteDescriptorSet descriptorWrite{
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .pNext = nullptr,
            .dstSet = pendingCameraFrame_.descriptorSet,
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &descriptorImageInfo,
            .pBufferInfo = nullptr,
            .pTexelBufferView = nullptr,
        };
        updateDescriptorSets_(device_, 1, &descriptorWrite, 0, nullptr);

        if (temporalReady_ && !prepareTemporalDescriptorSet()) {
            pendingCameraFrame_.temporalDescriptorSet = VK_NULL_HANDLE;
        }

        pendingCameraFrame_.vkFormat = imageFormat;
        pendingCameraFrame_.externalFormat = formatProperties.externalFormat;
        lastCameraWidth_.store(pendingCameraFrame_.description.width);
        lastCameraHeight_.store(pendingCameraFrame_.description.height);
        lastCameraFormat_.store(pendingCameraFrame_.description.format);
        lastCameraVkFormat_.store(imageFormat);
        lastCameraExternalFormat_.store(formatProperties.externalFormat);
        cameraImportedFrames_.fetch_add(1);
        return true;
    }

    bool importAndRenderCameraFrame(int& acquireFenceFd) {
        if (!importCameraImage()) {
            return false;
        }
        if (acquireFenceFd >= 0) {
            const VkSemaphoreCreateInfo semaphoreCreateInfo{
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
            };
            VkResult result = createSemaphore_(
                device_,
                &semaphoreCreateInfo,
                nullptr,
                &pendingCameraFrame_.acquireSemaphore
            );
            if (result != VK_SUCCESS) {
                setCameraVulkanError("create_camera_acquire_semaphore", result);
                return false;
            }
            const VkImportSemaphoreFdInfoKHR importSemaphoreInfo{
                .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
                .pNext = nullptr,
                .semaphore = pendingCameraFrame_.acquireSemaphore,
                .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
                .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
                .fd = acquireFenceFd,
            };
            result = importSemaphoreFd_(device_, &importSemaphoreInfo);
            if (result != VK_SUCCESS) {
                setCameraVulkanError("import_camera_acquire_fence", result);
                return false;
            }
            acquireFenceFd = -1;
            cameraAcquireFences_.fetch_add(1);
        }

        const VkExportSemaphoreCreateInfo exportSemaphoreInfo{
            .sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
            .pNext = nullptr,
            .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        };
        const VkSemaphoreCreateInfo semaphoreCreateInfo{
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            .pNext = &exportSemaphoreInfo,
            .flags = 0,
        };
        VkResult result = createSemaphore_(
            device_,
            &semaphoreCreateInfo,
            nullptr,
            &pendingCameraFrame_.releaseSemaphore
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("create_camera_release_semaphore", result);
            return false;
        }
        if (!renderImportedCameraFrame()) {
            return false;
        }
        const VkSemaphoreGetFdInfoKHR getFdInfo{
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
            .pNext = nullptr,
            .semaphore = pendingCameraFrame_.releaseSemaphore,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        };
        result = getSemaphoreFd_(device_, &getFdInfo, &pendingCameraFrame_.releaseFenceFd);
        if (result != VK_SUCCESS || pendingCameraFrame_.releaseFenceFd < 0) {
            deviceWaitIdle_(device_);
            setCameraVulkanError("export_camera_release_fence", result);
            return false;
        }
        pendingCameraFrame_.releaseFenceExported = true;
        cameraReleaseFences_.fetch_add(1);
        return true;
    }

    bool renderImportedCameraFrame() {
        std::lock_guard renderLock(renderMutex_);
        const std::uint32_t frameIndex = currentFrame_ % kFramesInFlight;
        VkResult result = waitForFences_(
            device_,
            1,
            &frameFences_[frameIndex],
            VK_TRUE,
            std::numeric_limits<std::uint64_t>::max()
        );
        if (result != VK_SUCCESS) {
            setCameraVulkanError("wait_camera_frame_fence", result);
            return false;
        }
        std::uint32_t imageIndex = 0;
        result = acquireNextImage_(
            device_,
            swapchain_,
            kAcquireTimeoutNs,
            imageAvailableSemaphores_[frameIndex],
            VK_NULL_HANDLE,
            &imageIndex
        );
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            setCameraVulkanError("acquire_camera_swapchain_image", result);
            return false;
        }
        if (imageIndex >= commandBuffers_.size()) {
            setCameraError("camera_swapchain_index_out_of_range");
            return false;
        }
        if (imageFences_[imageIndex] != VK_NULL_HANDLE) {
            result = waitForFences_(
                device_,
                1,
                &imageFences_[imageIndex],
                VK_TRUE,
                std::numeric_limits<std::uint64_t>::max()
            );
            if (result != VK_SUCCESS) {
                setCameraVulkanError("wait_camera_image_fence", result);
                return false;
            }
        }
        imageFences_[imageIndex] = frameFences_[frameIndex];
        result = resetFences_(device_, 1, &frameFences_[frameIndex]);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("reset_camera_frame_fence", result);
            return false;
        }
        if (!recordCameraCommandBuffer(imageIndex)) {
            return false;
        }

        std::array<VkSemaphore, 2> waitSemaphores{
            imageAvailableSemaphores_[frameIndex],
            pendingCameraFrame_.acquireSemaphore,
        };
        std::array<VkPipelineStageFlags, 2> waitStages{
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        };
        const std::uint32_t waitCount = pendingCameraFrame_.acquireSemaphore == VK_NULL_HANDLE
            ? 1U
            : 2U;
        const std::array<VkSemaphore, 2> signalSemaphores{
            renderFinishedSemaphores_[frameIndex],
            pendingCameraFrame_.releaseSemaphore,
        };
        const VkSubmitInfo submitInfo{
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .pNext = nullptr,
            .waitSemaphoreCount = waitCount,
            .pWaitSemaphores = waitSemaphores.data(),
            .pWaitDstStageMask = waitStages.data(),
            .commandBufferCount = 1,
            .pCommandBuffers = &commandBuffers_[imageIndex],
            .signalSemaphoreCount = static_cast<std::uint32_t>(signalSemaphores.size()),
            .pSignalSemaphores = signalSemaphores.data(),
        };
        result = queueSubmit_(graphicsQueue_, 1, &submitInfo, frameFences_[frameIndex]);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("submit_camera_frame", result);
            return false;
        }
        const VkPresentInfoKHR presentInfo{
            .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .pNext = nullptr,
            .waitSemaphoreCount = 1,
            .pWaitSemaphores = &renderFinishedSemaphores_[frameIndex],
            .swapchainCount = 1,
            .pSwapchains = &swapchain_,
            .pImageIndices = &imageIndex,
            .pResults = nullptr,
        };
        result = queuePresent_(graphicsQueue_, &presentInfo);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            waitForFences_(
                device_,
                1,
                &frameFences_[frameIndex],
                VK_TRUE,
                std::numeric_limits<std::uint64_t>::max()
            );
            setCameraVulkanError("present_camera_frame", result);
            return false;
        }
        presentedFrames_.fetch_add(1);
        cameraRenderedFrames_.fetch_add(1);
        currentFrame_ = (currentFrame_ + 1) % kFramesInFlight;
        return true;
    }

    bool recordCameraCommandBuffer(std::uint32_t imageIndex) {
        VkCommandBuffer commandBuffer = commandBuffers_[imageIndex];
        VkResult result = resetCommandBuffer_(commandBuffer, 0);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("reset_camera_command_buffer", result);
            return false;
        }
        const VkCommandBufferBeginInfo beginInfo{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
            .pInheritanceInfo = nullptr,
        };
        result = beginCommandBuffer_(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("begin_camera_command_buffer", result);
            return false;
        }
        const VkImageMemoryBarrier acquireBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .dstQueueFamilyIndex = queueFamilyIndex_,
            .image = pendingCameraFrame_.importedImage,
            .subresourceRange = VkImageSubresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        cmdPipelineBarrier_(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &acquireBarrier
        );
        if (!recordTemporalCommands(commandBuffer)) {
            return false;
        }
        const VkClearValue clearValue{
            .color = VkClearColorValue{.float32 = {0.0F, 0.0F, 0.0F, 1.0F}},
        };
        const VkRenderPassBeginInfo renderPassInfo{
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .pNext = nullptr,
            .renderPass = renderPass_,
            .framebuffer = framebuffers_[imageIndex],
            .renderArea = VkRect2D{
                .offset = VkOffset2D{.x = 0, .y = 0},
                .extent = extent_,
            },
            .clearValueCount = 1,
            .pClearValues = &clearValue,
        };
        cmdBeginRenderPass_(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        cmdBindPipeline_(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, cameraPipeline_);
        cmdBindDescriptorSets_(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            cameraPipelineLayout_,
            0,
            1,
            &pendingCameraFrame_.descriptorSet,
            0,
            nullptr
        );
        cmdPushConstants_(
            commandBuffer,
            cameraPipelineLayout_,
            VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            static_cast<std::uint32_t>(sizeof(float) * kTransformElementCount),
            pendingCameraFrame_.transform.data()
        );
        cmdDraw_(commandBuffer, 3, 1, 0, 0);
        cmdEndRenderPass_(commandBuffer);
        const VkImageMemoryBarrier releaseBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = queueFamilyIndex_,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .image = pendingCameraFrame_.importedImage,
            .subresourceRange = acquireBarrier.subresourceRange,
        };
        cmdPipelineBarrier_(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &releaseBarrier
        );
        result = endCommandBuffer_(commandBuffer);
        if (result != VK_SUCCESS) {
            setCameraVulkanError("end_camera_command_buffer", result);
            return false;
        }
        return true;
    }

    [[nodiscard]] bool temporalRoiValid() const {
        const auto& roi = pendingCameraFrame_.temporalRoi;
        return std::all_of(roi.begin(), roi.end(), [](float value) {
            return std::isfinite(value);
        }) && roi[0] >= 0.0F && roi[1] >= 0.0F &&
            roi[2] <= 1.0F && roi[3] <= 1.0F &&
            roi[2] - roi[0] >= 0.01F && roi[3] - roi[1] >= 0.01F;
    }

    [[nodiscard]] bool temporalTransformMatchesHistory() const {
        if (!temporalHistoryValid_) {
            return false;
        }
        for (std::size_t index = 0; index < kTransformElementCount; ++index) {
            if (std::abs(
                    pendingCameraFrame_.transform[index] - temporalHistoryTransform_[index]
                ) > 0.0001F) {
                return false;
            }
        }
        return true;
    }

    bool recordTemporalCommands(VkCommandBuffer commandBuffer) {
        if (!temporalReady_ ||
            pendingCameraFrame_.temporalDescriptorSet == VK_NULL_HANDLE ||
            !temporalRoiValid()) {
            return true;
        }
        const std::uint32_t writeIndex = pendingCameraFrame_.temporalWriteIndex;
        const bool initialized = temporalPyramidInitialized_[writeIndex];
        const VkImageMemoryBarrier preparePyramidBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = initialized
                ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                : 0U,
            .dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .oldLayout = initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = temporalPyramids_[writeIndex],
            .subresourceRange = VkImageSubresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = kTemporalPyramidLevels,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
        };
        cmdPipelineBarrier_(
            commandBuffer,
            initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &preparePyramidBarrier
        );
        TemporalPushConstants pushConstants{};
        pushConstants.uvTransform = pendingCameraFrame_.transform;
        pushConstants.roi = pendingCameraFrame_.temporalRoi;
        cmdBindDescriptorSets_(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            temporalPipelineLayout_,
            0,
            1,
            &pendingCameraFrame_.temporalDescriptorSet,
            0,
            nullptr
        );
        cmdBindPipeline_(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            temporalLumaPipeline_
        );
        cmdPushConstants_(
            commandBuffer,
            temporalPipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            static_cast<std::uint32_t>(sizeof(pushConstants)),
            &pushConstants
        );
        cmdDispatch_(
            commandBuffer,
            (kTemporalPyramidSize + 15U) / 16U,
            (kTemporalPyramidSize + 15U) / 16U,
            1
        );
        cmdBindPipeline_(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            temporalDownsamplePipeline_
        );
        for (std::uint32_t level = 1; level < kTemporalPyramidLevels; ++level) {
            const VkImageMemoryBarrier sourceReadyBarrier{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = temporalPyramids_[writeIndex],
                .subresourceRange = VkImageSubresourceRange{
                    .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                    .baseMipLevel = level - 1U,
                    .levelCount = 1,
                    .baseArrayLayer = 0,
                    .layerCount = 1,
                },
            };
            cmdPipelineBarrier_(
                commandBuffer,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0,
                nullptr,
                0,
                nullptr,
                1,
                &sourceReadyBarrier
            );
            pushConstants.parameters[0] = static_cast<std::int32_t>(level);
            cmdPushConstants_(
                commandBuffer,
                temporalPipelineLayout_,
                VK_SHADER_STAGE_COMPUTE_BIT,
                0,
                static_cast<std::uint32_t>(sizeof(pushConstants)),
                &pushConstants
            );
            const std::uint32_t levelSize = kTemporalPyramidSize >> level;
            cmdDispatch_(
                commandBuffer,
                (levelSize + 15U) / 16U,
                (levelSize + 15U) / 16U,
                1
            );
        }
        const VkImageMemoryBarrier pyramidReadyBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = temporalPyramids_[writeIndex],
            .subresourceRange = preparePyramidBarrier.subresourceRange,
        };
        cmdPipelineBarrier_(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &pyramidReadyBarrier
        );
        if (!temporalRoiValid() || !temporalTransformMatchesHistory()) {
            return true;
        }
        pendingCameraFrame_.temporalComputed = true;
        pendingCameraFrame_.temporalFromTimestampNs = temporalHistoryTimestampNs_;
        pushConstants.parameters.fill(0);
        cmdBindPipeline_(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            temporalFlowPipeline_
        );
        cmdPushConstants_(
            commandBuffer,
            temporalPipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            static_cast<std::uint32_t>(sizeof(pushConstants)),
            &pushConstants
        );
        cmdDispatch_(commandBuffer, 1, 1, 1);
        const VkBufferMemoryBarrier flowReadyBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = temporalFlowBuffer_,
            .offset = 0,
            .size = VK_WHOLE_SIZE,
        };
        cmdPipelineBarrier_(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            1,
            &flowReadyBarrier,
            0,
            nullptr
        );
        cmdBindPipeline_(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            temporalFitPipeline_
        );
        cmdDispatch_(commandBuffer, 1, 1, 1);
        const VkBufferMemoryBarrier fitHostBarrier{
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = temporalFitBuffer_,
            .offset = 0,
            .size = VK_WHOLE_SIZE,
        };
        cmdPipelineBarrier_(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0,
            0,
            nullptr,
            1,
            &fitHostBarrier,
            0,
            nullptr
        );
        return true;
    }

    bool finalizePendingCameraFrame() {
        if (pendingCameraFrame_.image == nullptr || pendingCameraFrame_.releaseFenceFd < 0) {
            return false;
        }
        pollfd fencePoll{
            .fd = pendingCameraFrame_.releaseFenceFd,
            .events = POLLIN,
            .revents = 0,
        };
        const int pollResult = poll(&fencePoll, 1, 0);
        if (pollResult == 0) {
            return false;
        }
        if (pollResult < 0) {
            setCameraError("release_fence_poll_failed");
            return false;
        }
        closeFileDescriptor(pendingCameraFrame_.releaseFenceFd);
        pendingCameraFrame_.releaseFenceFd = -1;
        if (pendingCameraFrame_.temporalDescriptorSet != VK_NULL_HANDLE && temporalReady_ &&
            temporalRoiValid()) {
            if (pendingCameraFrame_.temporalComputed && temporalFitMapped_ != nullptr) {
                const auto* result = static_cast<const float*>(temporalFitMapped_);
                std::copy_n(
                    result,
                    kTemporalResultElementCount,
                    pendingCameraFrame_.temporalResult.begin()
                );
                temporalComputedFrames_.fetch_add(1);
                if (pendingCameraFrame_.temporalResult[7] >= 0.5F) {
                    temporalAcceptedFrames_.fetch_add(1);
                } else {
                    temporalRejectedFrames_.fetch_add(1);
                }
            }
            temporalHistoryIndex_ = pendingCameraFrame_.temporalWriteIndex;
            temporalPyramidInitialized_[temporalHistoryIndex_] = true;
            temporalHistoryTimestampNs_ = pendingCameraFrame_.timestampNs;
            temporalHistoryTransform_ = pendingCameraFrame_.transform;
            temporalHistoryValid_ = true;
        }
        destroyPendingCameraGpuResources();
        return true;
    }

    jobject deliverPendingCameraFrame(
        JNIEnv* environment,
        jlongArray metadata,
        jfloatArray temporalValues
    ) {
        if (pendingCameraFrame_.image == nullptr ||
            pendingCameraFrame_.hardwareBuffer == nullptr) {
            return nullptr;
        }
        jobject hardwareBuffer = mediaDispatch_.toJavaHardwareBuffer(
            environment,
            pendingCameraFrame_.hardwareBuffer
        );
        if (hardwareBuffer == nullptr) {
            destroyPendingCameraFrame(/* deleteImage = */ true);
            cameraDroppedFrames_.fetch_add(1);
            setCameraError("java_hardware_buffer_unavailable");
            return nullptr;
        }
        const std::array<jlong, kCameraMetadataCount> values{
            static_cast<jlong>(pendingCameraFrame_.token),
            static_cast<jlong>(pendingCameraFrame_.timestampNs),
            static_cast<jlong>(pendingCameraFrame_.description.width),
            static_cast<jlong>(pendingCameraFrame_.description.height),
            static_cast<jlong>(pendingCameraFrame_.description.format),
            static_cast<jlong>(pendingCameraFrame_.description.usage),
            pendingCameraFrame_.acquireFenceImported ? 1L : 0L,
            pendingCameraFrame_.releaseFenceExported ? 1L : 0L,
            static_cast<jlong>(pendingCameraFrame_.temporalFromTimestampNs),
        };
        environment->SetLongArrayRegion(
            metadata,
            0,
            static_cast<jsize>(values.size()),
            values.data()
        );
        if (environment->ExceptionCheck() == JNI_TRUE) {
            environment->DeleteLocalRef(hardwareBuffer);
            destroyPendingCameraFrame(/* deleteImage = */ true);
            cameraDroppedFrames_.fetch_add(1);
            return nullptr;
        }
        environment->SetFloatArrayRegion(
            temporalValues,
            0,
            static_cast<jsize>(pendingCameraFrame_.temporalResult.size()),
            pendingCameraFrame_.temporalResult.data()
        );
        if (environment->ExceptionCheck() == JNI_TRUE) {
            environment->DeleteLocalRef(hardwareBuffer);
            destroyPendingCameraFrame(/* deleteImage = */ true);
            cameraDroppedFrames_.fetch_add(1);
            return nullptr;
        }
        deliveredCameraFrames_.emplace(
            pendingCameraFrame_.token,
            pendingCameraFrame_.image
        );
        pendingCameraFrame_.image = nullptr;
        pendingCameraFrame_ = PendingCameraFrame{};
        cameraDeliveredFrames_.fetch_add(1);
        return hardwareBuffer;
    }

    void destroyPendingCameraGpuResources() {
        if (device_ == VK_NULL_HANDLE) {
            return;
        }
        if (pendingCameraFrame_.descriptorSet != VK_NULL_HANDLE &&
            cameraDescriptorPool_ != VK_NULL_HANDLE) {
            freeDescriptorSets_(
                device_,
                cameraDescriptorPool_,
                1,
                &pendingCameraFrame_.descriptorSet
            );
            pendingCameraFrame_.descriptorSet = VK_NULL_HANDLE;
        }
        if (pendingCameraFrame_.temporalDescriptorSet != VK_NULL_HANDLE &&
            temporalDescriptorPool_ != VK_NULL_HANDLE) {
            freeDescriptorSets_(
                device_,
                temporalDescriptorPool_,
                1,
                &pendingCameraFrame_.temporalDescriptorSet
            );
            pendingCameraFrame_.temporalDescriptorSet = VK_NULL_HANDLE;
        }
        if (pendingCameraFrame_.importedImageView != VK_NULL_HANDLE) {
            destroyImageView_(device_, pendingCameraFrame_.importedImageView, nullptr);
            pendingCameraFrame_.importedImageView = VK_NULL_HANDLE;
        }
        if (pendingCameraFrame_.importedImage != VK_NULL_HANDLE) {
            destroyImage_(device_, pendingCameraFrame_.importedImage, nullptr);
            pendingCameraFrame_.importedImage = VK_NULL_HANDLE;
        }
        if (pendingCameraFrame_.importedMemory != VK_NULL_HANDLE) {
            freeMemory_(device_, pendingCameraFrame_.importedMemory, nullptr);
            pendingCameraFrame_.importedMemory = VK_NULL_HANDLE;
        }
        if (pendingCameraFrame_.releaseSemaphore != VK_NULL_HANDLE) {
            destroySemaphore_(device_, pendingCameraFrame_.releaseSemaphore, nullptr);
            pendingCameraFrame_.releaseSemaphore = VK_NULL_HANDLE;
        }
        if (pendingCameraFrame_.acquireSemaphore != VK_NULL_HANDLE) {
            destroySemaphore_(device_, pendingCameraFrame_.acquireSemaphore, nullptr);
            pendingCameraFrame_.acquireSemaphore = VK_NULL_HANDLE;
        }
    }

    void destroyPendingCameraFrame(bool deleteImage) {
        closeFileDescriptor(pendingCameraFrame_.releaseFenceFd);
        pendingCameraFrame_.releaseFenceFd = -1;
        destroyPendingCameraGpuResources();
        if (deleteImage && pendingCameraFrame_.image != nullptr &&
            mediaDispatch_.deleteImage != nullptr) {
            mediaDispatch_.deleteImage(pendingCameraFrame_.image);
        }
        pendingCameraFrame_ = PendingCameraFrame{};
    }

    void destroyTemporalPipeline() {
        temporalReady_ = false;
        temporalHistoryValid_ = false;
        temporalHistoryTimestampNs_ = 0;
        temporalPyramidInitialized_.fill(false);
        if (device_ == VK_NULL_HANDLE) {
            return;
        }
        const std::array<VkPipeline*, 4> pipelines{
            &temporalLumaPipeline_,
            &temporalDownsamplePipeline_,
            &temporalFlowPipeline_,
            &temporalFitPipeline_,
        };
        for (VkPipeline* pipeline : pipelines) {
            if (*pipeline != VK_NULL_HANDLE) {
                destroyPipeline_(device_, *pipeline, nullptr);
                *pipeline = VK_NULL_HANDLE;
            }
        }
        if (temporalPipelineLayout_ != VK_NULL_HANDLE) {
            destroyPipelineLayout_(device_, temporalPipelineLayout_, nullptr);
            temporalPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (temporalDescriptorPool_ != VK_NULL_HANDLE) {
            destroyDescriptorPool_(device_, temporalDescriptorPool_, nullptr);
            temporalDescriptorPool_ = VK_NULL_HANDLE;
        }
        if (temporalDescriptorSetLayout_ != VK_NULL_HANDLE) {
            destroyDescriptorSetLayout_(device_, temporalDescriptorSetLayout_, nullptr);
            temporalDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (temporalFitMapped_ != nullptr && temporalFitMemory_ != VK_NULL_HANDLE) {
            unmapMemory_(device_, temporalFitMemory_);
            temporalFitMapped_ = nullptr;
        }
        if (temporalFlowBuffer_ != VK_NULL_HANDLE) {
            destroyBuffer_(device_, temporalFlowBuffer_, nullptr);
            temporalFlowBuffer_ = VK_NULL_HANDLE;
        }
        if (temporalFlowMemory_ != VK_NULL_HANDLE) {
            freeMemory_(device_, temporalFlowMemory_, nullptr);
            temporalFlowMemory_ = VK_NULL_HANDLE;
        }
        if (temporalFitBuffer_ != VK_NULL_HANDLE) {
            destroyBuffer_(device_, temporalFitBuffer_, nullptr);
            temporalFitBuffer_ = VK_NULL_HANDLE;
        }
        if (temporalFitMemory_ != VK_NULL_HANDLE) {
            freeMemory_(device_, temporalFitMemory_, nullptr);
            temporalFitMemory_ = VK_NULL_HANDLE;
        }
        if (temporalSampler_ != VK_NULL_HANDLE) {
            destroySampler_(device_, temporalSampler_, nullptr);
            temporalSampler_ = VK_NULL_HANDLE;
        }
        for (std::uint32_t index = 0; index < temporalPyramids_.size(); ++index) {
            if (temporalPyramidSampledViews_[index] != VK_NULL_HANDLE) {
                destroyImageView_(device_, temporalPyramidSampledViews_[index], nullptr);
                temporalPyramidSampledViews_[index] = VK_NULL_HANDLE;
            }
            for (VkImageView& view : temporalPyramidLevelViews_[index]) {
                if (view != VK_NULL_HANDLE) {
                    destroyImageView_(device_, view, nullptr);
                    view = VK_NULL_HANDLE;
                }
            }
            if (temporalPyramids_[index] != VK_NULL_HANDLE) {
                destroyImage_(device_, temporalPyramids_[index], nullptr);
                temporalPyramids_[index] = VK_NULL_HANDLE;
            }
            if (temporalPyramidMemory_[index] != VK_NULL_HANDLE) {
                freeMemory_(device_, temporalPyramidMemory_[index], nullptr);
                temporalPyramidMemory_[index] = VK_NULL_HANDLE;
            }
        }
    }

    void destroyCameraPipeline() {
        if (device_ == VK_NULL_HANDLE) {
            return;
        }
        destroyTemporalPipeline();
        if (cameraPipeline_ != VK_NULL_HANDLE) {
            destroyPipeline_(device_, cameraPipeline_, nullptr);
            cameraPipeline_ = VK_NULL_HANDLE;
        }
        if (cameraPipelineLayout_ != VK_NULL_HANDLE) {
            destroyPipelineLayout_(device_, cameraPipelineLayout_, nullptr);
            cameraPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (cameraDescriptorPool_ != VK_NULL_HANDLE) {
            destroyDescriptorPool_(device_, cameraDescriptorPool_, nullptr);
            cameraDescriptorPool_ = VK_NULL_HANDLE;
        }
        if (cameraDescriptorSetLayout_ != VK_NULL_HANDLE) {
            destroyDescriptorSetLayout_(device_, cameraDescriptorSetLayout_, nullptr);
            cameraDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (cameraSampler_ != VK_NULL_HANDLE) {
            destroySampler_(device_, cameraSampler_, nullptr);
            cameraSampler_ = VK_NULL_HANDLE;
        }
        if (cameraYcbcrConversion_ != VK_NULL_HANDLE) {
            destroySamplerYcbcrConversion_(device_, cameraYcbcrConversion_, nullptr);
            cameraYcbcrConversion_ = VK_NULL_HANDLE;
        }
        cameraPipelineVkFormat_ = VK_FORMAT_UNDEFINED;
        cameraPipelineExternalFormat_ = 0;
    }

    [[nodiscard]] static std::uint32_t firstSetBit(std::uint32_t mask) {
        for (std::uint32_t index = 0; index < 32U; ++index) {
            if ((mask & (1U << index)) != 0U) {
                return index;
            }
        }
        return std::numeric_limits<std::uint32_t>::max();
    }

    static void closeFileDescriptor(int fileDescriptor) {
        if (fileDescriptor >= 0) {
            ::close(fileDescriptor);
        }
    }

    void renderLoop() {
        {
            std::lock_guard lock(statusMutex_);
            status_ = "running";
        }
        while (!stopRequested_.load()) {
            if (!renderFrame()) {
                ready_.store(false);
                break;
            }
            std::unique_lock lock(waitMutex_);
            wakeCondition_.wait_for(
                lock,
                kDiagnosticFrameInterval,
                [this] { return stopRequested_.load(); }
            );
        }
        std::lock_guard lock(statusMutex_);
        if (errorReason_.empty()) {
            status_ = "stopped";
        }
    }

    bool renderFrame() {
        std::lock_guard renderLock(renderMutex_);
        const std::uint32_t frameIndex = currentFrame_ % kFramesInFlight;
        VkResult result = waitForFences_(
            device_,
            1,
            &frameFences_[frameIndex],
            VK_TRUE,
            std::numeric_limits<std::uint64_t>::max()
        );
        if (result != VK_SUCCESS) {
            setVulkanError("wait_frame_fence", result);
            return false;
        }

        std::uint32_t imageIndex = 0;
        result = acquireNextImage_(
            device_,
            swapchain_,
            kAcquireTimeoutNs,
            imageAvailableSemaphores_[frameIndex],
            VK_NULL_HANDLE,
            &imageIndex
        );
        if (result == VK_TIMEOUT || result == VK_NOT_READY) {
            return true;
        }
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            setVulkanError("acquire_swapchain_image", result);
            return false;
        }
        if (imageIndex >= commandBuffers_.size()) {
            setError("acquired_image_index_out_of_range");
            return false;
        }
        if (imageFences_[imageIndex] != VK_NULL_HANDLE) {
            result = waitForFences_(
                device_,
                1,
                &imageFences_[imageIndex],
                VK_TRUE,
                std::numeric_limits<std::uint64_t>::max()
            );
            if (result != VK_SUCCESS) {
                setVulkanError("wait_image_fence", result);
                return false;
            }
        }
        imageFences_[imageIndex] = frameFences_[frameIndex];

        result = resetFences_(device_, 1, &frameFences_[frameIndex]);
        if (result != VK_SUCCESS) {
            setVulkanError("reset_frame_fence", result);
            return false;
        }
        if (!recordClearCommandBuffer(imageIndex, true)) {
            return false;
        }
        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        const VkSubmitInfo submitInfo{
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .pNext = nullptr,
            .waitSemaphoreCount = 1,
            .pWaitSemaphores = &imageAvailableSemaphores_[frameIndex],
            .pWaitDstStageMask = &waitStage,
            .commandBufferCount = 1,
            .pCommandBuffers = &commandBuffers_[imageIndex],
            .signalSemaphoreCount = 1,
            .pSignalSemaphores = &renderFinishedSemaphores_[frameIndex],
        };
        result = queueSubmit_(graphicsQueue_, 1, &submitInfo, frameFences_[frameIndex]);
        if (result != VK_SUCCESS) {
            setVulkanError("queue_submit", result);
            return false;
        }
        const VkPresentInfoKHR presentInfo{
            .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .pNext = nullptr,
            .waitSemaphoreCount = 1,
            .pWaitSemaphores = &renderFinishedSemaphores_[frameIndex],
            .swapchainCount = 1,
            .pSwapchains = &swapchain_,
            .pImageIndices = &imageIndex,
            .pResults = nullptr,
        };
        result = queuePresent_(graphicsQueue_, &presentInfo);
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            setVulkanError("queue_present", result);
            return false;
        }
        presentedFrames_.fetch_add(1);
        currentFrame_ = (currentFrame_ + 1) % kFramesInFlight;
        return true;
    }

    void destroyVulkan() {
        ready_.store(false);
        if (device_ != VK_NULL_HANDLE && deviceWaitIdle_ != nullptr) {
            deviceWaitIdle_(device_);
        }
        if (device_ != VK_NULL_HANDLE) {
            for (const VkFence fence : frameFences_) {
                if (fence != VK_NULL_HANDLE && destroyFence_ != nullptr) {
                    destroyFence_(device_, fence, nullptr);
                }
            }
            for (const VkSemaphore semaphore : renderFinishedSemaphores_) {
                if (semaphore != VK_NULL_HANDLE && destroySemaphore_ != nullptr) {
                    destroySemaphore_(device_, semaphore, nullptr);
                }
            }
            for (const VkSemaphore semaphore : imageAvailableSemaphores_) {
                if (semaphore != VK_NULL_HANDLE && destroySemaphore_ != nullptr) {
                    destroySemaphore_(device_, semaphore, nullptr);
                }
            }
            if (commandPool_ != VK_NULL_HANDLE && destroyCommandPool_ != nullptr) {
                destroyCommandPool_(device_, commandPool_, nullptr);
                commandPool_ = VK_NULL_HANDLE;
            }
            for (const VkFramebuffer framebuffer : framebuffers_) {
                if (framebuffer != VK_NULL_HANDLE && destroyFramebuffer_ != nullptr) {
                    destroyFramebuffer_(device_, framebuffer, nullptr);
                }
            }
            if (renderPass_ != VK_NULL_HANDLE && destroyRenderPass_ != nullptr) {
                destroyRenderPass_(device_, renderPass_, nullptr);
                renderPass_ = VK_NULL_HANDLE;
            }
            for (const VkImageView imageView : imageViews_) {
                if (imageView != VK_NULL_HANDLE && destroyImageView_ != nullptr) {
                    destroyImageView_(device_, imageView, nullptr);
                }
            }
            if (swapchain_ != VK_NULL_HANDLE && destroySwapchain_ != nullptr) {
                destroySwapchain_(device_, swapchain_, nullptr);
                swapchain_ = VK_NULL_HANDLE;
            }
            if (destroyDevice_ != nullptr) {
                destroyDevice_(device_, nullptr);
            }
            device_ = VK_NULL_HANDLE;
        }
        if (surface_ != VK_NULL_HANDLE && destroySurface_ != nullptr) {
            destroySurface_(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }
        if (instance_ != VK_NULL_HANDLE && destroyInstance_ != nullptr) {
            destroyInstance_(instance_, nullptr);
            instance_ = VK_NULL_HANDLE;
        }
        if (vulkanLibrary_ != nullptr) {
            dlclose(vulkanLibrary_);
            vulkanLibrary_ = nullptr;
        }
    }

    void setError(const std::string& reason) {
        std::lock_guard lock(statusMutex_);
        status_ = "error";
        errorReason_ = reason;
    }

    void setVulkanError(const std::string& operation, VkResult result) {
        setError(operation + "_vk_" + std::to_string(result));
    }

    void setCameraError(const std::string& reason) {
        std::lock_guard lock(statusMutex_);
        cameraError_ = reason;
    }

    void setCameraVulkanError(const std::string& operation, VkResult result) {
        setCameraError(operation + "_vk_" + std::to_string(result));
    }

    void setTemporalError(const std::string& reason) {
        std::lock_guard lock(statusMutex_);
        temporalError_ = reason;
    }

    void setTemporalVulkanError(const std::string& operation, VkResult result) {
        setTemporalError(operation + "_vk_" + std::to_string(result));
    }

    [[nodiscard]] static std::string versionName(std::uint32_t version) {
        std::ostringstream output;
        output << VK_API_VERSION_MAJOR(version) << '.'
               << VK_API_VERSION_MINOR(version) << '.'
               << VK_API_VERSION_PATCH(version);
        return output.str();
    }

    [[nodiscard]] static const char* formatName(VkFormat format) {
        switch (format) {
            case VK_FORMAT_R8G8B8A8_UNORM:
                return "RGBA8_UNORM";
            case VK_FORMAT_R8G8B8A8_SRGB:
                return "RGBA8_SRGB";
            case VK_FORMAT_B8G8R8A8_UNORM:
                return "BGRA8_UNORM";
            case VK_FORMAT_B8G8R8A8_SRGB:
                return "BGRA8_SRGB";
            case VK_FORMAT_UNDEFINED:
                return "UNDEFINED";
            default:
                return "OTHER";
        }
    }

    std::uint32_t requestedWidth_;
    std::uint32_t requestedHeight_;
    ANativeWindow* window_ = nullptr;
    void* vulkanLibrary_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    std::uint32_t queueFamilyIndex_ = 0;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkSurfaceFormatKHR surfaceFormat_{};
    VkExtent2D extent_{};
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> imageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkCommandBuffer> commandBuffers_;
    std::vector<VkSemaphore> imageAvailableSemaphores_;
    std::vector<VkSemaphore> renderFinishedSemaphores_;
    std::vector<VkFence> frameFences_;
    std::vector<VkFence> imageFences_;

    MediaDispatch mediaDispatch_;
    AImageReader* cameraReader_ = nullptr;
    ANativeWindow* cameraWindow_ = nullptr;
    std::uint32_t cameraWidth_ = 0;
    std::uint32_t cameraHeight_ = 0;
    bool cameraPipelineReady_ = false;
    PendingCameraFrame pendingCameraFrame_;
    std::unordered_map<std::uint64_t, AImage*> deliveredCameraFrames_;
    std::uint64_t nextCameraToken_ = 1;
    VkSamplerYcbcrConversion cameraYcbcrConversion_ = VK_NULL_HANDLE;
    VkSampler cameraSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout cameraDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool cameraDescriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout cameraPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline cameraPipeline_ = VK_NULL_HANDLE;
    VkFormat cameraPipelineVkFormat_ = VK_FORMAT_UNDEFINED;
    std::uint64_t cameraPipelineExternalFormat_ = 0;
    bool temporalReady_ = false;
    bool temporalHistoryValid_ = false;
    std::uint32_t temporalHistoryIndex_ = 0;
    std::int64_t temporalHistoryTimestampNs_ = 0;
    std::array<float, kTransformElementCount> temporalHistoryTransform_{};
    std::array<bool, 2> temporalPyramidInitialized_{};
    std::array<VkImage, 2> temporalPyramids_{};
    std::array<VkDeviceMemory, 2> temporalPyramidMemory_{};
    std::array<std::array<VkImageView, kTemporalPyramidLevels>, 2>
        temporalPyramidLevelViews_{};
    std::array<VkImageView, 2> temporalPyramidSampledViews_{};
    VkSampler temporalSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout temporalDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool temporalDescriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout temporalPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline temporalLumaPipeline_ = VK_NULL_HANDLE;
    VkPipeline temporalDownsamplePipeline_ = VK_NULL_HANDLE;
    VkPipeline temporalFlowPipeline_ = VK_NULL_HANDLE;
    VkPipeline temporalFitPipeline_ = VK_NULL_HANDLE;
    VkBuffer temporalFlowBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory temporalFlowMemory_ = VK_NULL_HANDLE;
    VkBuffer temporalFitBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory temporalFitMemory_ = VK_NULL_HANDLE;
    void* temporalFitMapped_ = nullptr;

    std::atomic<bool> ready_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<std::uint64_t> presentedFrames_{0};
    std::atomic<std::uint64_t> cameraImportedFrames_{0};
    std::atomic<std::uint64_t> cameraRenderedFrames_{0};
    std::atomic<std::uint64_t> cameraDeliveredFrames_{0};
    std::atomic<std::uint64_t> cameraReleasedFrames_{0};
    std::atomic<std::uint64_t> cameraDroppedFrames_{0};
    std::atomic<std::uint64_t> cameraAcquireFences_{0};
    std::atomic<std::uint64_t> cameraReleaseFences_{0};
    std::atomic<std::uint64_t> temporalComputedFrames_{0};
    std::atomic<std::uint64_t> temporalAcceptedFrames_{0};
    std::atomic<std::uint64_t> temporalRejectedFrames_{0};
    std::atomic<std::uint32_t> lastCameraWidth_{0};
    std::atomic<std::uint32_t> lastCameraHeight_{0};
    std::atomic<std::uint32_t> lastCameraFormat_{0};
    std::atomic<VkFormat> lastCameraVkFormat_{VK_FORMAT_UNDEFINED};
    std::atomic<std::uint64_t> lastCameraExternalFormat_{0};
    std::uint32_t currentFrame_ = 0;
    std::thread renderThread_;
    mutable std::mutex threadMutex_;
    mutable std::mutex statusMutex_;
    mutable std::mutex cameraMutex_;
    std::mutex renderMutex_;
    std::mutex waitMutex_;
    std::condition_variable wakeCondition_;
    std::string status_ = "initializing";
    std::string errorReason_;
    std::string cameraError_;
    std::string temporalError_;
    std::string deviceName_ = "none";
    std::uint32_t deviceApiVersion_ = 0;
    VkPhysicalDeviceMemoryProperties memoryProperties_{};

    PFN_vkGetInstanceProcAddr getInstanceProcAddress_ = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddress_ = nullptr;
    PFN_vkDestroyInstance destroyInstance_ = nullptr;
    PFN_vkCreateAndroidSurfaceKHR createAndroidSurface_ = nullptr;
    PFN_vkDestroySurfaceKHR destroySurface_ = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices_ = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties_ = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysicalDeviceMemoryProperties_ = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties_ = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR getPhysicalDeviceSurfaceSupport_ = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR getPhysicalDeviceSurfaceCapabilities_ = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR getPhysicalDeviceSurfaceFormats_ = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR getPhysicalDeviceSurfacePresentModes_ = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties_ = nullptr;
    PFN_vkCreateDevice createDevice_ = nullptr;
    PFN_vkDestroyDevice destroyDevice_ = nullptr;
    PFN_vkGetDeviceQueue getDeviceQueue_ = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchain_ = nullptr;
    PFN_vkDestroySwapchainKHR destroySwapchain_ = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImages_ = nullptr;
    PFN_vkCreateImageView createImageView_ = nullptr;
    PFN_vkDestroyImageView destroyImageView_ = nullptr;
    PFN_vkCreateRenderPass createRenderPass_ = nullptr;
    PFN_vkDestroyRenderPass destroyRenderPass_ = nullptr;
    PFN_vkCreateFramebuffer createFramebuffer_ = nullptr;
    PFN_vkDestroyFramebuffer destroyFramebuffer_ = nullptr;
    PFN_vkCreateCommandPool createCommandPool_ = nullptr;
    PFN_vkDestroyCommandPool destroyCommandPool_ = nullptr;
    PFN_vkAllocateCommandBuffers allocateCommandBuffers_ = nullptr;
    PFN_vkBeginCommandBuffer beginCommandBuffer_ = nullptr;
    PFN_vkEndCommandBuffer endCommandBuffer_ = nullptr;
    PFN_vkResetCommandBuffer resetCommandBuffer_ = nullptr;
    PFN_vkCmdBeginRenderPass cmdBeginRenderPass_ = nullptr;
    PFN_vkCmdEndRenderPass cmdEndRenderPass_ = nullptr;
    PFN_vkCmdPipelineBarrier cmdPipelineBarrier_ = nullptr;
    PFN_vkCmdBindPipeline cmdBindPipeline_ = nullptr;
    PFN_vkCmdBindDescriptorSets cmdBindDescriptorSets_ = nullptr;
    PFN_vkCmdPushConstants cmdPushConstants_ = nullptr;
    PFN_vkCmdDraw cmdDraw_ = nullptr;
    PFN_vkCmdDispatch cmdDispatch_ = nullptr;
    PFN_vkCreateSemaphore createSemaphore_ = nullptr;
    PFN_vkDestroySemaphore destroySemaphore_ = nullptr;
    PFN_vkCreateFence createFence_ = nullptr;
    PFN_vkDestroyFence destroyFence_ = nullptr;
    PFN_vkWaitForFences waitForFences_ = nullptr;
    PFN_vkResetFences resetFences_ = nullptr;
    PFN_vkAcquireNextImageKHR acquireNextImage_ = nullptr;
    PFN_vkQueueSubmit queueSubmit_ = nullptr;
    PFN_vkQueuePresentKHR queuePresent_ = nullptr;
    PFN_vkDeviceWaitIdle deviceWaitIdle_ = nullptr;
    PFN_vkCreateImage createImage_ = nullptr;
    PFN_vkDestroyImage destroyImage_ = nullptr;
    PFN_vkGetImageMemoryRequirements getImageMemoryRequirements_ = nullptr;
    PFN_vkAllocateMemory allocateMemory_ = nullptr;
    PFN_vkFreeMemory freeMemory_ = nullptr;
    PFN_vkBindImageMemory bindImageMemory_ = nullptr;
    PFN_vkCreateBuffer createBuffer_ = nullptr;
    PFN_vkDestroyBuffer destroyBuffer_ = nullptr;
    PFN_vkGetBufferMemoryRequirements getBufferMemoryRequirements_ = nullptr;
    PFN_vkBindBufferMemory bindBufferMemory_ = nullptr;
    PFN_vkMapMemory mapMemory_ = nullptr;
    PFN_vkUnmapMemory unmapMemory_ = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAndroidHardwareBufferProperties_ = nullptr;
    PFN_vkCreateSamplerYcbcrConversion createSamplerYcbcrConversion_ = nullptr;
    PFN_vkDestroySamplerYcbcrConversion destroySamplerYcbcrConversion_ = nullptr;
    PFN_vkCreateSampler createSampler_ = nullptr;
    PFN_vkDestroySampler destroySampler_ = nullptr;
    PFN_vkCreateShaderModule createShaderModule_ = nullptr;
    PFN_vkDestroyShaderModule destroyShaderModule_ = nullptr;
    PFN_vkCreateDescriptorSetLayout createDescriptorSetLayout_ = nullptr;
    PFN_vkDestroyDescriptorSetLayout destroyDescriptorSetLayout_ = nullptr;
    PFN_vkCreateDescriptorPool createDescriptorPool_ = nullptr;
    PFN_vkDestroyDescriptorPool destroyDescriptorPool_ = nullptr;
    PFN_vkAllocateDescriptorSets allocateDescriptorSets_ = nullptr;
    PFN_vkFreeDescriptorSets freeDescriptorSets_ = nullptr;
    PFN_vkUpdateDescriptorSets updateDescriptorSets_ = nullptr;
    PFN_vkCreatePipelineLayout createPipelineLayout_ = nullptr;
    PFN_vkDestroyPipelineLayout destroyPipelineLayout_ = nullptr;
    PFN_vkCreateGraphicsPipelines createGraphicsPipelines_ = nullptr;
    PFN_vkCreateComputePipelines createComputePipelines_ = nullptr;
    PFN_vkDestroyPipeline destroyPipeline_ = nullptr;
    PFN_vkImportSemaphoreFdKHR importSemaphoreFd_ = nullptr;
    PFN_vkGetSemaphoreFdKHR getSemaphoreFd_ = nullptr;
};

[[nodiscard]] VulkanDiagnosticRuntime* fromHandle(jlong handle) {
    return reinterpret_cast<VulkanDiagnosticRuntime*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeCreate(
    JNIEnv* environment,
    jobject /* runtime */,
    jobject surface,
    jint width,
    jint height
) {
    try {
        auto* runtime = new (std::nothrow) VulkanDiagnosticRuntime(
            environment,
            surface,
            static_cast<std::uint32_t>(width),
            static_cast<std::uint32_t>(height)
        );
        return reinterpret_cast<jlong>(runtime);
    } catch (const std::exception&) {
        return 0;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeIsReady(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle
) {
    const VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    return runtime != nullptr && runtime->isReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeStart(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle
) {
    VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    return runtime != nullptr && runtime->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeStop(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle
) {
    VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    if (runtime != nullptr) {
        runtime->stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeDestroy(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle
) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeDiagnostic(
    JNIEnv* environment,
    jobject /* runtime */,
    jlong handle
) {
    const VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    const std::string diagnostic = runtime == nullptr
        ? "status=error reason=invalid_native_handle"
        : runtime->diagnostic();
    return environment->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeConfigureCamera(
    JNIEnv* environment,
    jobject /* runtime */,
    jlong handle,
    jint width,
    jint height
) {
    VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    if (runtime == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }
    return runtime->configureCamera(
        environment,
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height)
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeIsCameraBridgeReady(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle
) {
    const VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    return runtime != nullptr && runtime->isCameraBridgeReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeAcquireCameraFrame(
    JNIEnv* environment,
    jobject /* runtime */,
    jlong handle,
    jlongArray metadata,
    jfloatArray uvTransform,
    jfloatArray trackingRoi,
    jfloatArray temporalValues
) {
    VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    if (runtime == nullptr) {
        return nullptr;
    }
    return runtime->acquireCameraFrame(
        environment,
        metadata,
        uvTransform,
        trackingRoi,
        temporalValues
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeReleaseCameraFrame(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle,
    jlong token
) {
    VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    if (runtime != nullptr && token > 0) {
        runtime->releaseCameraFrame(static_cast<std::uint64_t>(token));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_armakeup_render_NativeVulkanDiagnosticRuntime_nativeCloseCamera(
    JNIEnv* /* environment */,
    jobject /* runtime */,
    jlong handle
) {
    VulkanDiagnosticRuntime* runtime = fromHandle(handle);
    if (runtime != nullptr) {
        runtime->closeCamera();
    }
}

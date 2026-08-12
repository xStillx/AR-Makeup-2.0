#include <jni.h>

#include <dlfcn.h>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {

class VulkanLibrary final {
public:
    VulkanLibrary() : handle_(dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL)) {}

    ~VulkanLibrary() {
        if (handle_ != nullptr) {
            dlclose(handle_);
        }
    }

    VulkanLibrary(const VulkanLibrary&) = delete;
    VulkanLibrary& operator=(const VulkanLibrary&) = delete;

    [[nodiscard]] bool isOpen() const { return handle_ != nullptr; }

    template <typename Function>
    [[nodiscard]] Function load(const char* name) const {
        return reinterpret_cast<Function>(dlsym(handle_, name));
    }

private:
    void* handle_;
};

[[nodiscard]] std::string versionName(std::uint32_t version) {
    std::ostringstream output;
    output << VK_API_VERSION_MAJOR(version) << '.'
           << VK_API_VERSION_MINOR(version) << '.'
           << VK_API_VERSION_PATCH(version);
    return output.str();
}

[[nodiscard]] bool hasExtension(
    const std::vector<VkExtensionProperties>& extensions,
    const char* requested
) {
    return std::any_of(
        extensions.begin(),
        extensions.end(),
        [requested](const VkExtensionProperties& extension) {
            return std::string(extension.extensionName) == requested;
        }
    );
}

[[nodiscard]] std::string probeVulkan() {
    VulkanLibrary library;
    if (!library.isOpen()) {
        return "status=unavailable reason=loader_missing";
    }

    const auto getInstanceProcAddress =
        library.load<PFN_vkGetInstanceProcAddr>("vkGetInstanceProcAddr");
    if (getInstanceProcAddress == nullptr) {
        return "status=unavailable reason=get_instance_proc_addr_missing";
    }

    std::uint32_t loaderVersion = VK_API_VERSION_1_0;
    const auto enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        getInstanceProcAddress(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")
    );
    if (enumerateInstanceVersion != nullptr &&
        enumerateInstanceVersion(&loaderVersion) != VK_SUCCESS) {
        return "status=error reason=enumerate_instance_version_failed";
    }

    const auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(
        getInstanceProcAddress(VK_NULL_HANDLE, "vkCreateInstance")
    );
    const auto enumerateInstanceExtensionProperties =
        reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            getInstanceProcAddress(
                VK_NULL_HANDLE,
                "vkEnumerateInstanceExtensionProperties"
            )
        );
    if (createInstance == nullptr || enumerateInstanceExtensionProperties == nullptr) {
        return "status=unavailable reason=instance_entry_point_missing";
    }

    std::uint32_t instanceExtensionCount = 0;
    VkResult result = enumerateInstanceExtensionProperties(
        nullptr,
        &instanceExtensionCount,
        nullptr
    );
    if (result != VK_SUCCESS) {
        return "status=error reason=enumerate_instance_extensions_failed code=" +
            std::to_string(result);
    }
    std::vector<VkExtensionProperties> instanceExtensions(instanceExtensionCount);
    result = enumerateInstanceExtensionProperties(
        nullptr,
        &instanceExtensionCount,
        instanceExtensions.data()
    );
    if (result != VK_SUCCESS) {
        return "status=error reason=read_instance_extensions_failed code=" +
            std::to_string(result);
    }

    const bool supportsSurface = hasExtension(
        instanceExtensions,
        VK_KHR_SURFACE_EXTENSION_NAME
    );
    const bool supportsAndroidSurface = hasExtension(
        instanceExtensions,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    );
    std::vector<const char*> enabledInstanceExtensions;
    if (supportsSurface && supportsAndroidSurface) {
        enabledInstanceExtensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
        enabledInstanceExtensions.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    }

    const std::uint32_t requestedVersion = std::min(loaderVersion, VK_API_VERSION_1_1);
    const VkApplicationInfo applicationInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pNext = nullptr,
        .pApplicationName = "ARMakeup",
        .applicationVersion = VK_MAKE_API_VERSION(0, 1, 0, 0),
        .pEngineName = "ARMakeupVulkan",
        .engineVersion = VK_MAKE_API_VERSION(0, 1, 0, 0),
        .apiVersion = requestedVersion,
    };
    const VkInstanceCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .pApplicationInfo = &applicationInfo,
        .enabledLayerCount = 0,
        .ppEnabledLayerNames = nullptr,
        .enabledExtensionCount = static_cast<std::uint32_t>(
            enabledInstanceExtensions.size()
        ),
        .ppEnabledExtensionNames = enabledInstanceExtensions.data(),
    };

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult createResult = createInstance(&createInfo, nullptr, &instance);
    if (createResult != VK_SUCCESS) {
        return "status=error reason=create_instance_failed code=" +
            std::to_string(createResult);
    }

    const auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
        getInstanceProcAddress(instance, "vkDestroyInstance")
    );
    const auto enumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        getInstanceProcAddress(instance, "vkEnumeratePhysicalDevices")
    );
    const auto getPhysicalDeviceProperties =
        reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            getInstanceProcAddress(instance, "vkGetPhysicalDeviceProperties")
        );
    const auto enumerateDeviceExtensionProperties =
        reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
            getInstanceProcAddress(instance, "vkEnumerateDeviceExtensionProperties")
        );
    const auto getQueueFamilyProperties =
        reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
            getInstanceProcAddress(instance, "vkGetPhysicalDeviceQueueFamilyProperties")
        );

    const auto cleanup = [&]() {
        if (destroyInstance != nullptr) {
            destroyInstance(instance, nullptr);
        }
    };
    if (destroyInstance == nullptr ||
        enumeratePhysicalDevices == nullptr ||
        getPhysicalDeviceProperties == nullptr ||
        enumerateDeviceExtensionProperties == nullptr ||
        getQueueFamilyProperties == nullptr) {
        cleanup();
        return "status=error reason=instance_entry_point_missing";
    }

    std::uint32_t physicalDeviceCount = 0;
    result = enumeratePhysicalDevices(instance, &physicalDeviceCount, nullptr);
    if (result != VK_SUCCESS || physicalDeviceCount == 0) {
        cleanup();
        return "status=unavailable reason=physical_device_missing code=" +
            std::to_string(result);
    }

    std::vector<VkPhysicalDevice> physicalDevices(physicalDeviceCount);
    result = enumeratePhysicalDevices(
        instance,
        &physicalDeviceCount,
        physicalDevices.data()
    );
    if (result != VK_SUCCESS) {
        cleanup();
        return "status=error reason=enumerate_physical_devices_failed code=" +
            std::to_string(result);
    }

    const VkPhysicalDevice physicalDevice = physicalDevices.front();
    VkPhysicalDeviceProperties properties{};
    getPhysicalDeviceProperties(physicalDevice, &properties);

    std::uint32_t extensionCount = 0;
    result = enumerateDeviceExtensionProperties(
        physicalDevice,
        nullptr,
        &extensionCount,
        nullptr
    );
    if (result != VK_SUCCESS) {
        cleanup();
        return "status=error reason=enumerate_device_extensions_failed code=" +
            std::to_string(result);
    }
    std::vector<VkExtensionProperties> extensions(extensionCount);
    result = enumerateDeviceExtensionProperties(
        physicalDevice,
        nullptr,
        &extensionCount,
        extensions.data()
    );
    if (result != VK_SUCCESS) {
        cleanup();
        return "status=error reason=read_device_extensions_failed code=" +
            std::to_string(result);
    }

    std::uint32_t queueFamilyCount = 0;
    getQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    getQueueFamilyProperties(
        physicalDevice,
        &queueFamilyCount,
        queueFamilies.data()
    );
    const bool supportsTimestamps = std::any_of(
        queueFamilies.begin(),
        queueFamilies.end(),
        [](const VkQueueFamilyProperties& queueFamily) {
            return (queueFamily.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0U &&
                queueFamily.timestampValidBits > 0U;
        }
    );
    const bool supportsGraphicsQueue = std::any_of(
        queueFamilies.begin(),
        queueFamilies.end(),
        [](const VkQueueFamilyProperties& queueFamily) {
            return (queueFamily.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0U;
        }
    );

    const bool supportsAndroidHardwareBuffer = hasExtension(
        extensions,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    );
    const bool supportsSamplerYcbcr =
        properties.apiVersion >= VK_API_VERSION_1_1 ||
        hasExtension(extensions, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME);
    const bool supportsTimelineSemaphore =
        properties.apiVersion >= VK_API_VERSION_1_2 ||
        hasExtension(extensions, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
    const bool supportsSwapchain = hasExtension(
        extensions,
        VK_KHR_SWAPCHAIN_EXTENSION_NAME
    );

    std::ostringstream diagnostic;
    diagnostic << "status=ok"
               << " loader=" << versionName(loaderVersion)
               << " device=" << properties.deviceName
               << " deviceApi=" << versionName(properties.apiVersion)
               << " surface=" << supportsSurface
               << " androidSurface=" << supportsAndroidSurface
               << " graphics=" << supportsGraphicsQueue
               << " swapchain=" << supportsSwapchain
               << " ahb=" << supportsAndroidHardwareBuffer
               << " ycbcr=" << supportsSamplerYcbcr
               << " timeline=" << supportsTimelineSemaphore
               << " timestamps=" << supportsTimestamps;
    cleanup();
    return diagnostic.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_armakeup_render_NativeVulkanBootstrap_nativeProbe(
    JNIEnv* environment,
    jclass /* type */
) {
    const std::string diagnostic = probeVulkan();
    return environment->NewStringUTF(diagnostic.c_str());
}

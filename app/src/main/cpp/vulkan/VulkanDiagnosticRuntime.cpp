#include <android/native_window_jni.h>
#include <jni.h>

#include <dlfcn.h>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <exception>
#include <limits>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr std::uint32_t kFramesInFlight = 2;
constexpr std::uint64_t kAcquireTimeoutNs = 1'000'000'000ULL;
constexpr std::chrono::milliseconds kDiagnosticFrameInterval{100};

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
        destroyVulkan();
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    VulkanDiagnosticRuntime(const VulkanDiagnosticRuntime&) = delete;
    VulkanDiagnosticRuntime& operator=(const VulkanDiagnosticRuntime&) = delete;

    [[nodiscard]] bool isReady() const { return ready_.load(); }

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
               << " frames=" << presentedFrames_.load();
        if (!errorReason_.empty()) {
            output << " reason=" << errorReason_;
        }
        return output.str();
    }

private:
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
            if (!supportsDeviceExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
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
        const char* deviceExtension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        const VkPhysicalDeviceFeatures features{};
        const VkDeviceCreateInfo deviceCreateInfo{
            .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &queueCreateInfo,
            .enabledLayerCount = 0,
            .ppEnabledLayerNames = nullptr,
            .enabledExtensionCount = 1,
            .ppEnabledExtensionNames = &deviceExtension,
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
        cmdBeginRenderPass_ = loadDevice<PFN_vkCmdBeginRenderPass>("vkCmdBeginRenderPass");
        cmdEndRenderPass_ = loadDevice<PFN_vkCmdEndRenderPass>("vkCmdEndRenderPass");
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
            cmdBeginRenderPass_ == nullptr ||
            cmdEndRenderPass_ == nullptr ||
            createSemaphore_ == nullptr ||
            destroySemaphore_ == nullptr ||
            createFence_ == nullptr ||
            destroyFence_ == nullptr ||
            waitForFences_ == nullptr ||
            resetFences_ == nullptr ||
            acquireNextImage_ == nullptr ||
            queueSubmit_ == nullptr ||
            queuePresent_ == nullptr ||
            deviceWaitIdle_ == nullptr) {
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
            .flags = 0,
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
            const VkCommandBufferBeginInfo beginInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT,
                .pInheritanceInfo = nullptr,
            };
            result = beginCommandBuffer_(commandBuffers_[index], &beginInfo);
            if (result != VK_SUCCESS) {
                setVulkanError("begin_command_buffer", result);
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
                .framebuffer = framebuffers_[index],
                .renderArea = VkRect2D{
                    .offset = VkOffset2D{.x = 0, .y = 0},
                    .extent = extent_,
                },
                .clearValueCount = 1,
                .pClearValues = &clearValue,
            };
            cmdBeginRenderPass_(
                commandBuffers_[index],
                &renderPassInfo,
                VK_SUBPASS_CONTENTS_INLINE
            );
            cmdEndRenderPass_(commandBuffers_[index]);
            result = endCommandBuffer_(commandBuffers_[index]);
            if (result != VK_SUCCESS) {
                setVulkanError("end_command_buffer", result);
                return false;
            }
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

    std::atomic<bool> ready_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<std::uint64_t> presentedFrames_{0};
    std::uint32_t currentFrame_ = 0;
    std::thread renderThread_;
    mutable std::mutex threadMutex_;
    mutable std::mutex statusMutex_;
    std::mutex waitMutex_;
    std::condition_variable wakeCondition_;
    std::string status_ = "initializing";
    std::string errorReason_;
    std::string deviceName_ = "none";
    std::uint32_t deviceApiVersion_ = 0;

    PFN_vkGetInstanceProcAddr getInstanceProcAddress_ = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddress_ = nullptr;
    PFN_vkDestroyInstance destroyInstance_ = nullptr;
    PFN_vkCreateAndroidSurfaceKHR createAndroidSurface_ = nullptr;
    PFN_vkDestroySurfaceKHR destroySurface_ = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices_ = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties_ = nullptr;
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
    PFN_vkCmdBeginRenderPass cmdBeginRenderPass_ = nullptr;
    PFN_vkCmdEndRenderPass cmdEndRenderPass_ = nullptr;
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

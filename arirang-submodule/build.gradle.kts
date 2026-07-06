import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

val sdkDir = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(provider {
        val properties = java.util.Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }
        properties.getProperty("sdk.dir")
    })

val adbPath = sdkDir.map { "$it/platform-tools/adb" }.getOrElse("adb")
val adbSerial = providers.gradleProperty("arirang.device")
    .orElse(providers.environmentVariable("ARIRANG_DEVICE"))
val rootMethod = providers.gradleProperty("arirang.root")
    .orElse(providers.environmentVariable("ARIRANG_ROOT"))

fun adbCommand(vararg args: String): List<String> = buildList {
    add(adbPath)
    adbSerial.orNull?.takeIf { it.isNotBlank() }?.let {
        add("-s")
        add(it)
    }
    addAll(args)
}

fun installShellCommand(reboot: Boolean, forcedRootMethod: String? = null): String {
    val root = forcedRootMethod ?: rootMethod.orNull?.trim().orEmpty()
    if (root.isNotEmpty() && root !in setOf("magisk", "ksu", "kernelsu", "ap", "apatch")) {
        throw GradleException("Unsupported arirang.root: $root. Use magisk, ksu, kernelsu, ap, or apatch.")
    }

    val args = mutableListOf(remoteZipPath)
    if (root.isNotEmpty()) args += root
    if (reboot) args += "--reboot"
    return "sh $remoteInstallScriptPath ${args.joinToString(" ")}"
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

fun privilegedShellCommand(command: String): String =
    "if [ \"\$(id -u)\" = \"0\" ]; then " +
        "$command; " +
    "else " +
        "for su_bin in su /system/bin/su /system/xbin/su /data/adb/ksu/bin/su /debug_ramdisk/su; do " +
            "\"\$su_bin\" -c 'id -u' >/dev/null 2>/data/local/tmp/arirang_su_error || continue; " +
            "\"\$su_bin\" -c ${shellQuote(command)}; " +
            "exit \$?; " +
        "done; " +
        "echo \"adb shell is not root and no usable su binary was found. Grant ADB shell/root access in KernelSU Next, then rerun this task.\" >&2; " +
        "if [ -s /data/local/tmp/arirang_su_error ]; then cat /data/local/tmp/arirang_su_error >&2; fi; " +
        "rm -f /data/local/tmp/arirang_su_error; " +
        "exit 1; " +
    "fi"

val ndkVersion = "23.1.7779620"
val ndkDir = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.gradleProperty("android.ndkpath"))
    .orElse(sdkDir.map { "$it/ndk/$ndkVersion" })
    .orElse(provider {
        val properties = java.util.Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }
        properties.getProperty("ndk.dir")
    })

val nativeBuildDir = layout.buildDirectory.dir("native/arm64-v8a")
val stageDir = layout.buildDirectory.dir("stage")
val outputDir = layout.buildDirectory.dir("outputs")
val outputZip = outputDir.map { it.file("arirang-submodule.zip") }
val remoteZipPath = "/data/local/tmp/arirang-submodule.zip"
val remoteInstallScriptPath = "/data/local/tmp/arirang_install_module.sh"
val arirangApplicationId = rootProject.extra["arirangApplicationId"] as String
val arirangSubmoduleConfigDir = rootProject.extra["arirangSubmoduleConfigDir"] as String
val arirangSubmoduleConfigFile = rootProject.extra["arirangSubmoduleConfigFile"] as String

val configureNative by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp"))
    inputs.file("CMakeLists.txt")
    outputs.file(nativeBuildDir.map { it.file("build.ninja") })

    doFirst {
        if (!ndkDir.isPresent) {
            throw GradleException("NDK directory not found. Please set ANDROID_NDK_HOME or ANDROID_HOME environment variable, or ndk.dir/sdk.dir in local.properties.")
        }
        val toolchain = "${ndkDir.get()}/build/cmake/android.toolchain.cmake"
        if (!file(toolchain).exists()) {
            throw GradleException("Android toolchain not found at $toolchain. Check your NDK installation.")
        }
        commandLine(
            "cmake",
            "-S", projectDir.absolutePath,
            "-B", nativeBuildDir.get().asFile.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-31",
            // These are generated into arirang_build_config.hpp and must stay
            // in sync with the app module. The native code uses them to locate
            // the manager app's runtime config without hard-coding app paths in
            // C++ sources.
            "-DARIRANG_APPLICATION_ID=$arirangApplicationId",
            "-DARIRANG_SUBMODULE_CONFIG_DIR=$arirangSubmoduleConfigDir",
            "-DARIRANG_SUBMODULE_CONFIG_FILE=$arirangSubmoduleConfigFile",
            "-G", "Ninja"
        )
    }
}

val buildNative by tasks.registering(Exec::class) {
    dependsOn(configureNative)
    inputs.files(fileTree("src/main/cpp"))
    inputs.file("CMakeLists.txt")
    outputs.files(
        nativeBuildDir.map { it.file("libarirang_zygisk.so") },
        nativeBuildDir.map { it.file("libarirang_drm_hook.so") },
        nativeBuildDir.map { it.file("arirang_injector") }
    )

    doFirst {
        commandLine("cmake", "--build", nativeBuildDir.get().asFile.absolutePath)
    }
}

val stageModule by tasks.registering(Copy::class) {
    dependsOn(buildNative)
    // Stage the exact Magisk/KernelSU/APatch module root. Files copied here are
    // zipped without an extra directory level, so paths must match the runtime
    // module layout expected by post-fs-data.sh and service.sh.
    from("module/module.prop") {
        into("")
    }
    from("module/post-fs-data.sh") {
        into("")
    }
    from("module/service.sh") {
        into("")
    }
    from("module/sepolicy.rule") {
        into("")
    }
    from("module/lib") {
        into("lib")
    }
    from(nativeBuildDir.map { it.file("libarirang_zygisk.so") }) {
        into("zygisk")
        // Root managers discover the Zygisk library by ABI-specific filename.
        // Do not rename this to the CMake target name.
        rename { "arm64-v8a.so" }
    }
    // Hook .so is staged inside the module's own private directory and
    // bind-mounted into a vendor library path at post-fs-data, so it is
    // reachable to the vendor linker namespace without depending on a
    // KSU metamodule overlay.
    from(nativeBuildDir.map { it.file("libarirang_drm_hook.so") }) {
        into("lib")
    }
    from(nativeBuildDir.map { it.file("arirang_injector") }) {
        // service.sh executes this directly from the module directory after
        // boot, so keep it outside zygisk/ and lib/.
        into("bin")
    }
    into(stageDir)
}

val packageModule by tasks.registering(Zip::class) {
    dependsOn(stageModule)
    archiveFileName.set("arirang-submodule.zip")
    destinationDirectory.set(outputDir)
    from(stageDir)
}

val pushModuleZipToDevice by tasks.registering(Exec::class) {
    group = "install"
    description = "Push the packaged Arirang module zip to a connected device."
    dependsOn(packageModule)
    inputs.file(outputZip)

    doFirst {
        commandLine(adbCommand(
            "push",
            outputZip.get().asFile.absolutePath,
            remoteZipPath
        ))
    }
}

val pushInstallScriptToDevice by tasks.registering(Exec::class) {
    group = "install"
    description = "Push the root module installer script to a connected device."
    inputs.file("scripts/install_module.sh")

    doFirst {
        commandLine(adbCommand(
            "push",
            file("scripts/install_module.sh").absolutePath,
            remoteInstallScriptPath
        ))
    }
}

val pushModuleToDevice by tasks.registering {
    group = "install"
    description = "Push the module zip and installer script to a connected device."
    dependsOn(pushModuleZipToDevice, pushInstallScriptToDevice)
}

val cleanupPushedModuleFiles by tasks.registering(Exec::class) {
    group = "install"
    description = "Remove temporary module files pushed to /data/local/tmp."
    isIgnoreExitValue = true

    doFirst {
        commandLine(adbCommand(
            "shell",
            "rm -f $remoteInstallScriptPath $remoteZipPath"
        ))
    }
}

val installModule by tasks.registering(Exec::class) {
    group = "install"
    description = "Build, push, and install the Arirang module with Magisk, KernelSU, or APatch."
    dependsOn(pushModuleToDevice)
    finalizedBy(cleanupPushedModuleFiles)

    doFirst {
        commandLine(adbCommand(
            "shell",
            privilegedShellCommand(installShellCommand(reboot = false))
        ))
    }
}

val installModuleAndReboot by tasks.registering(Exec::class) {
    group = "install"
    description = "Build, push, install the Arirang module, and reboot the device."
    dependsOn(pushModuleToDevice)
    finalizedBy(cleanupPushedModuleFiles)

    doFirst {
        commandLine(adbCommand(
            "shell",
            privilegedShellCommand(installShellCommand(reboot = true))
        ))
    }
}

val installKernelSuNextAndReboot by tasks.registering(Exec::class) {
    group = "install"
    description = "Build, push, install the Arirang module with KernelSU Next, and reboot the device."
    dependsOn(pushModuleToDevice)
    finalizedBy(cleanupPushedModuleFiles)

    doFirst {
        commandLine(adbCommand(
            "shell",
            privilegedShellCommand(installShellCommand(reboot = true, forcedRootMethod = "ksu"))
        ))
    }
}

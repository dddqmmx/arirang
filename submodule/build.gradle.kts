import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

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
        if (it.length > 128 || !it.matches(Regex("[A-Za-z0-9._:-]+"))) {
            throw GradleException("Invalid arirang.device serial: $it")
        }
        add("-s")
        add(it)
    }
    addAll(args)
}

fun sha256Hex(input: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.inputStream().buffered().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun installShellCommand(reboot: Boolean, forcedRootMethod: String? = null): String {
    val root = forcedRootMethod ?: rootMethod.orNull?.trim().orEmpty()
    if (root.isNotEmpty() && root !in setOf("magisk", "ksu", "kernelsu", "ap", "apatch")) {
        throw GradleException("Unsupported arirang.root: $root. Use magisk, ksu, kernelsu, ap, or apatch.")
    }

    val zipFile = outputZip.get().asFile
    val scriptFile = file("scripts/install_module.sh")
    if (!zipFile.isFile || Files.isSymbolicLink(zipFile.toPath())) {
        throw GradleException("Packaged module is missing or is not a regular file: $zipFile")
    }
    if (!scriptFile.isFile || Files.isSymbolicLink(scriptFile.toPath())) {
        throw GradleException("Installer is missing or is not a regular file: $scriptFile")
    }

    val zipSize = zipFile.length()
    val scriptSize = scriptFile.length()
    if (zipSize !in 1..(128L * 1024 * 1024) || scriptSize !in 1..(1024L * 1024)) {
        throw GradleException("Refusing to install an empty or oversized module/installer artifact")
    }
    val zipBlocks = (zipSize + 4095) / 4096
    val scriptBlocks = (scriptSize + 4095) / 4096
    val zipHash = sha256Hex(zipFile)
    val scriptHash = sha256Hex(scriptFile)

    val installerArgs = buildList {
        add("\"\$private_script\"")
        add("\"\$private_zip\"")
        if (root.isNotEmpty()) add(shellQuote(root))
    }.joinToString(" ")

    return listOf(
        "set -eu",
        "umask 077",
        // A PID-based directory name (the previous "-\$\$" suffix) is
        // predictable to any other process on the device and can be raced by
        // a symlink planted ahead of mkdir. mktemp's random suffix removes
        // that guess, and the ownership/mode checks on /data/adb mirror
        // scripts/install_module.sh's prepare_module_snapshot() so a
        // tampered or non-root /data/adb is rejected before anything is
        // written under it.
        "[ -d /data/adb ] && [ ! -L /data/adb ]",
        "[ \"\$(/system/bin/toybox stat -c '%u' /data/adb)\" = \"0\" ]",
        "case \"\$(/system/bin/toybox stat -c '%A' /data/adb)\" in ?????w????|????????w?) exit 1;; esac",
        "private_dir=\$(/system/bin/toybox mktemp -d /data/adb/.arirang-install.XXXXXX)",
        "/system/bin/toybox chown 0:0 \"\$private_dir\"",
        "/system/bin/toybox chmod 700 \"\$private_dir\"",
        "private_zip=\"\$private_dir/module.zip\"",
        "private_script=\"\$private_dir/install.sh\"",
        "cleanup() { /system/bin/toybox rm -rf \"\$private_dir\"; }",
        "trap cleanup EXIT",
        "trap 'exit 129' HUP",
        "trap 'exit 130' INT",
        "trap 'exit 143' TERM",
        "/system/bin/toybox timeout -s KILL 30 /system/bin/toybox dd if=${shellQuote(remoteZipPath)} of=\"\$private_zip\" bs=4096 count=$zipBlocks 2>/dev/null",
        "/system/bin/toybox timeout -s KILL 10 /system/bin/toybox dd if=${shellQuote(remoteInstallScriptPath)} of=\"\$private_script\" bs=4096 count=$scriptBlocks 2>/dev/null",
        "[ \"\$(/system/bin/toybox wc -c < \"\$private_zip\")\" = ${shellQuote(zipSize.toString())} ]",
        "[ \"\$(/system/bin/toybox wc -c < \"\$private_script\")\" = ${shellQuote(scriptSize.toString())} ]",
        "zip_actual=\$(/system/bin/toybox sha256sum \"\$private_zip\")",
        "zip_actual=\${zip_actual%% *}",
        "script_actual=\$(/system/bin/toybox sha256sum \"\$private_script\")",
        "script_actual=\${script_actual%% *}",
        "[ \"\$zip_actual\" = ${shellQuote(zipHash)} ]",
        "[ \"\$script_actual\" = ${shellQuote(scriptHash)} ]",
        "/system/bin/toybox chmod 600 \"\$private_zip\"",
        "/system/bin/toybox chmod 700 \"\$private_script\"",
        "/system/bin/sh $installerArgs",
        "cleanup",
        "trap - EXIT HUP INT TERM",
        if (reboot) "/system/bin/svc power reboot || /system/bin/reboot" else ":"
    ).joinToString("; ")
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

fun privilegedShellCommand(command: String): String =
    "if [ \"\$(/system/bin/id -u)\" = \"0\" ]; then " +
        "$command; " +
    "else " +
        "for su_bin in /system/bin/su /system/xbin/su /data/adb/ksu/bin/su /debug_ramdisk/su /sbin/su; do " +
            "[ -x \"\$su_bin\" ] || continue; " +
            "[ \"\$(\"\$su_bin\" -c '/system/bin/id -u' 2>/dev/null)\" = \"0\" ] || continue; " +
            "\"\$su_bin\" -c ${shellQuote(command)}; " +
            "exit \$?; " +
        "done; " +
        "echo \"adb shell is not root and no usable su binary was found. Grant ADB shell/root access in KernelSU Next, then rerun this task.\" >&2; " +
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

val moduleLibrarySources = listOf(
    "common.sh",
    "resetprop.sh",
    "staging.sh",
    "vendor_bind.sh",
    "widevine.sh"
)
val packagedModuleFiles = setOf(
    "module.prop",
    "post-fs-data.sh",
    "service.sh",
    "sepolicy.rule",
    "lib/common.sh",
    "lib/resetprop.sh",
    "lib/staging.sh",
    "lib/vendor_bind.sh",
    "lib/widevine.sh",
    "zygisk/arm64-v8a.so",
    "lib/libarirang_drm_hook.so",
    "bin/arirang_injector"
)
val packagedModuleDirectories = setOf("bin/", "lib/", "zygisk/")

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
        val sourceProperties = file("${ndkDir.get()}/source.properties")
        val ndkProperties = java.util.Properties()
        if (!sourceProperties.isFile) {
            throw GradleException("NDK source.properties not found at $sourceProperties")
        }
        sourceProperties.inputStream().use { ndkProperties.load(it) }
        if (ndkProperties.getProperty("Pkg.Revision") != ndkVersion) {
            throw GradleException(
                "Expected NDK $ndkVersion but found ${ndkProperties.getProperty("Pkg.Revision")} at ${ndkDir.get()}"
            )
        }
        commandLine(
            "cmake",
            "-S", projectDir.absolutePath,
            "-B", nativeBuildDir.get().asFile.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-31",
            "-DCMAKE_BUILD_TYPE=Release",
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

val stageModule by tasks.registering(Sync::class) {
    dependsOn(buildNative)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dirPermissions { unix("rwxr-xr-x") }

    doFirst {
        val sourceFiles = listOf(
            file("module/module.prop"),
            file("module/post-fs-data.sh"),
            file("module/service.sh"),
            file("module/sepolicy.rule")
        ) + moduleLibrarySources.map { file("module/lib/$it") }
        sourceFiles.forEach { source ->
            if (!source.isFile || Files.isSymbolicLink(source.toPath())) {
                throw GradleException("Module source is missing or unsafe: $source")
            }
        }
        listOf(
            nativeBuildDir.get().file("libarirang_zygisk.so").asFile,
            nativeBuildDir.get().file("libarirang_drm_hook.so").asFile,
            nativeBuildDir.get().file("arirang_injector").asFile
        ).forEach { artifact ->
            if (!artifact.isFile || Files.isSymbolicLink(artifact.toPath())) {
                throw GradleException("Native artifact is missing or unsafe: $artifact")
            }
        }
    }
    // Stage the exact Magisk/KernelSU/APatch module root. Files copied here are
    // zipped without an extra directory level, so paths must match the runtime
    // module layout expected by post-fs-data.sh and service.sh.
    from("module/module.prop") {
        into("")
        filePermissions { unix("rw-r--r--") }
    }
    from("module/post-fs-data.sh") {
        into("")
        filePermissions { unix("rwxr-xr-x") }
    }
    from("module/service.sh") {
        into("")
        filePermissions { unix("rwxr-xr-x") }
    }
    from("module/sepolicy.rule") {
        into("")
        filePermissions { unix("rw-r--r--") }
    }
    from(moduleLibrarySources.map { file("module/lib/$it") }) {
        into("lib")
        filePermissions { unix("rw-r--r--") }
    }
    from(nativeBuildDir.map { it.file("libarirang_zygisk.so") }) {
        into("zygisk")
        filePermissions { unix("rw-r--r--") }
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
        filePermissions { unix("rw-r--r--") }
    }
    from(nativeBuildDir.map { it.file("arirang_injector") }) {
        // service.sh executes this directly from the module directory after
        // boot, so keep it outside zygisk/ and lib/.
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    into(stageDir)
}

val packageModule by tasks.registering(Zip::class) {
    dependsOn(stageModule)
    archiveFileName.set("arirang-submodule.zip")
    destinationDirectory.set(outputDir)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(stageDir) {
        eachFile {
            val executable = path == "post-fs-data.sh" || path == "service.sh" ||
                path == "bin/arirang_injector"
            permissions {
                unix(if (executable) "rwxr-xr-x" else "rw-r--r--")
            }
        }
        dirPermissions { unix("rwxr-xr-x") }
    }

    doFirst {
        val root = stageDir.get().asFile.toPath().toAbsolutePath().normalize()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                val normalized = path.toAbsolutePath().normalize()
                if (!normalized.startsWith(root) || Files.isSymbolicLink(path)) {
                    throw GradleException("Unsafe staged module path: $path")
                }
            }
        }
    }

    doLast {
        val seenFiles = mutableSetOf<String>()
        val seenEntries = mutableSetOf<String>()
        var totalSize = 0L
        ZipFile(archiveFile.get().asFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                val components = name.removeSuffix("/").split('/')
                val unsafeName = name.isEmpty() || name.startsWith('/') || '\\' in name ||
                    name.any { it.code < 0x20 } || components.any { it.isEmpty() || it == "." || it == ".." }
                if (unsafeName || name == "vendor/" || name.startsWith("vendor/") ||
                    !seenEntries.add(name)) {
                    throw GradleException("Unsafe or duplicate module zip entry: $name")
                }
                if (entry.isDirectory) {
                    if (name !in packagedModuleDirectories) {
                        throw GradleException("Unexpected module zip directory: $name")
                    }
                    continue
                }
                if (name !in packagedModuleFiles || entry.size <= 0 || entry.size > 128L * 1024 * 1024) {
                    throw GradleException("Unexpected or invalid module zip file: $name")
                }
                totalSize = Math.addExact(totalSize, entry.size)
                if (totalSize > 256L * 1024 * 1024) {
                    throw GradleException("Module zip expands beyond the permitted size")
                }
                seenFiles += name
            }
        }
        if (seenFiles != packagedModuleFiles) {
            throw GradleException("Module zip allowlist mismatch: expected=$packagedModuleFiles actual=$seenFiles")
        }
    }
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

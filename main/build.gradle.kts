import com.android.build.gradle.api.ApplicationVariant

/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

plugins {
    alias(libs.plugins.android.application)
    id("checkstyle")
}

android {
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    namespace = "de.blinkt.openvpn"
    compileSdk = 36
    //compileSdkPreview = "UpsideDownCake"

    // Also update runcoverity.sh
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 21
        targetSdk = 36
        //targetSdkPreview = "UpsideDownCake"
        versionCode = 219
        versionName = "0.7.64"
        externalNativeBuild {
            cmake {
                //arguments+= "-DCMAKE_VERBOSE_MAKEFILE=1"
            }
        }
    }


    //testOptions.unitTests.isIncludeAndroidResources = true

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "build/ovpnassets")
            jniLibs.srcDirs("${buildDir}/singbox/jniLibs", "${buildDir}/ydtun/jniLibs")
        }

        create("ui") {
        }

        create("skeleton") {
        }

        getByName("debug") {
        }

        getByName("release") {
        }
    }

    signingConfigs {
        create("release") {
            // ~/.gradle/gradle.properties
            val keystoreFile: String? by project
            storeFile = keystoreFile?.let { file(it) }
            val keystorePassword: String? by project
            storePassword = keystorePassword
            val keystoreAliasPassword: String? by project
            keyPassword = keystoreAliasPassword
            val keystoreAlias: String? by project
            keyAlias = keystoreAlias
            enableV1Signing = true
            enableV2Signing = true
        }

        create("releaseOvpn2") {
            // ~/.gradle/gradle.properties
            val keystoreO2File: String? by project
            storeFile = keystoreO2File?.let { file(it) }
            val keystoreO2Password: String? by project
            storePassword = keystoreO2Password
            val keystoreO2AliasPassword: String? by project
            keyPassword = keystoreO2AliasPassword
            val keystoreO2Alias: String? by project
            keyAlias = keystoreO2Alias
            enableV1Signing = true
            enableV2Signing = true
        }

    }

    lint {
        enable += setOf("BackButton", "EasterEgg", "StopShip", "IconExpectedSize", "GradleDynamicVersion", "NewerVersionAvailable")
        checkOnly += setOf("ImpliedQuantity", "MissingQuantity")
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }


    flavorDimensions += listOf("implementation", "ovpnimpl")

    productFlavors {
        create("ui") {
            dimension = "implementation"
        }

        create("skeleton") {
            dimension = "implementation"
        }

        create("ovpn23")
        {
            dimension = "ovpnimpl"
            buildConfigField("boolean", "openvpn3", "true")
        }

        create("ovpn2")
        {
            dimension = "ovpnimpl"
            versionNameSuffix = "-o2"
            buildConfigField("boolean", "openvpn3", "false")
        }
    }

    buildTypes {
        getByName("release") {
            if (project.hasProperty("icsopenvpnDebugSign")) {
                logger.warn("property icsopenvpnDebugSign set, using debug signing for release")
                signingConfig = android.signingConfigs.getByName("debug")
            } else {
                productFlavors["ovpn23"].signingConfig = signingConfigs.getByName("release")
                productFlavors["ovpn2"].signingConfig = signingConfigs.getByName("releaseOvpn2")
            }
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            val abis = (findProperty("buildAbis") as? String)?.split(",") ?: listOf("arm64-v8a")
            include(*abis.toTypedArray())
            isUniversalApk = abis.size > 1
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    bundle {
        codeTransparency {
            signing {
                val keystoreTPFile: String? by project
                storeFile = keystoreTPFile?.let { file(it) }
                val keystoreTPPassword: String? by project
                storePassword = keystoreTPPassword
                val keystoreTPAliasPassword: String? by project
                keyPassword = keystoreTPAliasPassword
                val keystoreTPAlias: String? by project
                keyAlias = keystoreTPAlias

                if (keystoreTPFile?.isEmpty() ?: true)
                    println("keystoreTPFile not set, disabling transparency signing")
                if (keystoreTPPassword?.isEmpty() ?: true)
                    println("keystoreTPPassword not set, disabling transparency signing")
                if (keystoreTPAliasPassword?.isEmpty() ?: true)
                    println("keystoreTPAliasPassword not set, disabling transparency signing")
                if (keystoreTPAlias?.isEmpty() ?: true)
                    println("keyAlias not set, disabling transparency signing")

            }
        }
    }
}

var swigcmd = "swig"
// Workaround for macOS(arm64) and macOS(intel) since it otherwise does not find swig and
// I cannot get the Exec task to respect the PATH environment :(
if (file("/opt/homebrew/bin/swig").exists())
    swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists())
    swigcmd = "/usr/local/bin/swig"


fun registerGenTask(variantName: String, variantDirName: String): File {
    val baseDir = File(buildDir, "generated/source/ovpn3swig/${variantDirName}")
    val genDir = File(baseDir, "net/openvpn/ovpn3")

    tasks.register<Exec>("generateOpenVPN3Swig${variantName}")
    {

        doFirst {
            mkdir(genDir)
        }
        commandLine(listOf(swigcmd, "-outdir", genDir, "-outcurrentdir", "-c++", "-java", "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client", "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "${genDir}/ovpncli_wrap.cxx", "-oh", "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"))
        inputs.files( "src/main/cpp/openvpn3/client/ovpncli.i")
        outputs.dir( genDir)

    }
    return baseDir
}

android.applicationVariants.all(object : Action<ApplicationVariant> {
    override fun execute(variant: ApplicationVariant) {
        val sourceDir = registerGenTask(variant.name, variant.baseName.replace("-", "/"))
        val task = tasks.named("generateOpenVPN3Swig${variant.name}").get()

        variant.registerJavaGeneratingTask(task, sourceDir)
    }
})

// sing-box build tasks
val singboxSrcDir = file("src/main/go/sing-box")
val singboxOutputDir = file("${buildDir}/singbox/jniLibs")

data class SingBoxTarget(val goarch: String, val ccPrefix: String)
val allSingboxTargets = mapOf(
    "arm64-v8a"   to SingBoxTarget("arm64", "aarch64-linux-android21"),
    "armeabi-v7a" to SingBoxTarget("arm",   "armv7a-linux-androideabi21"),
    "x86_64"      to SingBoxTarget("amd64", "x86_64-linux-android21"),
    "x86"         to SingBoxTarget("386",   "i686-linux-android21")
)
val buildAbis = (findProperty("buildAbis") as? String)?.split(",") ?: listOf("arm64-v8a")
val singboxTargets = allSingboxTargets.filterKeys { it in buildAbis }

var gocmd = "go"
if (file("/opt/homebrew/bin/go").exists())
    gocmd = "/opt/homebrew/bin/go"
else if (file("/usr/local/go/bin/go").exists())
    gocmd = "/usr/local/go/bin/go"

val ndkDir = android.ndkDirectory
val ndkHostTag = if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
    if (System.getProperty("os.arch") == "aarch64" || System.getProperty("os.arch") == "arm64") "darwin-x86_64" else "darwin-x86_64"
} else {
    "linux-x86_64"
}
val ndkToolchainBin = File(ndkDir, "toolchains/llvm/prebuilt/${ndkHostTag}/bin")

singboxTargets.forEach { (abi, target) ->
    tasks.register<Exec>("buildSingBox_${abi}") {
        val outputDir = file("${singboxOutputDir}/${abi}")
        val outputFile = file("${outputDir}/libsingbox.so")
        val cc = File(ndkToolchainBin, "${target.ccPrefix}-clang")

        workingDir = singboxSrcDir
        environment("GOOS", "android")
        environment("GOARCH", target.goarch)
        environment("CGO_ENABLED", "1")
        environment("CC", cc.absolutePath)
        commandLine(gocmd, "build",
            "-tags", "with_utls",
            "-o", outputFile.absolutePath,
            "-trimpath",
            "-ldflags", "-s -w",
            "./cmd/sing-box")

        doFirst {
            mkdir(outputDir)
        }

        inputs.files(file("${singboxSrcDir}/go.mod"), file("${singboxSrcDir}/go.sum"))
        inputs.dir(file("${singboxSrcDir}/cmd"))
        outputs.file(outputFile)
    }
}

tasks.register("buildSingBox") {
    description = "Build sing-box for all Android ABIs"
    singboxTargets.keys.forEach { abi ->
        dependsOn("buildSingBox_${abi}")
    }
}

// --- ydtun (Rust) cross-compilation for Android ---

val ydtunSrcDir = file(findProperty("ydtunSrcDir") ?: "src/main/rust/ydtun")
val ydtunOutputDir = file("${buildDir}/ydtun/jniLibs")

data class YdtunTarget(val rustTarget: String, val ccPrefix: String)
val allYdtunTargets = mapOf(
    "arm64-v8a"   to YdtunTarget("aarch64-linux-android", "aarch64-linux-android21"),
    "armeabi-v7a" to YdtunTarget("armv7-linux-androideabi", "armv7a-linux-androideabi21"),
    "x86_64"      to YdtunTarget("x86_64-linux-android", "x86_64-linux-android21"),
    "x86"         to YdtunTarget("i686-linux-android", "i686-linux-android21")
)
val ydtunTargets = allYdtunTargets.filterKeys { it in buildAbis }

val vpxLibDir = file(findProperty("vpxLibDir") ?: "src/main/rust/vpx-android")

ydtunTargets.forEach { (abi, target) ->
    tasks.register<Exec>("buildYdtun_${abi}") {
        val outputDir = file("${ydtunOutputDir}/${abi}")
        val outputFile = file("${outputDir}/libydtun.so")
        val cc = File(ndkToolchainBin, "${target.ccPrefix}-clang")
        val ar = File(ndkToolchainBin, "llvm-ar")
        val linkerEnvKey = "CARGO_TARGET_${target.rustTarget.uppercase().replace('-', '_')}_LINKER"

        workingDir = ydtunSrcDir
        environment("CC", cc.absolutePath)
        environment("AR", ar.absolutePath)
        environment(linkerEnvKey, cc.absolutePath)
        environment("VPX_LIB_DIR", file("${vpxLibDir}/${abi}").absolutePath)
        environment("VPX_STATIC", "1")
        commandLine("cargo", "build",
            "--target", target.rustTarget,
            "--release",
            "--no-default-features",
            "--features", "port-forward",
            "--bin", "ydtun")

        doLast {
            mkdir(outputDir)
            val builtBinary = file("${ydtunSrcDir}/target/${target.rustTarget}/release/ydtun")
            copy {
                from(builtBinary)
                into(outputDir)
                rename("ydtun", "libydtun.so")
            }
        }

        inputs.files(file("${ydtunSrcDir}/Cargo.toml"), file("${ydtunSrcDir}/Cargo.lock"))
        inputs.dir(file("${ydtunSrcDir}/src"))
        outputs.file(outputFile)
    }
}

tasks.register("buildYdtun") {
    description = "Build ydtun for all Android ABIs"
    ydtunTargets.keys.forEach { abi ->
        dependsOn("buildYdtun_${abi}")
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
        dependsOn("buildSingBox")
        dependsOn("buildYdtun")
    }
}


dependencies {
    // https://maven.google.com/web/index.html
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)

    uiImplementation(libs.android.view.material)
    uiImplementation(libs.androidx.activity)
    uiImplementation(libs.androidx.activity.ktx)
    uiImplementation(libs.androidx.appcompat)
    uiImplementation(libs.androidx.cardview)
    uiImplementation(libs.androidx.viewpager2)
    uiImplementation(libs.androidx.constraintlayout)
    uiImplementation(libs.androidx.core.ktx)
    uiImplementation(libs.androidx.fragment.ktx)
    uiImplementation(libs.androidx.lifecycle.runtime.ktx)
    uiImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    uiImplementation(libs.androidx.preference.ktx)
    uiImplementation(libs.androidx.recyclerview)
    uiImplementation(libs.androidx.security.crypto)
    uiImplementation(libs.androidx.webkit)
    uiImplementation(libs.kotlin)
    uiImplementation(libs.mpandroidchart)
    uiImplementation(libs.square.okhttp)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
}

fun DependencyHandler.uiImplementation(dependencyNotation: Any): Dependency? =
    add("uiImplementation", dependencyNotation)
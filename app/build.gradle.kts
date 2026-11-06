import java.util.Properties

plugins { id("com.android.application") }
val signingFile=rootProject.file(providers.gradleProperty("lokeySigningProperties").getOrElse(".local/signing/release.properties"))
val signing=Properties().apply { if(signingFile.isFile) signingFile.inputStream().use { load(it) } }
fun signingValue(name:String)=signing.getProperty(name)?.takeIf { it.isNotBlank() }
    ?: error("Missing $name in release signing properties")
val checkReleaseSigning=tasks.register("checkReleaseSigning") {
    doLast { check(signingFile.isFile) { "Release signing is not configured. Run python3 tools/setup-signing.py or set -PlokeySigningProperties=/path/to/release.properties." } }
}
tasks.matching { it.name=="preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }
val hexagonSdk=providers.gradleProperty("lokeyHexagonSdk").orNull
val hexagonOutput=layout.buildDirectory.dir("generated/hexagon/jniLibs")
val runtimeCredits=tasks.register<Copy>("prepareRuntimeCredits") {
    from(rootProject.file("runtimes.json"))
    into(layout.buildDirectory.dir("generated/runtimeCredits"))
}
val privacyAssets=layout.buildDirectory.dir("generated/notices")
val privacyPolicy=tasks.register("prepareAppNotices") {
    val source=rootProject.file("docs/PRIVACY.md")
    val license=rootProject.file("LICENSE")
    inputs.files(source,license);outputs.dir(privacyAssets)
    doLast {
        val output=privacyAssets.get().asFile.apply { mkdirs() }
        output.resolve("privacy-policy.txt").writeText(source.readText().replace(Regex("(?m)^#{1,6} +"),"").replace("`",""))
        val licenses=output.resolve("licenses").apply { mkdirs() }
        license.copyTo(licenses.resolve("lokey.txt"),overwrite=true)
    }
}
tasks.matching { it.name=="preBuild" }.configureEach { dependsOn(runtimeCredits,privacyPolicy) }
android {
    namespace = "org.lokey.app"
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/runtimeCredits").get().asFile)
    sourceSets["main"].assets.srcDir(privacyAssets.get().asFile)
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    providers.gradleProperty("lokeyNdkPath").orNull?.let { ndkPath = it }
    defaultConfig {
        applicationId = "org.lokey.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "org.lokey.app.KeyboardTest"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_JOB_POOLS=compile=4", "-DCMAKE_JOB_POOL_COMPILE=compile")
            providers.gradleProperty("lokeyRuntimeRoot").orNull?.let { arguments += "-DLOKEY_RUNTIME_ROOT=$it" }
            targets += "lokey"
            if(hexagonSdk!=null) {
                arguments += listOf("-DLOKEY_HEXAGON=ON","-DHEXAGON_SDK_ROOT=$hexagonSdk","-DPREBUILT_LIB_DIR=android_aarch64",
                    "-DLOKEY_HTP_OUTPUT=${hexagonOutput.get().asFile}/arm64-v8a")
                providers.gradleProperty("lokeyHexagonLibs").orNull?.let { arguments += "-DLOKEY_HTP_LIB_DIR=$it" }
            }
        } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    externalNativeBuild { cmake { path = file("src/main/c/CMakeLists.txt"); version = "3.31.6" } }
    signingConfigs {
        if(signingFile.isFile) create("release") {
            storeFile=rootProject.file(signingValue("storeFile"))
            storePassword=signingValue("storePassword")
            keyAlias=signingValue("keyAlias")
            keyPassword=signingValue("keyPassword")
            storeType="PKCS12"
        }
    }
    buildTypes { release {
        isMinifyEnabled = false
        if(signingFile.isFile) signingConfig=signingConfigs.getByName("release")
    } }
    if(hexagonSdk!=null) {
        sourceSets["main"].jniLibs.srcDir(hexagonOutput.get().asFile)
        packaging.jniLibs { useLegacyPackaging=true;keepDebugSymbols.add("**/libggml-htp-*.so") }
    }
}
if(hexagonSdk!=null) for(variant in listOf("Debug","Release")) {
    tasks.matching { it.name=="merge${variant}JniLibFolders" }.configureEach { dependsOn("externalNativeBuild$variant") }
}

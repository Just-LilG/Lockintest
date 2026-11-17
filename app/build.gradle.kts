import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.process.ExecOperations
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Reads google-services.json (next to this file) so Firebase knows which project this app belongs to.
    alias(libs.plugins.google.services)
}

// ---- Code-only updates (see README, "Code-only updates") ----------------------------------------------
// STABLE_API changes whenever something a code-only update can't change is edited: the manifest, resources,
// dependencies, this file, google-services.json, or the fixed host (src/.../dyn). A downloaded code update
// is only accepted by an app with the same STABLE_API. DYNAMIC_VERSION is the time of the newest source edit,
// so a code update built from newer sources is "newer".
val stableApi: Int = run {
    val md = MessageDigest.getInstance("SHA-256")
    val root = rootProject.projectDir
    val files = buildList {
        add(file("src/main/AndroidManifest.xml"))
        add(file("build.gradle.kts"))
        add(file("google-services.json"))
        add(File(root, "gradle/libs.versions.toml"))
        addAll(fileTree("src/main/res").files.sortedBy { it.path })
        addAll(fileTree("src/main/java/com/godbless/lockin/dyn").files.sortedBy { it.path })
    }
    files.filter { it.isFile }.forEach { f ->
        md.update(f.relativeTo(root).invariantSeparatorsPath.toByteArray())
        md.update(f.readText().replace("\r\n", "\n").toByteArray())
    }
    ByteBuffer.wrap(md.digest()).int and 0x7fffffff
}
val dynamicVersion: Long = (fileTree("src/main").files.maxOfOrNull { it.lastModified() } ?: 0L) / 1000L
// The public half of the signing key (made by scripts/dynamic-keygen.sh). Without it no code update is accepted.
val dynamicPublicKey: String = file("dynamic-public-key.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()

android {
    namespace = "com.godbless.lockin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.godbless.lockin"
        minSdk = 26
        targetSdk = 35
        versionCode = 76
        versionName = "0.28.2"

        // Phones only: x86 is just emulators and Chromebooks, so the APK skips it. (Remove this line to run on an x86 emulator.)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // Public client values: the project address and its publishable key. Row-level security protects the data.
        buildConfigField("String", "SUPABASE_URL", "\"https://huhhzfbmsdajkppllako.supabase.co\"")
        buildConfigField("int", "STABLE_API", stableApi.toString())
        buildConfigField("long", "DYNAMIC_VERSION", "${dynamicVersion}L")
        buildConfigField("String", "DYNAMIC_PUBKEY", "\"$dynamicPublicKey\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_QD4qv6JPerTWQY6bB3O5_g_MxOd3eU3\"")
    }

    // Release builds are signed with your own key when keystore.properties exists next to the root build
    // file (scripts/make-signing-key.sh makes both). Without it they fall back to the debug key, so the APK
    // still installs; but then it can only be updated by builds made on this same computer.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) FileInputStream(f).use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and optimises the code (Compose runs noticeably smoother). Names are kept (-dontobfuscate in
            // proguard-rules.pro) so a crash report in the app still reads. If a release build ever misbehaves,
            // set this back to false to rule R8 out.
            isMinifyEnabled = true
            // Drops resources nothing uses (smaller APK).
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    // Installs the ahead-of-time compile profile that ships inside Compose, so screens are smooth from the first launch.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Push notifications (Firebase Cloud Messaging). Only messaging is used, no analytics.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Pairing by QR code: ZXing draws the code, CameraX shows the camera, ML Kit reads the code.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}

// ---- Builds the code bundle's classes.dex: gradlew dynamicDexDebug (or dynamicDexRelease) ----
// Only the app's own classes go in. Libraries, resources (R) and the fixed host (dyn) stay in the installed app.
val dexTool: Configuration by configurations.creating
dependencies {
    dexTool("com.android.tools:r8:8.7.+")
}

abstract class DynamicDex @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val classDirs: ConfigurableFileCollection

    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:InputFiles
    abstract val platform: ConfigurableFileCollection

    @get:Classpath
    abstract val tool: ConfigurableFileCollection

    @get:Input
    abstract val version: Property<Long>

    @get:Input
    abstract val stable: Property<Int>

    @get:Input
    abstract val variantName: Property<String>

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outDir.get().asFile
        out.deleteRecursively()
        val dexDir = File(out, "dex").apply { mkdirs() }

        val seen = HashSet<String>()
        val classes = ArrayList<File>()
        for (dir in classDirs.files.filter { it.isDirectory }) {
            dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                val skip = rel.startsWith("com/godbless/lockin/dyn/") || rel == "com/godbless/lockin/R.class" ||
                    rel.startsWith("com/godbless/lockin/R$")
                if (!skip && seen.add(rel)) classes.add(f)
            }
        }
        check(classes.isNotEmpty()) { "No compiled classes found. Run the build first." }

        val d8Args = ArrayList<String>()
        d8Args += listOf("--release", "--min-api", "26", "--output", dexDir.path)
        platform.files.forEach { d8Args += listOf("--lib", it.path) }
        libraries.files.filter { it.exists() }.forEach { d8Args += listOf("--classpath", it.path) }
        classes.forEach { d8Args += it.path }
        exec.javaexec {
            classpath = tool
            mainClass.set("com.android.tools.r8.D8")
            setArgs(d8Args)
        }

        File(out, "info.properties").writeText(
            "version=${version.get()}\nstable=${stable.get()}\nvariant=${variantName.get()}\n",
        )
        println("Code bundle dex: ${File(dexDir, "classes.dex")} (${classes.size} classes)")
    }
}

extensions.getByType<ApplicationAndroidComponentsExtension>().apply {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val libs = configurations.getByName("${variant.name}CompileClasspath").incoming.artifactView {
            attributes.attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
        }.files
        tasks.register<DynamicDex>("dynamicDex$cap") {
            group = "lockin"
            description = "Builds the ${variant.name} code bundle's classes.dex for scripts/publish-dynamic.sh."
            dependsOn("compile${cap}Kotlin", "compile${cap}JavaWithJavac")
            classDirs.from(
                layout.buildDirectory.dir("tmp/kotlin-classes/${variant.name}"),
                layout.buildDirectory.dir("intermediates/javac/${variant.name}/compile${cap}JavaWithJavac/classes"),
                layout.buildDirectory.dir("intermediates/javac/${variant.name}/classes"),
            )
            libraries.from(libs)
            platform.from(sdkComponents.bootClasspath)
            tool.from(dexTool)
            version.set(dynamicVersion)
            stable.set(stableApi)
            variantName.set(variant.name)
            outDir.set(layout.buildDirectory.dir("dynamic/${variant.name}"))
        }
    }
}

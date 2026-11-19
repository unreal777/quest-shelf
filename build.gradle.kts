plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}

// Optional: -PbuildRoot=<short path> puts build output outside the project.
// Needed on Windows when the project sits in a deep folder (260-char path limit).
providers.gradleProperty("buildRoot").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(File(root, name))
    }
}

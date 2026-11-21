plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Keep the (large, throwaway) build output outside the source folder (e.g. a synced folder).
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.autotube-build/${project.name}"))
}

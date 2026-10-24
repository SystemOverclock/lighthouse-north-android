// Toolchain pinada = mesma já provada no monorepo (plugin embutido do
// client-flutter usa AGP 8.7.3 + Kotlin 2.1.0; wrapper 8.14 do example).
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

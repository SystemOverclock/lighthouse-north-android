// SDK Android nativo independente (codinome — nome de publicação Maven pendente
// do dono). Módulos: :client (a library publicável) e :example-app (app mínimo
// de integração — NÃO publicado).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lighthouse-north-android"

include(":client")
include(":example-app")
project(":example-app").projectDir = file("examples/android-app")

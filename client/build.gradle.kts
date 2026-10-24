plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lighthousenorth.clientandroid"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    // Firebase é dependência do APP HOSPEDEIRO, nunca do SDK (o SDK não embute
    // Firebase — decisão 3.2, paridade com o client-flutter). `compileOnly`
    // permite compilar contra a API; em runtime o NoClassDefFoundError é
    // mapeado para o erro tipado FirebaseNotConfiguredException.
    compileOnly("com.google.firebase:firebase-messaging:24.1.0")
    // Tasks.await usado para o token FCM — transitivo do firebase-messaging no host.
    compileOnly("com.google.android.gms:play-services-tasks:18.2.0")

    // JSON: org.json vem da PLATAFORMA Android (zero dependência em produção).
    // Nos testes JVM a documentação oficial do Android recomenda a artefato real:
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

// Publicação Maven Central (preparação — publica após criar conta Sonatype)
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.lighthousenorth"
                artifactId = "client-android"
                version = "0.2.0"
            }
        }
    }
}

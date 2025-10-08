val libraryVersion = "1.0.3"

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.dokka)
    `maven-publish`
    signing
}

android {
    namespace = "ai.pipecat.client.daily"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VERSION_NAME", "\"$libraryVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        targetSdk = 35
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    api(libs.daily.android.client)
    api(libs.pipecat.client)

    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    repositories {
        maven {
            url = rootProject.layout.buildDirectory.dir("PipecatLocalRepo").get().asFile.toURI()
            name = "PipecatLocalRepo"
        }
    }

    publications {
        register<MavenPublication>("release") {
            groupId = "ai.pipecat"
            artifactId = "daily-transport"
            version = libraryVersion

            pom {
                name.set("Pipecat Client Daily Transport")
                description.set("Daily Pipecat transport for Android")
                url.set("https://github.com/pipecat-ai/pipecat-client-android-transports")

                developers {
                    developer {
                        id.set("pipecat.ai")
                        name.set("pipecat.ai")
                    }
                }

                licenses {
                    license {
                        name.set("BSD 2-Clause License")
                        url.set("https://github.com/pipecat-ai/pipecat-client-android-transports/blob/main/LICENSE")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/pipecat-ai/pipecat-client-android-transports.git")
                    developerConnection.set("scm:git:ssh://github.com:pipecat-ai/pipecat-client-android-transports.git")
                    url.set("https://github.com/pipecat-ai/pipecat-client-android-transports")
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

signing {
    val signingKey = System.getenv("RTVI_GPG_SIGNING_KEY")
    val signingPassphrase = System.getenv("RTVI_GPG_SIGNING_PASSPHRASE")

    if (!signingKey.isNullOrEmpty() || !signingPassphrase.isNullOrEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassphrase)
        sign(publishing.publications)
    }
}
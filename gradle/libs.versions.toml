[versions]
agp = "8.4.0"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
appcompat = "1.6.1"
kotlin = "1.9.0"
coreKtx = "1.13.1"
documentfile = "1.0.1"
coroutines = "1.7.3"

# версии, добавленные ранее (recyclerview понижена для совместимости с compileSdk = 34)
recyclerview = "1.3.0"
markwon = "4.6.2"
# prism4j (bundler) удалён из version catalog чтобы избежать duplicate classes

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# RecyclerView - downgraded to 1.3.0 so it works with compileSdk = 34
recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }

# Markwon + syntax highlight (без изменения)
markwon-core = { group = "io.noties.markwon", name = "core", version.ref = "markwon" }
markwon-syntax = { group = "io.noties.markwon", name = "syntax-highlight", version.ref = "markwon" }

# prism4j-bundler intentionally removed to avoid duplicate-class conflicts.
# Если позже понадобится prism4j-bundler, лучше добавить его явно в app/build.gradle.kts
# с исключениями транзитивных артефактов, вызывающих коллизии.

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }

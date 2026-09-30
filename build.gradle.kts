plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

val formatter by configurations.creating { isTransitive=false }
dependencies { add(formatter.name,"com.facebook:ktfmt:0.54:jar-with-dependencies") }
tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    description = "Format Kotlin source with the Kotlin community style"
    classpath = formatter
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--kotlinlang-style")
    args(fileTree("app/src").matching { include("**/*.kt") }.files.sorted().map { it.absolutePath })
}

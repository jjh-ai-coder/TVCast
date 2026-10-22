// TVCast 共享 DLNA 协议栈：纯 Kotlin/JVM，无 Android 依赖。
// 接收端（:app，DMR 设备侧）与发送端（:sender，控制点 CP 侧）共用本模块。
plugins {
    // 版本继承自根构建 classpath（org.jetbrains.kotlin.android 2.2.21 已引入 kotlin 插件）
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

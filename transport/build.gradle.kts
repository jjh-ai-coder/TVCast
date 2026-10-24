// TVCast 屏幕镜像传输层：UDP 分片/重组 + NACK 重传 + 码率档位。纯 Kotlin/JVM，无 Android 依赖。
plugins {
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

// 镜像码流接收统计工具（模拟器/真机 E2E 调试用）
// 用法：gradlew :transport:mirrorRecv -PrecvPort=9420 -PrecvSecs=20
tasks.register<JavaExec>("mirrorRecv") {
    group = "mirror"
    description = "接收镜像码流并打印统计（端口/秒数见 -PrecvPort/-PrecvSecs）"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.tvcast.transport.MirrorStatsReceiver"
    if (project.hasProperty("recvPort")) args(project.property("recvPort") as String)
    if (project.hasProperty("recvSecs")) args(project.property("recvSecs") as String)
}

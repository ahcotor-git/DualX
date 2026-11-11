allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// Kotlin 增量编译缓存把源文件路径按「相对 android 目录」存储；来自 pub 缓存的插件
// 子工程源码位于该目录之外（本项目 PUB_CACHE 在 D 盘、工程在 E 盘），KGP 2.4 起
// 计算相对路径会抛 IllegalArgumentException（Could not close incremental caches）。
// 对工程目录之外的插件子工程关闭增量编译，:app 保持增量。
subprojects {
    if (!projectDir.canonicalPath.startsWith(rootDir.canonicalPath)) {
        extra["kotlin.incremental"] = "false"
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

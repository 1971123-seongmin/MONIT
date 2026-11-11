import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.withGroovyBuilder

// 기존 Compose compiler 설정으로 한 component family와 공통 theme만 컴파일한다.
// 앱 빌드에 적용하지 않으며 결과는 별도 build 디렉터리에 남긴다.
gradle.projectsEvaluated {
    // buildSrc 등 중첩 빌드에는 주 프로젝트의 검증 옵션을 요구하거나 적용하지 않는다.
    val module = rootProject.findProject(":core:designsystem") ?: return@projectsEvaluated
    val componentFamily = gradle.startParameter.projectProperties["monit.component"]
    require(componentFamily in setOf("button", "chip", "textfield")) {
        "-Pmonit.component=button|chip|textfield is required."
    }
    require(gradle.startParameter.taskNames == listOf(":core:designsystem:compileDebugKotlin")) {
        "Run only :core:designsystem:compileDebugKotlin with this init script."
    }
    require(gradle.startParameter.projectProperties["kotlin.incremental"] == "false") {
        "Use -Pkotlin.incremental=false to prevent reuse of peer component classes."
    }
    val verificationCache = module.layout.buildDirectory.dir("component-compilation/project-cache/$componentFamily").get().asFile.canonicalFile
    require(gradle.startParameter.projectCacheDir?.canonicalFile == verificationCache) {
        "Use --project-cache-dir core/designsystem/build/component-compilation/project-cache/$componentFamily."
    }
    val sourceRoot = module.file("src/main/kotlin/com/monit/core/designsystem")
    val componentSources = module.fileTree(sourceRoot.resolve("component/$componentFamily")) { include("**/*.kt") }
    require(componentSources.files.isNotEmpty()) { "No sources found for $componentFamily." }
    val selectedSources = module.files(
        componentSources,
        module.fileTree(sourceRoot.resolve("theme")) { include("**/*.kt") },
    )
    val output = module.layout.buildDirectory.dir("component-compilation/$componentFamily")
    require(output.get().asFile.toPath().startsWith(module.layout.buildDirectory.get().asFile.toPath()))
    module.tasks.named("compileDebugKotlin").configure {
        withGroovyBuilder {
            "setSource"(selectedSources)
            (getProperty("destinationDirectory") as DirectoryProperty).set(output)
        }
        outputs.cacheIf { false }
        doFirst {
            val libraries = withGroovyBuilder { getProperty("libraries") } as FileCollection
            val projectRoot = rootProject.projectDir.canonicalFile.toPath()
            val moduleBuild = module.layout.buildDirectory.get().asFile.canonicalFile.toPath()
            val projectArtifacts = libraries.files.filter { it.canonicalFile.toPath().startsWith(projectRoot) }
            require(projectArtifacts.all { it.name == "R.jar" && it.canonicalFile.toPath().startsWith(moduleBuild) }) {
                "Unexpected project classpath: $projectArtifacts"
            }
            logger.lifecycle("Independent $componentFamily compilation: ${selectedSources.files.size} sources; project classpath contains only resource symbols.")
        }
        doLast {
            val classes = output.get().asFile.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
            require(classes.isNotEmpty()) { "No class output for $componentFamily." }
            val allowedPackage = "com/monit/core/designsystem/component/$componentFamily/"
            require(classes.all {
                val path = it.relativeTo(output.get().asFile).invariantSeparatorsPath
                path.startsWith(allowedPackage) || path.startsWith("com/monit/core/designsystem/theme/")
            }) { "Peer component classes were included in $componentFamily output." }
            logger.lifecycle("Verified $componentFamily output: ${classes.size} classes, no peer component classes.")
        }
    }
}

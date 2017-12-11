import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

plugins {
	kotlin("jvm") version "1.1.51"
	application
}

java {
	sourceSets {
		"generated" {
			withConvention(KotlinSourceSet::class) {
				kotlin.srcDir("build/generated")

				compileClasspath += sourceSets["main"].output
				runtimeClasspath += sourceSets["main"].output
			}
		}
	}
}

configurations {
	"generatedCompile" {
		extendsFrom(configurations["compile"])
	}
	"generatedRuntime" {
		extendsFrom(configurations["runtime"])
	}
}

dependencies {
	compile(kotlin("stdlib"))
	compile("org.reflections:reflections:0.9.11")
	compile("org.membrane-soa:service-proxy-core:4.5.0")
	compile("com.squareup:kotlinpoet:0.6.0")
	compile("org.funktionale:funktionale-all:1.1")
}

repositories {
	jcenter()
}

application {
	mainClassName = "com.predic8.membrane.dsl.DslKt"
}

task<Jar>("jarGenerated") {
	group = "build"
	dependsOn("compileGeneratedKotlin")

	from(java.sourceSets["generated"].output)
	val (directories, jars) = configurations["generatedCompile"].partition(File::isDirectory)
	from(directories + jars.map(project::zipTree))
}

tasks["compileGeneratedKotlin"].dependsOn("run")

defaultTasks("jarGenerated")
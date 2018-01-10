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

				val main by sourceSets

				compileClasspath += main.output
				runtimeClasspath += main.output
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
	compile("com.squareup:kotlinpoet:0.7.0-SNAPSHOT")
	compile("org.funktionale:funktionale-all:1.1")
	testCompile("io.kotlintest:kotlintest:2.0.7")
}

repositories {
	jcenter()
	// maven("https://oss.sonatype.org/content/repositories/snapshots")
	mavenLocal()
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

task<Wrapper>("wrapper") {
	gradleVersion = "4.4"
}

tasks["compileGeneratedKotlin"].dependsOn("run")

defaultTasks("jarGenerated")
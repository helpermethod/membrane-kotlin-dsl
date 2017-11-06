plugins {
	kotlin("jvm") version "1.1.51"
	application
}

application {
	mainClassName = "com.predic8.membrane.dsl.DslKt"
}

dependencies {
	compile(kotlin("stdlib"))
	compile("org.reflections:reflections:0.9.11")
	compile("org.membrane-soa:service-proxy-core:4.5.0")
	compile("com.squareup:kotlinpoet:0.6.0-SNAPSHOT")
	compile("org.funktionale:funktionale-all:1.1")
}

repositories {
	mavenLocal()
	jcenter()
}
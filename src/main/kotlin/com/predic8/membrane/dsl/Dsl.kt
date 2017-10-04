package com.predic8.membrane.dsl

import com.predic8.membrane.annot.MCElement
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import org.funktionale.composition.andThen
import org.funktionale.partials.partially1
import org.reflections.Reflections
import java.nio.file.Paths

val generate = {
	val reflections = Reflections()

	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.map(generateParts.partially1(reflections) andThen ::generateType)
		.forEach(::generateKotlinFile)
}

val generateParts = { reflections: Reflections, type: Class<*> ->
	Parts("${type.simpleName}Spec", generateConstructor(type), generateFunctions(type, reflections))
}

fun generateConstructor(type: Class<*>) =
	FunSpec
		.constructorBuilder()
		.addParameter(type.simpleName.decapitalize(), type)
		.build()

fun generateFunctions(type: Class<*>, reflections: Reflections): List<FunSpec> {
	TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
}

fun generateType(parts: Parts) =
	TypeSpec
		.classBuilder(parts.name)
		.primaryConstructor(parts.constructor)
		.build()

fun generateKotlinFile(type: TypeSpec) =
	FileSpec.builder("com.predic8.membrane.dsl", type.name as String)
		.addType(type)
		.indent(" ".repeat(4))
		.build()
		.writeTo(Paths.get("build/generated"))

fun main(args: Array<String>) {
	generate()
}
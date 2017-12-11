package com.predic8.membrane.dsl

import com.predic8.membrane.annot.MCAttribute
import com.predic8.membrane.annot.MCChildElement
import com.predic8.membrane.annot.MCElement
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import org.funktionale.composition.andThen
import org.funktionale.partials.partially1
import org.reflections.ReflectionUtils.getAllMethods
import org.reflections.ReflectionUtils.withAnnotation
import org.reflections.Reflections
import org.springframework.beans.factory.annotation.Required
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Paths

data class Parts(val name: String, val constructor: FunSpec, val functions: List<FunSpec>)

fun generate(reflections: Reflections = Reflections()) {
	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.forEach(::generateParts.partially1(reflections) andThen generateClass andThen ::writeKotlinFile)
}

fun generateParts(reflections: Reflections, type: Class<*>) =
	Parts("${type.simpleName}Spec", generateConstructor(type), generateFuns(reflections, type))

fun generateConstructor(type: Class<*>) =
	FunSpec
		.constructorBuilder()
		.addParameter(toCamelCase(type.simpleName), type)
		.build()

fun generateFuns(reflections: Reflections, type: Class<*>) =
	getAllMethods(type, withAnnotation(MCChildElement::class.java))
		.flatMap({ child: Method -> child.parameters.first().parameterizedType } andThen ::determineParameterType andThen ::generateSubtypeFuns.partially1(reflections))

fun determineParameterType(parametrizedType: Type): Type =
	when (parametrizedType) {
		is ParameterizedType -> parametrizedType.actualTypeArguments.first()
		else -> parametrizedType
	}

fun generateSubtypeFuns(reflections: Reflections, type: Type): List<FunSpec> {
	val subTypes = reflections
		.getSubTypesOf(type as Class<*>)
		.filter { it.isAnnotationPresent(MCElement::class.java) }

	return when {
		subTypes.isEmpty() -> listOf(generateFun(type))
		else -> subTypes.map(::generateFun)
	}
}

fun generateFun(subType: Class<*>): FunSpec {
	val (reqAttributes, _) = subType
		.methods
		.filter { it.isAnnotationPresent(MCAttribute::class.java) }
		.partition { it.isAnnotationPresent(Required::class.java) }

	return FunSpec
		.builder(subType.getAnnotation(MCElement::class.java).name)
		.addParameters(reqAttributes.map(::createParameter))
		.build()
}

fun createParameter(attribute: Method) =
	ParameterSpec
		.builder(sanitize(attribute.name), attribute.parameters.first().type)
		.build()

fun toCamelCase(s: String): String {
	val i = s.indexOfFirst { it in 'a'..'z' }

	return when(i) {
		1 -> s[0].toLowerCase() + s.substring(1)
		else -> s.slice(0 until i - 1).toLowerCase() + s.slice(i - 1..s.lastIndex)
	}
}

val sanitize = { s: String -> s.removePrefix("set") } andThen { s: String -> s.decapitalize() }

val generateClass = { (name, constructor, functions): Parts ->
	TypeSpec
		.classBuilder(name)
		.primaryConstructor(constructor)
		.addFunctions(functions)
		.build()
}

fun writeKotlinFile(type: TypeSpec) =
	FileSpec
		.builder("com.predic8.membrane.dsl", type.name as String)
		.addType(type)
		.indent(" ".repeat(4))
		.build()
		.writeTo(Paths.get("build/generated"))

fun main(args: Array<String>) {
	generate()
}
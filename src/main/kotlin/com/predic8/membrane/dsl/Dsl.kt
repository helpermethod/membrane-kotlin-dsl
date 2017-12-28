package com.predic8.membrane.dsl

import com.predic8.membrane.annot.MCAttribute
import com.predic8.membrane.annot.MCChildElement
import com.predic8.membrane.annot.MCElement
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
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
data class Attribute(val methodName: String, val parameter: ParameterSpec)

fun generate(reflections: Reflections = Reflections()) {
	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.forEach(::generateParts.partially1(reflections) andThen generateClass andThen ::writeKotlinFile)
}

fun generateParts(reflections: Reflections, type: Class<*>): Parts {
	val field = ParameterSpec
		.builder(type.simpleName.toCamelCase(), type)
		.build()

	return Parts("${type.simpleName}Spec", generateConstructor(field), generateFuns(reflections, type))
}

fun generateConstructor(parameter: ParameterSpec) =
	FunSpec
		.constructorBuilder()
		.addParameter(parameter)
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

	return if (subTypes.isEmpty()) listOf(generateFun(type)) else subTypes.map(::generateFun)
}

fun generateFun(subType: Class<*>): FunSpec {
	val (reqAttributes, optAttributes) = subType
		.methods
		.filter { it.isAnnotationPresent(MCAttribute::class.java) }
		.partition { it.isAnnotationPresent(Required::class.java) }

	val attrs = reqAttributes.map { Attribute(it.name, createParameter(subType, it.name, it.parameters.first().type)) } +
                optAttributes.map { Attribute(it.name, createParameter(subType, it.name, it.parameters.first().type, defaultValue = true)) }

	return with(FunSpec.builder(subType.getAnnotation(MCElement::class.java).name)) {
		addParameters(attrs.map { it.parameter })
		addParameter("init", LambdaTypeName.get(receiver = ClassName("com.predic8.membrane.dsl", "${subType.simpleName}Spec"), returnType = Unit::class.asTypeName()))
		addStatement("val %N = %T()", subType.simpleName.decapitalize(), subType)
		attrs.forEach {
			addStatement("%N.%N(%N)", subType.simpleName.decapitalize(), it.methodName, it.parameter)
		}
		addStatement("%NSpec(type).init()", subType.simpleName)
		build()
	}
}

fun createParameter(type: Type, name: String, parameterType: Type, defaultValue: Boolean = false): ParameterSpec {
	val propertyName = sanitize(name)

	return ParameterSpec
		.builder(propertyName, parameterType)
		.apply {
			println("type: $type, propertyName: $propertyName")
			if (defaultValue) {
				defaultValue("%L", (type as Class<*>)
					.getDeclaredField(propertyName)
					.apply { isAccessible = true }
					.get(type.newInstance()))
			}
		}
		.build()
}

// TODO tailrec
fun String.toCamelCase(): String {
	val indexOfLastConsecutiveUppercaseLetter = this.indexOfFirst { it in 'a'..'z' } - 1

	return when (indexOfLastConsecutiveUppercaseLetter) {
		0 -> this.decapitalize()
		else -> this.slice(0 until indexOfLastConsecutiveUppercaseLetter).toLowerCase() + this.slice(indexOfLastConsecutiveUppercaseLetter..this.lastIndex)
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
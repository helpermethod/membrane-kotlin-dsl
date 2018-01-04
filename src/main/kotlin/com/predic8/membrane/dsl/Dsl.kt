package com.predic8.membrane.dsl

import com.predic8.membrane.annot.MCAttribute
import com.predic8.membrane.annot.MCChildElement
import com.predic8.membrane.annot.MCElement
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import org.funktionale.composition.andThen
import org.funktionale.either.eitherTry
import org.funktionale.partials.invoke
import org.reflections.ReflectionUtils.getAllMethods
import org.reflections.ReflectionUtils.withAnnotation
import org.reflections.ReflectionUtils.withName
import org.reflections.Reflections
import org.springframework.beans.factory.annotation.Required
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Paths

data class Parts(val name: String, val constructor: FunSpec, val property: PropertySpec, val functions: List<FunSpec>)

fun generate(reflections: Reflections = Reflections()) {
	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.forEach((::generateParts)(p1 = reflections) andThen generateClass andThen ::writeKotlinFile)
}

fun generateParts(reflections: Reflections, type: Class<*>) =
	Parts("${type.simpleName}Spec", generateFullConstructor(type), generateProperty(type), generateFuns(reflections, type))

fun generateProperty(type: Class<*>) =
	PropertySpec.builder(type.simpleName.toCamelCase(), type)
		.initializer(type.simpleName.toCamelCase())
		.build()

fun generateField(type: Class<*>) =
	ParameterSpec
		.builder(type.simpleName.toCamelCase(), type)
		.build()

fun generateConstructor(parameter: ParameterSpec) =
	FunSpec
		.constructorBuilder()
		.addParameter(parameter)
		.build()

val generateFullConstructor = ::generateField andThen ::generateConstructor

fun generateFuns(reflections: Reflections, type: Class<*>) =
	getAllMethods(type, withAnnotation(MCChildElement::class.java))
		.flatMap({ child: Method -> child.parameters.first().parameterizedType } andThen ::determineParameterType andThen (::generateSubtypeFuns)(p1 = reflections))

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
	val attrs = generateAttrs(subType)
	val subTypeName = subType.simpleName.decapitalize()

	return with(FunSpec.builder(subType.getAnnotation(MCElement::class.java).name)) {
		addParameters(attrs)
		addParameter("init", LambdaTypeName.get(receiver = ClassName("com.predic8.membrane.dsl", "${subType.simpleName}Spec"), returnType = Unit::class.asTypeName()))
		addStatement("val %N = %T()", subTypeName, subType)
		attrs.forEach {
			addStatement("%N.%N = %N", subTypeName, it.name, it)
		}
		addStatement("%NSpec(%N).init()", subType.simpleName, subTypeName)
		build()
	}
}

private fun generateAttrs(subType: Class<*>): List<ParameterSpec> {
	val (reqAttributes, optAttributes) = subType
		.methods
		.filter { it.isAnnotationPresent(MCAttribute::class.java) }
		.partition { it.isAnnotationPresent(Required::class.java) }

	return reqAttributes.map { generateParameter(subType, it.name, it.parameters.first().type) } +
		optAttributes.map { generateParameter(subType, it.name, it.parameters.first().type, defaultValue = true) }
}

fun generateParameter(type: Type, name: String, parameterType: Type, defaultValue: Boolean = false): ParameterSpec {
	// is or get
	val getterName = "${determinePrefix(type.asClassName())}${name.removePrefix("set")}"

	return ParameterSpec
		// TODO propertyName
		.builder(getterName.removePrefix("get").decapitalize(), convertType(parameterType))
		.apply {
			if (defaultValue) {
				// TODO when (type) is Boolean
				defaultValue("%L", invokeGetter(type, getterName))
			}
		}
		.build()
}

fun determinePrefix(type: ClassName) =
	when (type.simpleName()) {
		"boolean" -> "is"
		else -> "get"
	}

fun Type.asClassName() = asTypeName() as ClassName

val convertType = Type::asClassName andThen ::convertToKotlinType

fun convertToKotlinType(className: ClassName): ClassName =
	when (className.simpleName()) {
		"boolean" -> ClassName("kotlin", "Boolean")
		"String" -> ClassName("kotlin", "String").asNullable()
		else -> className
	}

fun invokeGetter(type: Type, getterName: String) =
	tryInvokeGetter(type, getterName).fold({ _ -> null }) { v -> v }

fun tryInvokeGetter(type: Type, getterName: String) =
	eitherTry {
		getAllMethods(type as Class<*>, withName(getterName))
			.first()
			.invoke(type.newInstance())
	}

fun String.toCamelCase(): String {
	val indexOfLastConsecutiveUppercaseLetter = this.indexOfFirst { it in 'a'..'z' } - 1

	return when (indexOfLastConsecutiveUppercaseLetter) {
		0 -> this.decapitalize()
		else -> this.slice(0 until indexOfLastConsecutiveUppercaseLetter).toLowerCase() + this.slice(indexOfLastConsecutiveUppercaseLetter..this.lastIndex)
	}
}

val generateClass = { (name, constructor, property, functions): Parts ->
	TypeSpec
		.classBuilder(name)
		.primaryConstructor(constructor)
		.addProperty(property)
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
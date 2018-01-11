package com.predic8.membrane.dsl

import com.predic8.membrane.annot.MCAttribute
import com.predic8.membrane.annot.MCChildElement
import com.predic8.membrane.annot.MCElement
import com.predic8.membrane.annot.MCTextContent
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import org.funktionale.composition.andThen
import org.funktionale.either.eitherTry
import org.funktionale.partials.invoke
import org.reflections.ReflectionUtils.*
import org.reflections.Reflections
import org.springframework.beans.factory.annotation.Required
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Paths

data class Parts(val name: String, val constructor: FunSpec, val property: PropertySpec, val functions: List<FunSpec>)
data class TypeDesc(val pattern: String, val methodName: String, val type: Type)

fun generate(reflections: Reflections = Reflections()) {
	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.forEach((::generateParts)(reflections) andThen generateClass andThen ::writeKotlinFile)
	System.exit(0)
}

fun generateParts(reflections: Reflections, type: java.lang.Class<*>): Parts {
	val field = generateField(type)

	return Parts(
		"${type.enclosingClass?.simpleName ?: ""}${type.simpleName}Spec",
		generateConstructor(field),
		generateProperty(field.name, field.type),
		generateFuns(reflections, type, field)
	)
}

fun generateProperty(name: String, type: TypeName) =
	PropertySpec.builder(name, type)
		.initializer(name)
		.build()

fun generateField(type: java.lang.Class<*>) =
	ParameterSpec
		.builder(type.simpleName.toCamelCase(), type)
		.build()

fun generateConstructor(parameter: ParameterSpec) =
	FunSpec
		.constructorBuilder()
		.addParameter(parameter)
		.build()

fun generateFuns(reflections: Reflections, type: java.lang.Class<*>, field: ParameterSpec) =
	getAllMethods(type, withAnnotation(MCChildElement::class.java))
		.flatMap(::determineParameterDesc andThen (::generateSubtypeFuns)(reflections)(field))

fun determineParameterDesc(method: Method): TypeDesc {
	val parameterizedType = method.parameters.first().parameterizedType

	return when (parameterizedType) {
		is ParameterizedType -> TypeDesc("%N.%N.add(%N)", method.name, parameterizedType.actualTypeArguments.first())
		else -> TypeDesc("%N.%N = %N", method.name, parameterizedType)
	}
}

fun generateSubtypeFuns(reflections: Reflections, field: ParameterSpec, typeDesc: TypeDesc): List<FunSpec> {
	val (pattern, methodName, type) = typeDesc
	val subTypes = reflections
		.getSubTypesOf(type as java.lang.Class<*>)
		.filter { it.isAnnotationPresent(MCElement::class.java) }

	return if (subTypes.isEmpty()) listOf(generateFun(field, methodName, pattern, type)) else subTypes.map((::generateFun)(field)(methodName)(pattern))
}

fun generateFun(field: ParameterSpec, methodName: String, pattern: String, type: Type): FunSpec {
	val (reqAttrs, optAttrs) = collectAttrs(type as java.lang.Class<*>)
	val subTypeName = type.simpleName.toCamelCase()
	val attrs = reqAttrs.map { it.name to generateParameter(type, it.name, it.parameters.first().type) } +
		optAttrs.map { it.name to generateParameter(type, it.name, it.parameters.first().type, defaultValue = true) }

	return FunSpec.builder(type.getAnnotation(MCElement::class.java).name).run {
		addParameters(attrs.map { (_, parameter) -> parameter })
		val hasChildren = type.methods.any { it.isAnnotationPresent(MCChildElement::class.java) }
		if (hasChildren) {
			addParameter(
				ParameterSpec.builder("init", LambdaTypeName.get(receiver = ClassName("com.predic8.membrane.dsl", "${type.enclosingClass?.simpleName ?: ""}${type.simpleName}Spec"), returnType = Unit::class.asTypeName()))
					.defaultValue("{}")
					.build()
			)
		}
		beginControlFlow("val %N = %T().apply", subTypeName, type)
		attrs.forEach { (methodName, parameter) ->
			addStatement("%N(%N)", methodName, parameter)
		}
		endControlFlow()
		if (hasChildren) {
			val enclosingClass = type.enclosingClass
			when {
				enclosingClass != null -> addStatement("%N%NSpec(%N).init()", type.enclosingClass.simpleName, type.simpleName, subTypeName)
				else -> addStatement("%NSpec(%N).init()", type.simpleName, subTypeName)
			}
		}
		addStatement(pattern, field, methodName.removePrefix("set").decapitalize(), subTypeName)
		build()
	}
}

fun collectAttrs(type: java.lang.Class<*>): Pair<List<Method>, List<Method>> {
	val (reqAttributes, optAttributes) = type
		.methods
		.filter { it.isAnnotationPresent(MCAttribute::class.java) }
		.partition { it.isAnnotationPresent(Required::class.java) }
	val textContents = type
		.methods
		.filter { it.isAnnotationPresent(MCTextContent::class.java) }

	// treat @TextContent as a required attribute
	return (reqAttributes + textContents) to optAttributes
}

fun generateParameter(type: Type, name: String, parameterType: Type, defaultValue: Boolean = false): ParameterSpec {
	val getterName = "${determinePrefix((parameterType as java.lang.Class<*>).simpleName)}${name.removePrefix("set")}"
	val propertyName = getterName.removePrefix("get").decapitalize()
	val default = when {
		(type as Class<*>).methods.find { it.name == getterName } != null -> invokeGetter(type, getterName)
		else -> invokeField(type, name.removePrefix("set").decapitalize())
	}

	return ParameterSpec
		.builder(propertyName, convertType(parameterType))
		.apply {
			if (defaultValue) {
				when (default) {
					is Boolean, is Int, is Long -> defaultValue("%L", default)
					is Enum<*> -> defaultValue("%T.%L", default::class, default.name)
					else -> defaultValue("%S", default)
				}
			}
		}
		.build()
}

fun determinePrefix(simpleName: String) =
	when (simpleName) {
		"boolean" -> "is"
		else -> "get"
	}

val convertType = Type::asClassName andThen ::convertToKotlinType

fun Type.asClassName() = asTypeName() as ClassName

fun convertToKotlinType(className: ClassName): ClassName =
	when (className.simpleName()) {
		"boolean" -> ClassName("kotlin", "Boolean")
		"String" -> ClassName("kotlin", "String").asNullable()
		"Int", "Boolean", "Long" -> className
		else -> className.asNullable()
	}

fun invokeField(type: Type, fieldName: String) =
	tryInvokeField(type, fieldName)
		.fold({ _ -> null }) { v -> v }

fun tryInvokeField(type: Type, fieldName: String) =
	eitherTry {
		(type as Class<*>)
			.getDeclaredField(fieldName)
			.apply { isAccessible = true }
			.get(type.newInstance())
	}

fun invokeGetter(type: Type, getterName: String) =
	tryInvokeGetter(type, getterName)
		.fold({ _ -> null }) { v -> v }

fun tryInvokeGetter(type: Type, getterName: String) =
	eitherTry {
		(type as Class<*>)
			.getMethod(getterName)
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
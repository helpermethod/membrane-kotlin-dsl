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
data class TypeDesc(val pattern: String, val methodName: String, val type: Type)

fun generate(reflections: Reflections = Reflections()) {
	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.forEach((::generateParts)(reflections) andThen generateClass andThen ::writeKotlinFile)
}

fun generateParts(reflections: Reflections, type: Class<*>): Parts {
	val field = generateField(type)

	return Parts(
		"${type.simpleName}Spec",
		generateConstructor(field),
		generateProperty(field.name, field.type),
		generateFuns(reflections, type, field)
	)
}

fun generateProperty(name: String, type: TypeName) =
	PropertySpec.builder(name, type)
		.initializer(name)
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

fun generateFuns(reflections: Reflections, type: Class<*>, field: ParameterSpec) =
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
		.getSubTypesOf(type as Class<*>)
		.filter { it.isAnnotationPresent(MCElement::class.java) }

	return if (subTypes.isEmpty()) listOf(generateFun(field, methodName, pattern, type)) else subTypes.map((::generateFun)(field)(methodName)(pattern))
}

fun generateFun(field: ParameterSpec, methodName: String, pattern: String, type: Type): FunSpec {
	val attrs = generateAttrs(type as Class<*>)
	val subTypeName = type.simpleName.decapitalize()

	return FunSpec.builder(type.getAnnotation(MCElement::class.java).name).run {
		addParameters(attrs)
		val hasChildren = type.methods.any { it.isAnnotationPresent(MCChildElement::class.java) }
		if (hasChildren) {
			addParameter("init", LambdaTypeName.get(receiver = ClassName("com.predic8.membrane.dsl", "${type.simpleName}Spec"), returnType = Unit::class.asTypeName()))
		}
		if (type.methods.any { it.isAnnotationPresent(MCChildElement::class.java) }) {
			addParameter("init", LambdaTypeName.get(receiver = ClassName("com.predic8.membrane.dsl", "${type.simpleName}Spec"), returnType = Unit::class.asTypeName()))
		}
		addStatement("val %N = %T()", subTypeName, type)
		attrs.forEach {
			addStatement("%N.%N = %N", subTypeName, it.name, it)
		}
		addStatement("%NSpec(%N).init()", type.simpleName, subTypeName)
		// pattern, methodname
		addStatement(pattern, field, methodName.removePrefix("set").decapitalize(), subTypeName)
		build()
	}
}

fun generateAttrs(subType: Class<*>): List<ParameterSpec> {
	val (reqAttributes, optAttributes) = subType
		.methods
		.filter { it.isAnnotationPresent(MCAttribute::class.java) }
		.partition { it.isAnnotationPresent(Required::class.java) }
	val textContents = subType
		.methods
		.filter { it.isAnnotationPresent(MCTextContent::class.java) }

	return (reqAttributes + textContents).map { generateParameter(subType, it.name, it.parameters.first().type) } +
		optAttributes.map { generateParameter(subType, it.name, it.parameters.first().type, defaultValue = true) }
}

fun generateParameter(type: Type, name: String, parameterType: Type, defaultValue: Boolean = false): ParameterSpec {
	val getterName = "${determinePrefix((parameterType as Class<*>).simpleName)}${name.removePrefix("set")}"
	val propertyName = getterName.removePrefix("get").decapitalize()
	val default = invokeGetter(type, getterName)

	return ParameterSpec
		.builder(propertyName, convertType(parameterType))
		.apply {
			if (defaultValue) {
				val placeholder = when (default) {
					is Boolean, is Int, is Long -> "%L"
					else -> "%S"
				}
				defaultValue(placeholder, default)
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
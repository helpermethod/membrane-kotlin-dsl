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
import java.lang.reflect.ParameterizedType
import java.nio.file.Paths

data class Parts(val name: String, val constructor: FunSpec, val functions: List<FunSpec>)

val generate = {
	val reflections = Reflections()

	reflections
		.getTypesAnnotatedWith(MCElement::class.java)
		.map(generateParts.partially1(reflections) andThen generateClass)
		.forEach(writeKotlinFile)
}

val generateParts = { reflections: Reflections, type: Class<*> ->
	Parts("${type.simpleName}Spec", generateConstructor(type), generateFuns(reflections, type))
}

val generateConstructor = { type: Class<*> ->
	FunSpec
		.constructorBuilder()
		.addParameter(type.simpleName.decapitalize(), type)
		.build()
}

val generateFuns = { reflections: Reflections, type: Class<*> ->
	getAllMethods(type, withAnnotation(MCChildElement::class.java)).flatMap { child ->
		val parameter = child.parameters.first()
		val parametrizedType = parameter.parameterizedType

        when(parametrizedType) {
			is ParameterizedType -> generateSubtypeFuns(parametrizedType, reflections)
	        else -> listOf()
		}
	}
}

private fun generateSubtypeFuns(parametrizedType: ParameterizedType, reflections: Reflections): List<FunSpec> {
	val (parameterType, _) = parametrizedType.actualTypeArguments
	val subTypes = reflections.getSubTypesOf(parameterType as Class<*>).filter { it.isAnnotationPresent(MCElement::class.java) }

	if (subTypes.isEmpty()) {
		// TODO
	}

	return subTypes.map { subType ->
		generateFun(subType)
	}
}

private fun generateFun(subType: Class<*>): FunSpec {
	val (reqAttributes, _) = subType.methods
		.filter { it.isAnnotationPresent(MCAttribute::class.java) }
		.partition { it.isAnnotationPresent(Required::class.java) }

	return FunSpec
		.builder(subType.getAnnotation(MCElement::class.java).name)
		.addParameters(reqAttributes.map { attribute ->
			ParameterSpec
				.builder(attribute.name.removePrefix("set"), attribute.parameters.first().type)
				.build()
		})
		.build()
}

val generateClass = { (name, constructor, functions): Parts ->
	TypeSpec
		.classBuilder(name)
		.primaryConstructor(constructor)
		.addFunctions(functions)
		.build()
}

val writeKotlinFile = { type: TypeSpec ->
	FileSpec
		.builder("com.predic8.membrane.dsl", type.name as String)
		.addType(type)
		.indent(" ".repeat(4))
		.build()
		.writeTo(Paths.get("build/generated"))
}

fun main(args: Array<String>) {
	generate()
}
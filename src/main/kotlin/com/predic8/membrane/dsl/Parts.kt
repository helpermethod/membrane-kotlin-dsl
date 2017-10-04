package com.predic8.membrane.dsl

import com.squareup.kotlinpoet.FunSpec

data class Parts(val name: String, val constructor: FunSpec, val functions: List<FunSpec>)
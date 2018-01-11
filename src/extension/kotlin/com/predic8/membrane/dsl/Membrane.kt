package com.predic8.membrane.dsl

import com.predic8.membrane.core.HttpRouter

fun router(init: RouterSpec.() -> Unit) {
	val router = HttpRouter().apply { isHotDeploy = false }
	RouterSpec(router).init()
	router.start()
}

/* <serviceProxy name="names" port="2000">
  <path isRegExp="true">/(rest)?names.*</path>
  <rewriter>
    <map from="^/names/(.*)" to="/restnames/name\.groovy\?name=$1" />
  </rewriter>
  <target host="thomas-bayer.com" port="80" />
</serviceProxy>
*/

fun main(args: Array<String>) {
	router {
		serviceProxy(name = "names", port = 2000) {
			path("/(restnames)?names.*", isRegExp = true)
			rewriter {
				map(from = "^/names/(.*)", to = """/restnames/name\.groovy\?name=$1""" )
			}
			target(host = "thomas-bayer.com", port = 80)
		}
	}
}
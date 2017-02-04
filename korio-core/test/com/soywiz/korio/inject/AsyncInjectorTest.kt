package com.soywiz.korio.inject

import com.soywiz.korio.async.syncTest
import org.junit.Assert
import org.junit.Test

class AsyncInjectorTest {
	@Test
	fun testSimple() = syncTest {
		val inject = AsyncInjector()
		inject.map(10)
		Assert.assertEquals(10, inject.get<Int>())
	}

	@Test
	fun testAnnotation() = syncTest {
		annotation class Path(val path: String)

		@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
		@Prototype class B(
				val path: Path,
				val v: java.lang.Integer
		)

		@Singleton class A(
				@Path("mypath1") val b1: B,
				@Path("mypath2") val b2: B
		)

		val inject = AsyncInjector()
		inject.map(10)
		val a = inject.get<A>()
		Assert.assertEquals("mypath1", a.b1.path.path)
		Assert.assertEquals("mypath2", a.b2.path.path)
		Assert.assertEquals(10, a.b1.v)
		Assert.assertEquals(10, a.b2.v)
	}

	annotation class Path(val path: String)

	@AsyncFactoryClass(BitmapFontLoader::class) class BitmapFont(val path: String)

	class BitmapFontLoader(val path: Path) : AsyncFactory<BitmapFont> {
		override suspend fun create() = BitmapFont(path.path)
	}

	@Test
	fun testLoader() = syncTest {
		@Singleton
		class Demo(
				@Path("path/to/font") val font: BitmapFont
		)

		val inject = AsyncInjector()
		val demo = inject.get<Demo>()
		Assert.assertEquals("path/to/font", demo.font.path)
	}

	//@Inject lateinit var injector: AsyncInjector

	@Test
	fun testInjectAnnotation() = syncTest {
		var log = ""

		open class Base : AsyncDependency {
			@Inject lateinit private var injector: AsyncInjector

			override suspend fun init() {
				log += "Base.init<" + injector.get<Int>() + ">"
			}
		}

		@Singleton
		class Demo(
			val a: java.lang.Integer
		) : Base() {
			override suspend fun init() {
				super.init()
				log += "Demo.init<$a>"
			}
		}

		val inject = AsyncInjector()
		inject.map(10)
		val demo = inject.get<Demo>()
		Assert.assertEquals(10, demo.a)
	}

}

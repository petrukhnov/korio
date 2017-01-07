package com.soywiz.korio.vfs.jvm

import com.soywiz.korio.async.asyncFun
import com.soywiz.korio.async.asyncGenerate
import com.soywiz.korio.async.executeInWorker
import com.soywiz.korio.async.toEventLoop
import com.soywiz.korio.stream.AsyncStream
import com.soywiz.korio.stream.AsyncStreamBase
import com.soywiz.korio.stream.toAsyncStream
import com.soywiz.korio.vfs.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.coroutines.suspendCoroutine

class LocalVfsProviderJvm : LocalVfsProvider {
	override fun invoke(): Vfs = object : Vfs() {
		val that = this
		val baseAbsolutePath = ""
		override val absolutePath: String = baseAbsolutePath

		fun resolve(path: String) = VfsUtil.lightCombine(baseAbsolutePath, path)
		fun resolvePath(path: String) = Paths.get(resolve(path))
		fun resolveFile(path: String) = File(resolve(path))

		suspend override fun exec(path: String, cmdAndArgs: List<String>, handler: VfsProcessHandler): Int = executeInWorker {
			val p = Runtime.getRuntime().exec(cmdAndArgs.toTypedArray(), arrayOf<String>(), resolveFile(path))
			var closing = false
			while (true) {
				val o = p.inputStream.readAvailableChunk(readRest = closing)
				val e = p.errorStream.readAvailableChunk(readRest = closing)
				if (o.isNotEmpty()) handler.onOut(o)
				if (e.isNotEmpty()) handler.onErr(e)
				if (closing) break
				if (o.isEmpty() && e.isEmpty() && !p.isAliveJre7) {
					closing = true
					continue
				}
				Thread.sleep(1L)
			}
			p.waitFor()
			//handler.onCompleted(p.exitValue())
			p.exitValue()
		}

		private val Process.isAliveJre7: Boolean get() = try {
			exitValue()
			false
		} catch (e: IllegalThreadStateException) {
			true
		}

		private fun InputStream.readAvailableChunk(readRest: Boolean): ByteArray {
			val out = ByteArrayOutputStream()
			while (if (readRest) true else available() > 0) {
				val c = this.read()
				if (c < 0) break
				out.write(c)
			}
			return out.toByteArray()
		}

		private fun InputStreamReader.readAvailableChunk(i: InputStream, readRest: Boolean): String {
			val out = java.lang.StringBuilder()
			while (if (readRest) true else i.available() > 0) {
				val c = this.read()
				if (c < 0) break
				out.append(c.toChar())
			}
			return out.toString()
		}

		suspend override fun open(path: String, mode: VfsOpenMode): AsyncStream {
			val channel = AsynchronousFileChannel.open(resolvePath(path), *when (mode) {
				VfsOpenMode.READ -> arrayOf(StandardOpenOption.READ)
				VfsOpenMode.WRITE -> arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
				VfsOpenMode.APPEND -> arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
				VfsOpenMode.CREATE -> arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
				VfsOpenMode.CREATE_NEW -> arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
				VfsOpenMode.CREATE_OR_TRUNCATE -> arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
			})

			return object : AsyncStreamBase() {
				suspend override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = asyncFun {
					val bb = ByteBuffer.wrap(buffer, offset, len)
					completionHandler<Int> { channel.read(bb, position, Unit, it) }
				}

				suspend override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) = asyncFun {
					val bb = ByteBuffer.wrap(buffer, offset, len)
					val write = completionHandler<Int> { channel.write(bb, position, Unit, it) }
					Unit
				}

				suspend override fun setLength(value: Long): Unit = asyncFun { channel.truncate(value); Unit }
				suspend override fun getLength(): Long = channel.size()
				suspend override fun close() = channel.close()

				override fun toString(): String = "$that($path)"
			}.toAsyncStream()
		}

		suspend override fun setSize(path: String, size: Long): Unit = executeInWorker {
			val file = resolveFile(path)
			FileOutputStream(file, true).channel.use { outChan ->
				outChan.truncate(size)
			}
			Unit
		}

		suspend override fun stat(path: String): VfsStat = executeInWorker {
			val file = resolveFile(path)
			val fullpath = "$path/${file.name}"
			if (file.exists()) {
				createExistsStat(
					fullpath,
					isDirectory = file.isDirectory,
					size = file.length()
				)
			} else {
				createNonExistsStat(fullpath)
			}

		}

		suspend override fun list(path: String) = executeInWorker {
			asyncGenerate {
				for (path in Files.newDirectoryStream(resolvePath(path))) {
					val file = path.toFile()
					yield(VfsFile(that, file.absolutePath))
				}
			}
		}

		suspend override fun mkdir(path: String): Boolean = executeInWorker {
			resolveFile(path).mkdir()
		}

		suspend override fun delete(path: String): Boolean = executeInWorker {
			resolveFile(path).delete()
		}

		suspend override fun rename(src: String, dst: String): Boolean = executeInWorker {
			resolveFile(src).renameTo(resolveFile(dst))
		}

		inline suspend fun <T> completionHandler(crossinline callback: (CompletionHandler<T, Unit>) -> Unit) = suspendCoroutine<T> { c ->
			val cevent = c.toEventLoop()
			callback(object : CompletionHandler<T, Unit> {
				override fun completed(result: T, attachment: Unit?) = cevent.resume(result)
				override fun failed(exc: Throwable, attachment: Unit?) = cevent.resumeWithException(exc)
			})
		}

		override fun toString(): String = "LocalVfs"
	}
}

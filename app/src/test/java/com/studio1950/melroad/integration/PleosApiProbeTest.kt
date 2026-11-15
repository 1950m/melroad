package com.studio1950.melroad.integration

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * 설치된 Pleos SDK(클래스패스의 ai.pleos.playground.*)의 공개 클래스·생성자·메서드·필드 목록을
 * app/build/pleos-api.txt로 기록한다. 문서에서 확인하지 못한 이름을 추측하지 않고 실제 SDK에서 읽기 위한 도구다.
 */
class PleosApiProbeTest {

    @Test
    fun dumpPleosPublicApi() {
        val loader = javaClass.classLoader!!
        val classNames = sortedSetOf<String>()
        val classPath = System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
        for (entry in classPath) {
            val f = File(entry)
            if (f.isFile && f.name.endsWith(".jar")) {
                runCatching {
                    ZipFile(f).use { zip ->
                        zip.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("ai/pleos/playground/") && it.endsWith(".class") && !Regex("\\$\\d").containsMatchIn(it) }
                            .forEach { classNames.add(it.removeSuffix(".class").replace('/', '.')) }
                    }
                }
            } else if (f.isDirectory) {
                f.walkTopDown()
                    .filter { it.isFile && it.extension == "class" && !Regex("\\$\\d").containsMatchIn(it.name) }
                    .map { it.relativeTo(f).path.replace(File.separatorChar, '.').removeSuffix(".class") }
                    .filter { it.startsWith("ai.pleos.playground.") }
                    .forEach { classNames.add(it) }
            }
        }

        val out = StringBuilder()
        for (name in classNames) {
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (!Modifier.isPublic(cls.modifiers)) continue
            val kind = when {
                cls.isInterface -> "interface"
                cls.isEnum -> "enum"
                else -> "class"
            }
            out.append("\n").append(kind).append(' ').append(cls.name)
            cls.superclass?.takeIf { it != Any::class.java }?.let { out.append(" : ").append(it.name) }
            out.append('\n')
            runCatching { cls.constructors }.getOrDefault(emptyArray()).forEach { c ->
                out.append("  <init>(").append(c.parameterTypes.joinToString { it.simpleName }).append(")\n")
            }
            runCatching { cls.declaredFields }.getOrDefault(emptyArray())
                .filter { Modifier.isPublic(it.modifiers) }
                .forEach { field ->
                    out.append("  field ").append(field.type.simpleName).append(' ').append(field.name)
                    // 권한·서비스 이름 같은 상수 문자열은 값까지 기록해야 manifest에 정확히 선언할 수 있다.
                    if (Modifier.isStatic(field.modifiers) && field.type == String::class.java) {
                        runCatching { field.get(null) as? String }.getOrNull()?.let { out.append(" = \"").append(it).append('"') }
                    }
                    out.append('\n')
                }
            runCatching { cls.declaredMethods }.getOrDefault(emptyArray())
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .sortedBy { it.name }
                .forEach { m ->
                    out.append("  ").append(m.returnType.simpleName).append(' ').append(m.name)
                        .append('(').append(m.parameterTypes.joinToString { it.simpleName }).append(")\n")
                }
            if (cls.isEnum) {
                out.append("  values: ").append(cls.enumConstants?.joinToString() ?: "").append('\n')
            }
        }
        // 어떤 SDK 파일(모듈)에 들어 있는지: 컴파일 경로에 없고 실행 시에만 오는 클래스를 가려내는 데 쓴다.
        out.append("\n== code source\n")
        for (name in listOf(
            "ai.pleos.playground.location.fused.FusedLocation",
            "ai.pleos.playground.location.fused.model.LocationData",
            "ai.pleos.playground.location.fused.constant.ErrorCode",
            "ai.pleos.playground.location.fused.exception.LocationException",
            "ai.pleos.playground.location.fused.constant.PleosPermission",
        )) {
            val where = runCatching { Class.forName(name, false, loader).protectionDomain?.codeSource?.location?.path }.getOrNull()
            out.append(name).append(" <- ").append(where?.substringAfterLast("/caches/") ?: "?").append('\n')
        }
        val file = File("build/pleos-api.txt")
        file.parentFile?.mkdirs()
        file.writeText("classes=${classNames.size}\n$out")
        println("Pleos API classes: ${classNames.size} → ${file.absolutePath}")
        assertTrue("Pleos SDK 클래스가 클래스패스에 있어야 한다", classNames.isNotEmpty())
    }
}

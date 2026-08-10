package ai.worklog.framework.core

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JsonFiles {
    static Object read(File file, Object fallback = [:]) {
        if (!file.isFile()) {
            return cloneValue(fallback)
        }
        try {
            return new JsonSlurper().parse(file, 'UTF-8')
        } catch (Exception ignored) {
            return cloneValue(fallback)
        }
    }

    static void write(File file, Object value) {
        file.parentFile?.mkdirs()
        File temporary = File.createTempFile(".${file.name}.", '.tmp', file.parentFile)
        temporary.setText(
            JsonOutput.prettyPrint(JsonOutput.toJson(value)) + System.lineSeparator(),
            'UTF-8'
        )
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    static Object cloneValue(Object value) {
        new JsonSlurper().parseText(JsonOutput.toJson(value))
    }

    static Map deepMerge(Map base, Map override) {
        Map merged = new LinkedHashMap(base)
        override.each { key, value ->
            if (merged[key] instanceof Map && value instanceof Map) {
                merged[key] = deepMerge((Map) merged[key], (Map) value)
            } else {
                merged[key] = value
            }
        }
        merged
    }
}

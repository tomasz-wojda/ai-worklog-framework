package ai.worklog.framework.core

import java.util.regex.Pattern

class Redaction {
    final String redacted
    final Pattern keyPattern
    final List<Pattern> valuePatterns

    Redaction(File frameworkRoot) {
        Map rules = (Map) JsonFiles.read(
            new File(frameworkRoot, 'shared/redaction-patterns.json'),
            [
                redacted: '***REDACTED***',
                sensitive_key_pattern: '(token|password|secret|cookie|auth)',
                sensitive_value_patterns: []
            ]
        )
        redacted = rules.redacted.toString()
        keyPattern = Pattern.compile(rules.sensitive_key_pattern.toString(), Pattern.CASE_INSENSITIVE)
        valuePatterns = ((List) rules.sensitive_value_patterns).collect {
            Pattern.compile(it.toString())
        }
    }

    boolean sensitiveKey(String key) {
        keyPattern.matcher(key).find()
    }

    String redactValue(String value) {
        if (!value) {
            return value
        }
        if (value.length() <= 4) {
            return redacted
        }
        "${value.substring(0, 2)}...${value.substring(value.length() - 2)} (${value.length()} chars)"
    }

    String redactString(String value) {
        String output = value
        valuePatterns.each { output = it.matcher(output).replaceAll(redacted) }
        output
    }

    Object redact(Object value, int depth = 0) {
        if (depth > 10) {
            return value
        }
        if (value instanceof Map) {
            Map output = [:]
            value.each { key, item ->
                if (sensitiveKey(key.toString())) {
                    output[key] = item instanceof String ? redactValue(item.toString()) : redacted
                } else {
                    output[key] = redact(item, depth + 1)
                }
            }
            return output
        }
        if (value instanceof List) {
            return value.collect { redact(it, depth + 1) }
        }
        value instanceof String ? redactString(value.toString()) : value
    }
}

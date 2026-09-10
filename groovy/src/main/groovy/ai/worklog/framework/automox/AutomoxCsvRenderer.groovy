package ai.worklog.framework.automox

class AutomoxCsvRenderer {
    static String render(List<String> columns, List<Map> rows) {
        StringBuilder output = new StringBuilder()
        output.append(columns.collect { escapeField(it) }.join(',')).append(System.lineSeparator())
        rows.each { Map row ->
            output.append(columns.collect { escapeField(formatCell(row[it])) }.join(','))
                .append(System.lineSeparator())
        }
        output.toString()
    }

    private static String formatCell(Object value) {
        value == null ? '' : value.toString()
    }

    private static String escapeField(String value) {
        String safe = value ?: ''
        if (safe.startsWith('=') || safe.startsWith('+') || safe.startsWith('-') ||
            safe.startsWith('@') || safe.startsWith('\t') || safe.startsWith('\r')) {
            safe = "'${safe}"
        }
        if (safe.contains('"') || safe.contains(',') || safe.contains('\n') || safe.contains('\r')) {
            return '"' + safe.replace('"', '""') + '"'
        }
        safe
    }
}

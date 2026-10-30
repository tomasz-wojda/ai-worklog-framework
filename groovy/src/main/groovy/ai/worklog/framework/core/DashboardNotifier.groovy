package ai.worklog.framework.core

import groovy.json.JsonOutput

class DashboardNotifier {
    static void notifyChange(FrameworkPaths paths, String ticketKey) {
        if (System.getenv('AI_WORKLOG_DASHBOARD_NOTIFY') == '0') {
            return
        }
        try {
            Object info = JsonFiles.read(new File(paths.configDir, 'dashboard.json'), [:])
            if (!(info instanceof Map) || !info.port) {
                return
            }
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:${info.port}/events").openConnection()
            connection.requestMethod = 'POST'
            connection.connectTimeout = 200
            connection.readTimeout = 200
            connection.doOutput = true
            connection.setRequestProperty('Content-Type', 'application/json')
            connection.setRequestProperty('X-Ai-Worklog', '1')
            connection.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson([ticket_key: ticketKey, kind: 'state']) }
            connection.responseCode
            connection.disconnect()
        } catch (Exception ignored) {
        }
    }
}

package ai.worklog.framework.newrelic

class NewRelicTerraformRenderer {
    static Map<String, String> render(Map dashboard, String accountId, String resourceName = 'exported_dashboard') {
        String safeResource = sanitizeResourceName(resourceName)
        String dashboardName = dashboard.name?.toString() ?: 'Exported Dashboard'
        String description = dashboard.description?.toString() ?: ''
        [
            'provider.tf': providerTf(),
            'variables.tf': variablesTf(accountId, dashboardName),
            'terraform.tfvars.example': tfvarsExample(accountId, dashboardName),
            'dashboard.tf': dashboardTf(dashboard, accountId, safeResource, dashboardName, description)
        ]
    }

    static String providerTf() {
        '''terraform {
  required_providers {
    newrelic = {
      source  = "newrelic/newrelic"
      version = "~> 3.0"
    }
  }
}

provider "newrelic" {
  api_key    = var.newrelic_api_key
  account_id = var.newrelic_account_id
}
'''
    }

    static String variablesTf(String accountId, String dashboardName) {
        """variable "newrelic_api_key" {
  description = "New Relic API Key"
  type        = string
  sensitive   = true
}

variable "newrelic_account_id" {
  description = "New Relic Account ID"
  type        = number
  default     = ${accountId ?: 0}
}

variable "dashboard_name" {
  description = "Dashboard name"
  type        = string
  default     = ${escapeHcl(dashboardName)}
}
"""
    }

    static String tfvarsExample(String accountId, String dashboardName) {
        """newrelic_api_key       = "REPLACE_WITH_API_KEY"
newrelic_account_id    = ${accountId ?: 0}
dashboard_name         = ${escapeHcl(dashboardName)}
"""
    }

    static String dashboardTf(
        Map dashboard,
        String accountId,
        String resourceName,
        String dashboardName,
        String description
    ) {
        StringBuilder hcl = new StringBuilder()
        hcl.append("resource \"newrelic_one_dashboard\" \"${resourceName}\" {").append(System.lineSeparator())
        hcl.append("  name        = var.dashboard_name").append(System.lineSeparator())
        if (description) {
            hcl.append("  description = ${escapeHcl(description)}").append(System.lineSeparator())
        }
        hcl.append("  account_id  = var.newrelic_account_id").append(System.lineSeparator())
        hcl.append(System.lineSeparator())
        List pages = dashboard.pages instanceof List ? (List) dashboard.pages : []
        pages.eachWithIndex { page, pageIndex ->
            Map pageMap = page instanceof Map ? (Map) page : [:]
            String pageName = pageMap.name?.toString() ?: "Page ${pageIndex + 1}"
            hcl.append("  page {").append(System.lineSeparator())
            hcl.append("    name = ${escapeHcl(pageName)}").append(System.lineSeparator())
            hcl.append(System.lineSeparator())
            List widgets = pageMap.widgets instanceof List ? (List) pageMap.widgets : []
            widgets.eachWithIndex { widget, widgetIndex ->
                Map widgetMap = widget instanceof Map ? (Map) widget : [:]
                appendWidget(hcl, widgetMap, accountId, widgetIndex + 1)
            }
            hcl.append("  }").append(System.lineSeparator())
            if (pageIndex < pages.size() - 1) {
                hcl.append(System.lineSeparator())
            }
        }
        hcl.append('}').append(System.lineSeparator())
        hcl.toString()
    }

    private static void appendWidget(StringBuilder hcl, Map widget, String defaultAccountId, int widgetIndex) {
        String title = widget.title?.toString() ?: "Widget ${widgetIndex}"
        Map layout = widget.layout instanceof Map ? (Map) widget.layout : [:]
        int row = (layout.row ?: widget.row ?: 1) as int
        int column = (layout.column ?: widget.column ?: 1) as int
        int width = (layout.width ?: widget.width ?: 4) as int
        int height = (layout.height ?: widget.height ?: 3) as int
        String vizId = widget.visualization?.id?.toString() ?: widget.visualization_id?.toString() ?: 'viz.line'
        hcl.append("    widget {").append(System.lineSeparator())
        hcl.append("      title            = ${escapeHcl(title)}").append(System.lineSeparator())
        hcl.append("      row              = ${row}").append(System.lineSeparator())
        hcl.append("      column           = ${column}").append(System.lineSeparator())
        hcl.append("      width            = ${width}").append(System.lineSeparator())
        hcl.append("      height           = ${height}").append(System.lineSeparator())
        hcl.append("      visualization_id = ${escapeHcl(vizId)}").append(System.lineSeparator())
        List<Map> queries = extractNrqlQueries(widget, defaultAccountId)
        if (queries) {
            hcl.append(System.lineSeparator())
            queries.eachWithIndex { Map query, queryIndex ->
                hcl.append("      nrql_query {").append(System.lineSeparator())
                hcl.append("        query      = ${escapeHcl(query.query?.toString() ?: '')}").append(System.lineSeparator())
                hcl.append("        account_id = ${query.account_id}").append(System.lineSeparator())
                hcl.append("      }").append(System.lineSeparator())
                if (queryIndex < queries.size() - 1) {
                    hcl.append(System.lineSeparator())
                }
            }
        }
        hcl.append("    }").append(System.lineSeparator())
        hcl.append(System.lineSeparator())
    }

    static List<Map> extractNrqlQueries(Map widget, String defaultAccountId) {
        List<Map> queries = []
        Map configuration = widget.configuration instanceof Map ? (Map) widget.configuration : [:]
        ['area', 'bar', 'billboard', 'line', 'pie', 'table', 'markdown', 'stacked_bar'].each { vizType ->
            Map section = configuration[vizType] instanceof Map ? (Map) configuration[vizType] : null
            if (!section) {
                return
            }
            List nrqlQueries = section.nrqlQueries instanceof List ? (List) section.nrqlQueries : []
            nrqlQueries.each { entry ->
                if (!(entry instanceof Map)) {
                    return
                }
                queries << [
                    query: entry.query?.toString() ?: '',
                    account_id: entry.accountId ?: entry.account_id ?: defaultAccountId
                ]
            }
        }
        if (!queries && widget.rawConfiguration) {
            try {
                groovy.json.JsonSlurper slurper = new groovy.json.JsonSlurper()
                Object parsed = slurper.parseText(widget.rawConfiguration.toString())
                if (parsed instanceof Map && parsed.nrqlQueries instanceof List) {
                    parsed.nrqlQueries.each { entry ->
                        if (entry instanceof Map) {
                            queries << [
                                query: entry.query?.toString() ?: '',
                                account_id: entry.accountId ?: entry.account_id ?: defaultAccountId
                            ]
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        queries
    }

    static String escapeHcl(String value) {
        if (value == null) {
            return '""'
        }
        String escaped = value
            .replace('\\', '\\\\')
            .replace('"', '\\"')
            .replace('\n', '\\n')
            .replace('\r', '\\r')
            .replace('\t', '\\t')
        "\"${escaped}\""
    }

    static String sanitizeResourceName(String value) {
        String safe = (value ?: 'exported_dashboard').toLowerCase()
            .replaceAll(/[^a-z0-9_]+/, '_')
            .replaceAll(/^_+|_+$/, '')
        safe ?: 'exported_dashboard'
    }
}

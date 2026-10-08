package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status

import java.util.regex.Matcher

class JiraAssetsAdapter {
    static final String MALFORMED = 'Malformed Jira Assets response'

    static final Map<Integer, String> ATTRIBUTE_TYPES = [
        0: 'Default',
        1: 'Object',
        2: 'User',
        3: 'Confluence',
        4: 'Group',
        5: 'Version',
        6: 'Project',
        7: 'Status'
    ]

    final JiraOperatorAdapter jira
    final Map rules

    JiraAssetsAdapter(JiraOperatorAdapter jira, Map rules) {
        this.jira = jira
        this.rules = rules
    }

    Map schemas() {
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('assets-schemas', 'Jira credentials unavailable')
        }
        Map result = jira.get(path('assets_object_schemas'), credentials)
        if (result.failure) {
            return JiraOperatorAdapter.failure('assets-schemas', result)
        }
        List<Map> entries = listOf(result.data, 'objectschemas')
        if (entries == null) {
            return malformed('assets-schemas')
        }
        JiraOperatorAdapter.report('assets-schemas', Status.READY, entries.collect { Map schema ->
            [
                id: schema.id?.toString() ?: '',
                key: schema.objectSchemaKey?.toString() ?: '',
                name: schema.name?.toString() ?: '',
                object_count: (schema.objectCount ?: 0) as long
            ]
        })
    }

    Map types(int schemaId) {
        Map context = [schema_id: schemaId]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('assets-types', 'Jira credentials unavailable', context)
        }
        Map result = jira.get(path('assets_object_types', schemaId.toString()), credentials)
        if (result.failure) {
            return JiraOperatorAdapter.failure('assets-types', result, context)
        }
        List<Map> entries = listOf(result.data, 'objectTypes')
        if (entries == null) {
            return malformed('assets-types', context)
        }
        JiraOperatorAdapter.report('assets-types', Status.READY, entries.collect { Map type ->
            [
                id: type.id?.toString() ?: '',
                name: type.name?.toString() ?: '',
                parent_id: type.parentObjectTypeId?.toString() ?: '',
                object_count: (type.objectCount ?: 0) as long
            ]
        }, context)
    }

    Map attributes(int typeId) {
        Map context = [type_id: typeId]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('assets-attributes', 'Jira credentials unavailable', context)
        }
        Map result = jira.get(path('assets_type_attributes', typeId.toString()), credentials)
        if (result.failure) {
            return JiraOperatorAdapter.failure('assets-attributes', result, context)
        }
        List<Map> entries = listOf(result.data, 'objectTypeAttributes')
        if (entries == null) {
            return malformed('assets-attributes', context)
        }
        JiraOperatorAdapter.report('assets-attributes', Status.READY, entries.collect { Map attribute ->
            [
                id: attribute.id?.toString() ?: '',
                name: attribute.name?.toString() ?: '',
                type: attributeType(attribute),
                referenced_type: attribute.referenceObjectType?.name?.toString() ?: '',
                minimum: attribute.minimumCardinality == null ? null : attribute.minimumCardinality as int,
                maximum: attribute.maximumCardinality == null ? null : attribute.maximumCardinality as int
            ]
        }, context)
    }

    Map object(String objectKey) {
        Map fetched = fetchObject('assets-object', objectKey)
        if (fetched.report) {
            return (Map) fetched.report
        }
        JiraOperatorAdapter.report('assets-object', Status.READY, [(Map) fetched.object], [object_key: objectKey])
    }

    Map search(String query, int limit) {
        Map context = [query: query]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('assets-search', 'Jira credentials unavailable', context)
        }
        int bounded = Math.min(limit, limitValue('assets_search_max'))
        Map searched = searchObjects(
            query,
            bounded,
            limitValue('assets_page_size'),
            true,
            credentials
        ) { Map item -> true }
        if (searched.failure) {
            return searchFailure('assets-search', searched, context)
        }
        JiraOperatorAdapter.report(
            'assets-search',
            Status.READY,
            (List<Map>) searched.items,
            context + [truncated: searched.truncated as boolean]
        )
    }

    Map ci(String objectKey) {
        Map fetched = fetchObject('get-ci', objectKey)
        if (fetched.report) {
            return (Map) fetched.report
        }
        Map object = (Map) fetched.object
        List<String> labelParts = labelParts(object.label.toString())
        if (labelParts == null) {
            return JiraOperatorAdapter.report(
                'get-ci',
                Status.ERROR,
                [],
                [
                    object_key: objectKey,
                    message: "Object is not an application CI: ${object.label}",
                    error_kind: 'user'
                ]
            )
        }
        Map fieldNames = (Map) ciRules().fields
        Map attributes = (Map) object.attributes
        Map item = [
            key: object.key,
            id: object.id,
            label: object.label,
            env: labelParts[0],
            app: labelParts[1],
            object_type: object.object_type,
            updated: object.updated,
            fields: fieldNames.collectEntries { field, attribute ->
                List values = (List) attributes[attribute.toString()]
                [(field.toString()): values ? values[0].toString() : '']
            },
            field_attributes: fieldNames.collectEntries { field, attribute ->
                [(field.toString()): attribute.toString()]
            }
        ]
        JiraOperatorAdapter.report('get-ci', Status.READY, [item], [object_key: objectKey])
    }

    Map cis(String env, int limit) {
        Map context = env ? [env: env] : [:]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('get-cis', 'Jira credentials unavailable', context)
        }
        Map ci = ciRules()
        String query = env ?
            ci.env_search_iql.toString().replace('{env}', env) :
            ci.search_iql.toString()
        int bounded = Math.min(limit, limitValue('ci_list_max'))
        Map searched = searchObjects(
            query,
            bounded,
            limitValue('ci_list_page_size'),
            false,
            credentials
        ) { Map item ->
            List<String> labelParts = labelParts(item.label.toString())
            labelParts != null && (!env || labelParts[0] == env)
        }
        if (searched.failure) {
            return searchFailure('get-cis', searched, context + [query: query])
        }
        List<Map> items = ((List<Map>) searched.items).collect { Map item ->
            [key: item.key, id: item.id, label: item.label]
        }.sort { it.label }
        JiraOperatorAdapter.report(
            'get-cis',
            Status.READY,
            items,
            context + [
                query: query,
                truncated: searched.truncated as boolean,
                totals: [cis: items.size()]
            ]
        )
    }

    Map normalizeObject(Map raw, Map<String, String> attributeNames = [:]) {
        Map type = raw.objectType instanceof Map ? (Map) raw.objectType : [:]
        int maximum = limitValue('assets_values_max')
        Map<String, List<String>> attributes = [:]
        Set<String> referenced = new LinkedHashSet<>()
        ((List) (raw.attributes ?: [])).findAll { it instanceof Map }.each { Map attribute ->
            String id = attribute.objectTypeAttributeId?.toString() ?: ''
            String name = attribute.objectTypeAttribute?.name?.toString() ?:
                attributeNames[id] ?:
                id
            List<String> values = attributes[name] ?: []
            ((List) (attribute.objectAttributeValues ?: [])).findAll { it instanceof Map }.each { Map value ->
                Map reference = value.referencedObject instanceof Map ? (Map) value.referencedObject : null
                if (reference?.objectKey) {
                    referenced << reference.objectKey.toString()
                }
                Object display = value.displayValue ?: reference?.label ?: value.value
                if (display != null && values.size() < maximum) {
                    values << display.toString()
                }
            }
            attributes[name] = values
        }
        [
            key: raw.objectKey?.toString() ?: '',
            id: raw.id?.toString() ?: '',
            label: raw.label?.toString() ?: '',
            object_type: type.name?.toString() ?: '',
            created: raw.created?.toString() ?: '',
            updated: raw.updated?.toString() ?: '',
            attributes: attributes,
            referenced_keys: referenced as List
        ]
    }

    private Map fetchObject(String operation, String objectKey) {
        Map context = [object_key: objectKey]
        Map credentials = jira.credentials()
        if (!credentials) {
            return [report: JiraOperatorAdapter.blocked(operation, 'Jira credentials unavailable', context)]
        }
        Map result = jira.get(path('assets_object', objectKey), credentials)
        if (result.failure) {
            return [report: JiraOperatorAdapter.failure(operation, result, context)]
        }
        if (!(result.data instanceof Map) || !((Map) result.data).objectKey) {
            return [report: malformed(operation, context)]
        }
        [object: normalizeObject((Map) result.data)]
    }

    private Map searchObjects(
        String query,
        int limit,
        int pageSize,
        boolean includeAttributes,
        Map credentials,
        Closure<Boolean> accept
    ) {
        List<Map> items = []
        int pageNumber = 1
        int seen = 0
        while (items.size() <= limit) {
            String parameters = [
                (((Map) rules.api_params).assets_search_query.toString()): query,
                page: pageNumber.toString(),
                resultPerPage: pageSize.toString(),
                includeAttributes: includeAttributes.toString(),
                includeTypeAttributes: includeAttributes.toString()
            ].collect { key, value ->
                "${URLEncoder.encode(key.toString(), 'UTF-8')}=${URLEncoder.encode(value.toString(), 'UTF-8')}"
            }.join('&')
            Map result = jira.get("${path('assets_search')}?${parameters}", credentials)
            if (result.failure) {
                return result
            }
            Map payload = result.data instanceof Map ? (Map) result.data : [:]
            if (!(payload.objectEntries instanceof List)) {
                return [failure: true, malformed: true]
            }
            Map<String, String> names = ((List) (payload.objectTypeAttributes ?: [])).findAll {
                it instanceof Map && it.id != null
            }.collectEntries { Map attribute ->
                [(attribute.id.toString()): attribute.name?.toString() ?: '']
            }
            List<Map> entries = ((List) payload.objectEntries).findAll { it instanceof Map }
            for (Map entry : entries) {
                Map item = normalizeObject(entry, names)
                if (accept(item)) {
                    items << item
                    if (items.size() > limit) {
                        break
                    }
                }
            }
            seen += entries.size()
            Integer total = payload.totalFilterCount instanceof Number ?
                payload.totalFilterCount as Integer :
                null
            if (!entries || (total != null ? seen >= total : entries.size() < pageSize)) {
                break
            }
            pageNumber++
        }
        [items: items.take(limit), truncated: items.size() > limit]
    }

    private static Map searchFailure(String operation, Map result, Map context) {
        result.malformed ? malformed(operation, context) : JiraOperatorAdapter.failure(operation, result, context)
    }

    private static Map malformed(String operation, Map context = [:]) {
        JiraOperatorAdapter.report(operation, Status.ERROR, [], [message: MALFORMED] + context)
    }

    private static List<Map> listOf(Object data, String wrapper) {
        Object entries = data instanceof Map ? ((Map) data)[wrapper] : data
        entries instanceof List ? ((List) entries).findAll { it instanceof Map } : null
    }

    private static String attributeType(Map attribute) {
        if (attribute.defaultType instanceof Map && attribute.defaultType.name) {
            return attribute.defaultType.name.toString()
        }
        attribute.type instanceof Number ?
            ATTRIBUTE_TYPES[(attribute.type as int)] ?: attribute.type.toString() :
            attribute.type?.toString() ?: ''
    }

    private List<String> labelParts(String label) {
        Matcher matcher = label =~ ciRules().label_pattern.toString()
        matcher.matches() ? [matcher.group(1), matcher.group(2)] : null
    }

    private Map ciRules() {
        (Map) rules.ci
    }

    private int limitValue(String name) {
        ((Map) rules.limits)[name] as int
    }

    private String path(String name, String id = null) {
        String suffix = jira.apiPath(name)
        if (id != null) {
            suffix = suffix.replace('{id}', URLEncoder.encode(id, 'UTF-8'))
        }
        "${jira.apiPath('assets_base')}${suffix}"
    }
}

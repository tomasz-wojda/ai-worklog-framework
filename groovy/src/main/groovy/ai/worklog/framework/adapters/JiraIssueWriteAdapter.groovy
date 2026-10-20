package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Matcher
import java.util.regex.Pattern

class JiraIssueWriteAdapter {
    final JiraOperatorAdapter jira
    final JsonWriteHttp writeHttp
    final Map rules

    JiraIssueWriteAdapter(JiraOperatorAdapter jira, JsonWriteHttp writeHttp, Map rules) {
        this.jira = jira
        this.writeHttp = writeHttp
        this.rules = rules
    }

    Map comment(String ticketKey, String body, boolean apply) {
        Map context = [ticket_key: ticketKey, dry_run: !apply, applied: false]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('comment', 'Jira credentials unavailable', context)
        }
        Map prepared = prepareBody('comment', ticketKey, body, credentials, context)
        if (prepared.report) {
            return (Map) prepared.report
        }
        String text = prepared.text
        Map item = (Map) prepared.item
        Map existing = jira.comments(ticketKey, credentials)
        if (existing.error) {
            return JiraOperatorAdapter.report('comment', Status.ERROR, [item], context + [message: existing.error])
        }
        String duplicate = findDuplicate((List<Map>) existing.items, text)
        if (duplicate) {
            return userError('comment', context, "Identical comment already exists: ${duplicate}", [item])
        }
        if (!apply) {
            return JiraOperatorAdapter.report(
                'comment',
                Status.READY,
                [item],
                context + [message: 'Dry-run only; no Jira comment created']
            )
        }
        postComment(ticketKey, text, item, credentials)
    }

    Map editComment(String ticketKey, String commentId, String body, boolean apply) {
        Map context = [ticket_key: ticketKey, dry_run: !apply, applied: false]
        Map credentials = jira.credentials()
        if (!credentials) {
            return JiraOperatorAdapter.blocked('comment-edit', 'Jira credentials unavailable', context)
        }
        Map prepared = prepareBody('comment-edit', ticketKey, body, credentials, context)
        if (prepared.report) {
            return (Map) prepared.report
        }
        String text = prepared.text
        Map item = (Map) prepared.item
        Map current = jira.get("${jira.apiPath('issue')}/${ticketKey}/comment/${commentId}", credentials)
        if (current.failure) {
            return JiraOperatorAdapter.failure('comment-edit', current, context)
        }
        Map me = jira.get(jira.apiPath('myself'), credentials)
        if (me.failure) {
            return JiraOperatorAdapter.failure('comment-edit', me, context)
        }
        Map existing = current.data instanceof Map ? (Map) current.data : [:]
        Map author = existing.author instanceof Map ? (Map) existing.author : [:]
        Map user = me.data instanceof Map ? (Map) me.data : [:]
        String authorKey = author.key?.toString() ?: ''
        String authorName = author.name?.toString() ?: ''
        boolean owned = (authorKey && authorKey == user.key?.toString()) ||
            (authorName && authorName == user.name?.toString())
        if (!owned) {
            return userError(
                'comment-edit',
                context,
                "Comment ${commentId} belongs to ${author.displayName ?: 'another user'}; only own comments can be edited",
                [item]
            )
        }
        String oldText = normalize(existing.body?.toString())
        item.comment_id = commentId
        item.comment_url = commentUrl(baseUrl(credentials), ticketKey, commentId)
        item.old_body_characters = oldText.size()
        item.old_body_sha256 = sha256(oldText)
        if (oldText == text) {
            return userError('comment-edit', context, "Comment ${commentId} already has this body", [item])
        }
        if (!apply) {
            return JiraOperatorAdapter.report(
                'comment-edit',
                Status.READY,
                [item],
                context + [message: 'Dry-run only; Jira comment not changed']
            )
        }
        putComment(ticketKey, commentId, text, item, credentials)
    }

    private Map prepareBody(String operation, String ticketKey, String body, Map credentials, Map context) {
        String text = normalize(body)
        int maximum = limits().issue_comment_max_characters as int
        if (!text || text.size() > maximum) {
            return [report: userError(operation, context, "Comment must contain 1-${maximum} characters")]
        }
        Map issue = jira.get("${jira.apiPath('issue')}/${ticketKey}?fields=summary", credentials)
        if (issue.failure) {
            return [report: JiraOperatorAdapter.failure(operation, issue, context)]
        }
        Map resolution = resolveMentions(text, credentials)
        if (resolution.failure) {
            return [report: JiraOperatorAdapter.failure(operation, (Map) resolution.failure, context)]
        }
        List<Map> mentions = (List<Map>) resolution.mentions
        Map fields = ((Map) issue.data).fields instanceof Map ? (Map) ((Map) issue.data).fields : [:]
        Map item = commentItem(ticketKey, fields.summary?.toString() ?: '', text, mentions)
        List<String> unresolved = mentions.findAll { !it.active }*.username
        if (unresolved) {
            return [report: userError(operation, context, "Unresolved mentions: ${unresolved.join(', ')}", [item])]
        }
        [text: text, item: item]
    }

    private Map postComment(String ticketKey, String text, Map item, Map credentials) {
        String base = baseUrl(credentials)
        Map response = writeHttp.post(
            "${base}${jira.apiPath('issue')}/${ticketKey}/comment",
            jira.headers(credentials),
            [body: text],
            jira.timeout(),
            limits().error_body_max_characters as int,
            limits().write_response_max_characters as int
        )
        verifyWrite('comment', response, ticketKey, text, item, base, null, 'Jira comment created')
    }

    private Map putComment(String ticketKey, String commentId, String text, Map item, Map credentials) {
        String base = baseUrl(credentials)
        Map response = writeHttp.put(
            "${base}${jira.apiPath('issue')}/${ticketKey}/comment/${commentId}",
            jira.headers(credentials),
            [body: text],
            jira.timeout(),
            limits().error_body_max_characters as int,
            limits().write_response_max_characters as int
        )
        verifyWrite('comment-edit', response, ticketKey, text, item, base, commentId, 'Jira comment updated')
    }

    private static Map verifyWrite(
        String operation,
        Map response,
        String ticketKey,
        String text,
        Map item,
        String base,
        String expectedId,
        String successMessage
    ) {
        Map context = [ticket_key: ticketKey, dry_run: false, applied: false]
        int code = (response.code ?: 0) as int
        if (code < 200 || code >= 300) {
            Status status = code in [401, 403] ? Status.BLOCKED : Status.ERROR
            return JiraOperatorAdapter.report(operation, status, [item], context + [
                message: code ? "Jira returned HTTP ${code}" : 'Jira request failed'
            ])
        }
        Map written = parseObject(response.body?.toString())
        String commentId = written.id?.toString() ?: ''
        item.comment_id = commentId
        boolean idMatches = !commentId ? false : (expectedId == null || commentId == expectedId)
        if (!idMatches || normalize(written.body?.toString()) != text) {
            return JiraOperatorAdapter.report(operation, Status.ERROR, [item], context + [
                message: 'Comment verification failed'
            ])
        }
        item.comment_url = commentUrl(base, ticketKey, commentId)
        JiraOperatorAdapter.report(operation, Status.READY, [item], context + [
            applied: true,
            message: successMessage
        ])
    }

    private Map resolveMentions(String text, Map credentials) {
        Pattern pattern = Pattern.compile(((Map) rules.comment).mention_pattern.toString())
        Matcher matcher = pattern.matcher(text)
        Set<String> usernames = [] as LinkedHashSet
        while (matcher.find()) {
            usernames << matcher.group(1)
        }
        List<Map> mentions = []
        for (String username : usernames) {
            Map result = jira.get(
                "${jira.apiPath('user')}?username=${URLEncoder.encode(username, 'UTF-8')}",
                credentials
            )
            if (result.failure && result.code != 404) {
                return [failure: result]
            }
            Map user = result.failure ? [:] : (Map) result.data
            mentions << [
                username: username,
                display_name: user.displayName?.toString() ?: '',
                active: user ? (user.active == null ? true : user.active as boolean) : false
            ]
        }
        [mentions: mentions]
    }

    private static String findDuplicate(List<Map> comments, String text) {
        comments.find { normalize(it.body?.toString()) == text }?.id?.toString()
    }

    private static Map commentItem(String ticketKey, String summary, String text, List<Map> mentions) {
        [
            issue_key: ticketKey,
            summary: summary,
            body_characters: text.size(),
            body_sha256: sha256(text),
            first_line: text.readLines()[0],
            mentions: mentions
        ]
    }

    private static String sha256(String text) {
        MessageDigest.getInstance('SHA-256')
            .digest(text.getBytes(StandardCharsets.UTF_8))
            .collect { String.format('%02x', it) }.join('')
    }

    private static String commentUrl(String base, String ticketKey, String commentId) {
        "${base}/browse/${ticketKey}?focusedCommentId=${commentId}#comment-${commentId}"
    }

    private static String baseUrl(Map credentials) {
        credentials.url.toString().replaceAll(/\/+$/, '')
    }

    private static String normalize(String value) {
        (value ?: '').replace('\r\n', '\n').replaceAll(/\s+$/, '')
    }

    private static Map parseObject(String body) {
        try {
            Object parsed = body?.trim() ? new JsonSlurper().parseText(body) : null
            parsed instanceof Map ? (Map) parsed : [:]
        } catch (Exception ignored) {
            [:]
        }
    }

    private static Map userError(String operation, Map context, String message, List<Map> items = []) {
        JiraOperatorAdapter.report(operation, Status.ERROR, items, context + [message: message, error_kind: 'user'])
    }

    private Map limits() {
        (Map) rules.limits
    }
}

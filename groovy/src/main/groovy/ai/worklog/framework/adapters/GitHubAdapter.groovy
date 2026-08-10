package ai.worklog.framework.adapters

import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation
import groovy.json.JsonSlurper

class GitHubAdapter {
    final ReadOnlyProcess process
    final Map rules

    GitHubAdapter(ReadOnlyProcess process, Map rules) {
        this.process = process
        this.rules = rules
    }

    List<Observation> observe(String ticketKey, List<String> repoUrls) {
        if (!commandAvailable('gh')) {
            return [new Observation(
                system: 'github',
                source: 'github',
                status: Status.UNKNOWN,
                message: 'GitHub CLI unavailable'
            )]
        }
        if (!repoUrls) {
            return [new Observation(
                system: 'github',
                source: 'github',
                status: Status.UNKNOWN,
                message: 'No repository URLs configured'
            )]
        }
        String search = ticketKey.replaceAll(/[^A-Za-z0-9._-]/, '')
        List<Observation> observations = []
        repoUrls.each { url ->
            String repo = repoFromUrl(url)
            String source = "github:${repo ?: 'invalid'}"
            if (!repo || repo.split('/').any { it.contains('..') }) {
                observations << new Observation(
                    system: 'github',
                    source: source,
                    status: Status.ERROR,
                    message: 'Invalid repository URL'
                )
                return
            }
            Map result = process.execute([
                'gh', 'pr', 'list',
                '--repo', repo,
                '--search', search,
                '--state', 'all',
                '--json', 'number,title,state,isDraft,url,headRefName,baseRefName',
                '--limit', '50'
            ], timeout())
            if (result.code != 0) {
                observations << new Observation(
                    system: 'github',
                    source: source,
                    status: Status.DEGRADED,
                    message: 'GitHub query failed',
                    details: [stderr: result.err ?: '']
                )
                return
            }
            try {
                List pulls = (List) new JsonSlurper().parseText(result.out ?: '[]')
                observations << new Observation(
                    system: 'github',
                    source: source,
                    status: Status.READY,
                    message: "${pulls.size()} pull request(s) found",
                    details: [repository: repo, pull_requests: pulls]
                )
            } catch (Exception ignored) {
                observations << new Observation(
                    system: 'github',
                    source: source,
                    status: Status.ERROR,
                    message: 'Malformed GitHub response'
                )
            }
        }
        observations
    }

    private static boolean commandAvailable(String binary) {
        try {
            ReadOnlyProcess.validateArgv(['which', binary])
            new ProcessBuilder(['which', binary]).start().waitFor() == 0
        } catch (Exception ignored) {
            false
        }
    }

    private static String repoFromUrl(String url) {
        URI uri
        try {
            uri = new URI(url)
        } catch (Exception ignored) {
            return null
        }
        String path = uri.path?.replaceAll(/^\/+|\/+$/, '') ?: ''
        if (path.endsWith('.git')) {
            path = path.substring(0, path.length() - 4)
        }
        path ?: null
    }

    private int timeout() {
        ((Map) (rules.timeouts ?: [:])).process_seconds ?: 15
    }
}

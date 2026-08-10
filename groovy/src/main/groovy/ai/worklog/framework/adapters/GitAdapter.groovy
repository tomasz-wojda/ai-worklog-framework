package ai.worklog.framework.adapters

import ai.worklog.framework.core.FrameworkPaths
import ai.worklog.framework.core.Status
import ai.worklog.framework.reconciliation.Observation

class GitAdapter {
    final FrameworkPaths paths
    final ReadOnlyProcess process
    final Map rules

    GitAdapter(FrameworkPaths paths, ReadOnlyProcess process, Map rules) {
        this.paths = paths
        this.process = process
        this.rules = rules
    }

    List<Observation> observe(Map state, List<String> repositories) {
        List<Observation> observations = []
        if (!repositories) {
            observations << new Observation(
                system: 'git',
                source: 'git',
                status: Status.UNKNOWN,
                message: 'No repositories configured in ticket state'
            )
            return observations
        }
        String root = rules.repositories_root?.toString() ?: 'repos'
        File allowedRoot = new File(paths.root, root).canonicalFile
        File workspaceRoot = paths.root.canonicalFile
        if (!allowedRoot.toPath().startsWith(workspaceRoot.toPath())) {
            observations << new Observation(
                system: 'git',
                source: 'git',
                status: Status.ERROR,
                message: 'Repository root outside workspace'
            )
            return observations
        }

        repositories.each { repoName ->
            String source = "git:${repoName}"
            try {
                FrameworkPaths.validateComponent(repoName, 'repository name')
            } catch (IllegalArgumentException exception) {
                observations << new Observation(
                    system: 'git',
                    source: source,
                    status: Status.ERROR,
                    message: exception.message
                )
                return
            }
            File repoPath = new File(allowedRoot, repoName).canonicalFile
            if (!repoPath.canonicalPath.startsWith(allowedRoot.canonicalPath)) {
                observations << new Observation(
                    system: 'git',
                    source: source,
                    status: Status.ERROR,
                    message: 'Repository path outside workspace repos root'
                )
                return
            }
            if (!repoPath.isDirectory()) {
                observations << new Observation(
                    system: 'git',
                    source: source,
                    status: Status.DEGRADED,
                    message: 'Repository not cloned',
                    details: [local_dir: repoName, present: false]
                )
                return
            }
            Map branchResult = process.execute(['git', '-C', repoPath.path, 'rev-parse', '--abbrev-ref', 'HEAD'], timeout())
            Map headResult = process.execute(['git', '-C', repoPath.path, 'rev-parse', 'HEAD'], timeout())
            Map upstreamResult = process.execute(['git', '-C', repoPath.path, 'rev-parse', '@{upstream}'], timeout())
            Map statusResult = process.execute(['git', '-C', repoPath.path, 'status', '--porcelain'], timeout())
            if (branchResult.code != 0 || headResult.code != 0) {
                observations << new Observation(
                    system: 'git',
                    source: source,
                    status: Status.ERROR,
                    message: 'Git query failed',
                    details: [stderr: branchResult.err ?: '']
                )
                return
            }
            boolean dirty = !(statusResult.out ?: '').trim().isEmpty()
            int ahead = 0
            String upstream = (upstreamResult.out ?: '').trim()
            if (upstreamResult.code == 0 && upstream) {
                Map countResult = process.execute(
                    ['git', '-C', repoPath.path, 'rev-list', '--count', "${upstream}..HEAD"],
                    timeout()
                )
                if (countResult.code == 0 && (countResult.out ?: '').trim().isInteger()) {
                    ahead = (countResult.out.trim()) as int
                }
            }
            observations << new Observation(
                system: 'git',
                source: source,
                status: Status.READY,
                message: 'Repository inspected',
                details: [
                    local_dir: repoName,
                    present: true,
                    branch: (branchResult.out ?: '').trim(),
                    head: (headResult.out ?: '').trim(),
                    upstream: upstreamResult.code == 0 ? upstream : '',
                    dirty: dirty,
                    ahead_of_upstream: ahead
                ]
            )
        }
        observations
    }

    private int timeout() {
        ((Map) (rules.timeouts ?: [:])).process_seconds ?: 15
    }
}

package ai.worklog.framework

class AotTraining {
    static final List<List<String>> COMMANDS = [
        ['--version'],
        ['help'],
        ['help', '--json'],
        ['help', '--json', 'service', 'jira', 'get-cis'],
        ['service'],
        ['service', 'jira'],
        ['service', 'jira', 'get-ci', 'invalid-key'],
        ['service', 'jenkins', 'jobs', '--limit', '0'],
        ['catalog', '--help'],
        ['toolchain', '--help'],
    ]

    static void main(String[] input) {
        PrintStream out = System.out
        PrintStream err = System.err
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream())
        try {
            System.out = sink
            System.err = sink
            COMMANDS.each { List<String> command ->
                try {
                    Main.execute(command)
                } catch (Exception ignored) {
                }
            }
        } finally {
            System.out = out
            System.err = err
        }
    }
}

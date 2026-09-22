package ai.worklog.framework.jenkins

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class JenkinsVersioning {
    private static final Map<String, Integer> QUALIFIERS = [
        snapshot: -5,
        alpha: -4,
        a: -4,
        beta: -3,
        b: -3,
        milestone: -2,
        m: -2,
        rc: -1,
        cr: -1,
        '': 0,
        final: 0,
        ga: 0,
        release: 0,
        sp: 1
    ]

    static int compare(String left, String right) {
        List<List> leftTokens = tokens(left)
        List<List> rightTokens = tokens(right)
        int length = Math.max(leftTokens.size(), rightTokens.size())
        for (int index = 0; index < length; index++) {
            List leftToken = index < leftTokens.size() ? leftTokens[index] : null
            List rightToken = index < rightTokens.size() ? rightTokens[index] : null
            if (leftToken == rightToken) {
                continue
            }
            if (leftToken == null) {
                int result = compareMissing(rightToken)
                if (result != 0) {
                    return result
                }
                continue
            }
            if (rightToken == null) {
                int result = -compareMissing(leftToken)
                if (result != 0) {
                    return result
                }
                continue
            }
            int kind = (leftToken[0] as int) <=> (rightToken[0] as int)
            if (kind != 0) {
                return kind
            }
            int value = leftToken[1] <=> rightToken[1]
            if (value != 0) {
                return value
            }
        }
        0
    }

    static boolean atLeast(String actual, String required) {
        compare(actual, required) >= 0
    }

    static boolean warningMatches(Map warning, String version) {
        Object rawRanges = warning.versions
        if (!(rawRanges instanceof List) || !rawRanges) {
            return true
        }
        for (Object rawEntry : (List) rawRanges) {
            if (!(rawEntry instanceof Map)) {
                return true
            }
            String pattern = ((Map) rawEntry).pattern?.toString()
            if (!pattern) {
                return true
            }
            try {
                if (Pattern.compile(pattern).matcher(version ?: '').matches()) {
                    return true
                }
            } catch (PatternSyntaxException ignored) {
                return true
            }
        }
        false
    }

    private static List<List> tokens(String version) {
        List<List> result = []
        def matcher = Pattern.compile(/\d+|[A-Za-z]+/).matcher((version ?: '').toLowerCase())
        while (matcher.find()) {
            String value = matcher.group()
            if (value ==~ /\d+/) {
                result << [2, new BigInteger(value)]
            } else if (QUALIFIERS.containsKey(value)) {
                result << [0, QUALIFIERS[value]]
            } else {
                result << [1, value]
            }
        }
        while (result && result[-1] in [[2, 0G], [0, 0]]) {
            result.remove(result.size() - 1)
        }
        result
    }

    private static int compareMissing(List token) {
        int kind = token[0] as int
        if (kind == 2) {
            return token[1] == 0 ? 0 : -1
        }
        if (kind == 0) {
            int value = token[1] as int
            if (value < 0) {
                return 1
            }
            return value == 0 ? 0 : -1
        }
        -1
    }
}

package ai.worklog.framework

import ai.worklog.framework.automox.AutomoxOperatorReport
import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.OperatorReport
import ai.worklog.framework.core.Status
import groovy.test.GroovyTestCase

class OperatorReportTest extends GroovyTestCase {
    private ExitCodes exitCodes = new ExitCodes(new File('..').canonicalFile)

    void testExplicitErrorKindOverridesMessageMarkers() {
        assertEquals(exitCodes.systemError, exit([message: 'Group not found', error_kind: 'system']))
        assertEquals(exitCodes.userError, exit([message: 'Server exploded', error_kind: 'user']))
    }

    void testMessageMarkersApplyOnlyWithoutErrorKind() {
        assertEquals(exitCodes.userError, exit([message: 'Group not found']))
        assertEquals(exitCodes.systemError, exit([message: 'Server exploded']))
    }

    void testStatusMapping() {
        assertEquals(exitCodes.blocked, exit([status: 'blocked', message: 'Group not found']))
        assertEquals(exitCodes.success, exit([status: 'ready']))
        assertEquals(Status.UNKNOWN, OperatorReport.parseStatus('nonsense'))
    }

    void testPythonItemFormatting() {
        assertEquals(
            "{'a': None, 'b': True, 'c': 1, 'd': 'x', 'e': [1, {'f': False}]}",
            OperatorReport.pythonItemString([a: null, b: true, c: 1, d: 'x', e: [1, [f: false]]])
        )
    }

    private int exit(Map payload) {
        AutomoxOperatorReport.exitCodeFor(
            AutomoxOperatorReport.fromPayload([operation: 'group', status: 'error'] + payload),
            exitCodes
        )
    }
}

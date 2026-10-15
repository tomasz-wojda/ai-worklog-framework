package ai.worklog.framework

import ai.worklog.framework.jenkins.JenkinsLdapSearchScript
import groovy.json.JsonSlurper
import groovy.test.GroovyTestCase

import java.nio.charset.StandardCharsets

class JenkinsLdapSearchScriptTest extends GroovyTestCase {
    void testTermWithoutWildcardMatchesSubstring() {
        assertEquals(['*daszew*'], JenkinsLdapSearchScript.terms('daszew', 128))
    }

    void testPipeSeparatesTrimmedWildcardTerms() {
        assertEquals(['mkwiat*', 'pbakier*'], JenkinsLdapSearchScript.terms('mkwiat*| pbakier* ', 128))
    }

    void testFilterSpecialCharactersAreEscaped() {
        assertEquals(['*a\\28b\\29\\5cc*'], JenkinsLdapSearchScript.terms('a(b)\\c', 128))
    }

    void testInvalidQueriesAreRejected() {
        assertTrue(rejection('').contains('no search terms'))
        assertTrue(rejection('  |  ').contains('no search terms'))
        assertTrue(rejection('x' * 129).contains('longer than 128 characters'))
        assertTrue(rejection('ab\u0007cd').contains('control characters'))
        assertTrue(rejection((1..11).collect { "term${it}" }.join('|')).contains('at most 10 terms'))
        assertTrue(rejection('*').contains("term '*' needs at least 2 characters"))
        assertTrue(rejection('a*').contains("term 'a*' needs at least 2 characters"))
    }

    void testRenderReplacesPlaceholdersAndEncodesTerms() {
        List<String> terms = ['mkwiat*', '*daszew*']
        String script = JenkinsLdapSearchScript.render(terms, 25, 30000)
        assertFalse(script.contains('__TERMS__'))
        assertFalse(script.contains('__LIMIT__'))
        assertFalse(script.contains('__TIMEOUT_MS__'))
        assertFalse(script.contains('mkwiat*'))
        assertTrue(script.contains('int limit = 25'))
        assertTrue(script.contains('int timeoutMillis = 30000'))
        String encoded = (script =~ /decode\('([A-Za-z0-9+\/=]+)'\)/)[0][1]
        String decoded = new String(Base64.decoder.decode(encoded), StandardCharsets.UTF_8)
        assertEquals(terms, new JsonSlurper().parseText(decoded))
    }

    private String rejection(String query) {
        shouldFail(IllegalArgumentException) {
            JenkinsLdapSearchScript.terms(query, 128)
        }
    }
}

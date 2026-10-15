package ai.worklog.framework.jenkins

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.util.Base64

class JenkinsLdapSearchScript {
    static final int MAX_TERMS = 10
    static final int MIN_TERM_CHARACTERS = 2

    private static final String TEMPLATE = '''
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.naming.Context
import javax.naming.PartialResultException
import javax.naming.SizeLimitExceededException
import javax.naming.directory.InitialDirContext
import javax.naming.directory.SearchControls

def terms = new JsonSlurper().parseText(
    new String(Base64.decoder.decode('__TERMS__'), StandardCharsets.UTF_8)
)
int limit = __LIMIT__
int timeoutMillis = __TIMEOUT_MS__

def attributeValue = { attributes, String name ->
    def attribute = attributes?.get(name)
    attribute ? attribute.get()?.toString() : null
}

try {
    def instance = jenkins.model.Jenkins.get()
    def realm = instance.securityRealm
    if (realm?.class?.name != 'hudson.security.LDAPSecurityRealm') {
        print JsonOutput.toJson([status: 'error', code: 'realm_not_ldap', realm: realm?.class?.name ?: ''])
        return
    }
    String displayAttribute = realm.displayNameAttributeName ?: 'displayName'
    String mailAttribute = realm.mailAddressAttributeName ?: 'mail'
    def items = []
    def seen = [] as Set
    boolean truncated = false
    for (def configuration : realm.configurations) {
        if (items.size() > limit) {
            break
        }
        String providerUrl = (configuration.server ?: '').toString().split(' ')
            .collect { it.trim() }
            .findAll { it }
            .collect { it.contains('://') ? it : 'ldap://' + it }
            .join(' ')
        def matcher = (configuration.userSearch ?: '').toString() =~ /([A-Za-z][A-Za-z0-9-]*)=[{]0[}]/
        String loginAttribute = matcher.find() ? matcher.group(1) : 'uid'
        def attributes = [loginAttribute, displayAttribute, mailAttribute, 'cn'].unique { it.toLowerCase() }
        String filter = '(&(objectClass=person)(|' +
            terms.collect { term -> attributes.collect { "(${it}=${term})" }.join('') }.join('') +
            '))'
        String base = [configuration.userSearchBase, configuration.rootDN]
            .collect { it?.toString()?.trim() }
            .findAll { it }
            .join(',')
        def environment = new Hashtable()
        environment.put(Context.INITIAL_CONTEXT_FACTORY, 'com.sun.jndi.ldap.LdapCtxFactory')
        environment.put(Context.PROVIDER_URL, providerUrl)
        environment.put(Context.REFERRAL, 'ignore')
        environment.put('com.sun.jndi.ldap.connect.timeout', timeoutMillis.toString())
        environment.put('com.sun.jndi.ldap.read.timeout', timeoutMillis.toString())
        if (configuration.managerDN) {
            environment.put(Context.SECURITY_AUTHENTICATION, 'simple')
            environment.put(Context.SECURITY_PRINCIPAL, configuration.managerDN.toString())
            environment.put(Context.SECURITY_CREDENTIALS, configuration.managerPasswordSecret?.plainText ?: '')
        } else {
            environment.put(Context.SECURITY_AUTHENTICATION, 'none')
        }
        def context = new InitialDirContext(environment)
        try {
            def controls = new SearchControls()
            controls.searchScope = SearchControls.SUBTREE_SCOPE
            controls.countLimit = (long) (limit + 1)
            controls.timeLimit = timeoutMillis
            controls.returningAttributes = attributes as String[]
            def results = context.search(base, filter, controls)
            try {
                while (results.hasMore()) {
                    def result = results.next()
                    String dn = result.nameInNamespace
                    if (!seen.add(dn.toLowerCase())) {
                        continue
                    }
                    def values = result.attributes
                    String login = attributeValue(values, loginAttribute)
                    items << [
                        login: login,
                        display_name: attributeValue(values, displayAttribute),
                        mail: attributeValue(values, mailAttribute),
                        dn: dn,
                        server: providerUrl,
                        jenkins_user: login ? hudson.model.User.getById(login, false) != null : false
                    ]
                    if (items.size() > limit) {
                        break
                    }
                }
            } catch (SizeLimitExceededException ignored) {
                truncated = true
            } catch (PartialResultException ignored) {
            }
        } finally {
            context.close()
        }
    }
    if (items.size() > limit) {
        truncated = true
        items = items.take(limit)
    }
    print JsonOutput.toJson([status: 'ok', realm: realm.class.name, items: items, truncated: truncated])
} catch (Exception exception) {
    print JsonOutput.toJson([status: 'error', code: 'search_failed', exception_class: exception.class.name])
}
'''

    static List<String> terms(String query, int maxLength) {
        String text = query ?: ''
        if (text.length() > maxLength) {
            throw new IllegalArgumentException("Invalid query: longer than ${maxLength} characters")
        }
        if (text.toCharArray().any { Character.isISOControl(it) }) {
            throw new IllegalArgumentException('Invalid query: control characters are not allowed')
        }
        List<String> parts = text.tokenize('|')*.trim().findAll { it }
        if (!parts) {
            throw new IllegalArgumentException('Invalid query: no search terms')
        }
        if (parts.size() > MAX_TERMS) {
            throw new IllegalArgumentException("Invalid query: at most ${MAX_TERMS} terms")
        }
        parts.collect { String term ->
            if (term.replace('*', '').length() < MIN_TERM_CHARACTERS) {
                throw new IllegalArgumentException(
                    "Invalid query: term '${term}' needs at least ${MIN_TERM_CHARACTERS} characters besides *"
                )
            }
            String escaped = term
                .replace('\\', '\\5c')
                .replace('(', '\\28')
                .replace(')', '\\29')
            term.contains('*') ? escaped : "*${escaped}*".toString()
        }
    }

    static String render(List<String> terms, int limit, int ldapTimeoutMillis) {
        TEMPLATE
            .replace('__TERMS__', encode(JsonOutput.toJson(terms)))
            .replace('__LIMIT__', limit.toString())
            .replace('__TIMEOUT_MS__', ldapTimeoutMillis.toString())
    }

    private static String encode(String value) {
        Base64.encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))
    }
}

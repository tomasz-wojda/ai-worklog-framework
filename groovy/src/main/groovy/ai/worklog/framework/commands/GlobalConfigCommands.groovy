package ai.worklog.framework.commands

import ai.worklog.framework.core.ExitCodes
import ai.worklog.framework.core.GlobalConfig

class GlobalConfigCommands {
    static int run(String action, List<String> args, File frameworkRoot) {
        ExitCodes exitCodes = new ExitCodes(frameworkRoot)
        if (!action) {
            println 'Usage: ai-worklog config {show|runtime|set-ai-vault-root}'
            return exitCodes.userError
        }
        List<String> remaining = new ArrayList<>(args)
        boolean json = remaining.remove('--json')
        try {
            switch (action) {
                case 'show':
                    return render(GlobalConfig.showConfiguration(), json, exitCodes)
                case 'runtime':
                    if (!remaining) {
                        return render(GlobalConfig.showRuntime(), json, exitCodes)
                    }
                    if (remaining.size() > 1) {
                        throw new IllegalArgumentException('Unexpected arguments for config runtime')
                    }
                    return render(GlobalConfig.setRuntime(remaining[0]), json, exitCodes)
                case 'set-ai-vault-root':
                    if (!remaining) {
                        throw new IllegalArgumentException('Missing path for set-ai-vault-root')
                    }
                    return render(GlobalConfig.setAiVaultRoot(remaining[0]), json, exitCodes)
                default:
                    println 'Usage: ai-worklog config {show|runtime|set-ai-vault-root}'
                    return exitCodes.userError
            }
        } catch (IllegalArgumentException exception) {
            if (json) {
                GlobalConfig.printJson([
                    operation: action,
                    status: 'error',
                    message: exception.message
                ])
            } else {
                println exception.message
            }
            return exitCodes.userError
        }
    }

    private static int render(Map payload, boolean json, ExitCodes exitCodes) {
        if (json) {
            GlobalConfig.printJson(payload)
        } else {
            renderHuman(payload)
        }
        exitCodes.success
    }

    private static void renderHuman(Map payload) {
        switch (payload.operation) {
            case 'show':
                println "Global configuration (${GlobalConfig.configFile().path}):"
                println "  version: ${payload.version}"
                println "  runtime: ${payload.runtime}"
                println "  AI Vault root: ${payload.ai_vault_root ?: 'none'}"
                println "  default workspace: ${payload.default_workspace ?: 'none'}"
                if (payload.workspaces) {
                    println '  workspaces:'
                    payload.workspaces.each { Map entry ->
                        println "    ${entry.name}  ${entry.path}${availabilitySuffix(entry)}${defaultSuffix(entry)}${idesSuffix(entry)}"
                    }
                } else {
                    println '  workspaces: none'
                }
                break
            case 'runtime':
                println "Runtime: ${payload.runtime}"
                break
            case 'ai_vault_root':
                println "AI Vault Root: ${payload.ai_vault_root ?: 'none'}"
                break
        }
    }

    private static String availabilitySuffix(Map entry) {
        entry.available ? ' [available]' : ' [missing]'
    }

    private static String defaultSuffix(Map entry) {
        entry.default ? ' [default]' : ''
    }

    private static String idesSuffix(Map entry) {
        List ides = entry.ides instanceof List ? (List) entry.ides*.toString() : []
        ides ? "  ides: ${ides.join(', ')}" : '  ides: none'
    }
}

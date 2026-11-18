/**
 * Tool activity + native JDT/PDE tooling UI. Every invocation goes through the backend
 * tool endpoint (the single ToolEngine); nothing is analyzed in the browser.
 */
window.DCodeTooling = (function () {
    const JAVA_TOOLS = [
        { name: 'java_project_structure', desc: 'Discover Java projects and classpaths',
            args: [{ k: 'repository', label: 'Repository', required: true }] },
        { name: 'java_get_diagnostics', desc: 'Eclipse batch-compiler diagnostics',
            args: [{ k: 'repository', label: 'Repository', required: true },
                { k: 'module', label: 'Module (relative)', required: false }] },
        { name: 'java_ast_summary', desc: 'AST with resolved bindings',
            args: [{ k: 'path', label: 'File path', required: true }] },
        { name: 'java_resolve_type', desc: 'Resolve a type by name',
            args: [{ k: 'repository', label: 'Repository', required: true },
                { k: 'module', label: 'Module', required: false },
                { k: 'type', label: 'Fully qualified type', required: true }] },
        { name: 'java_type_hierarchy', desc: 'Supertype / subtype hierarchy',
            args: [{ k: 'repository', label: 'Repository', required: true },
                { k: 'module', label: 'Module', required: false },
                { k: 'type', label: 'Fully qualified type', required: true }] },
        { name: 'java_call_hierarchy', desc: 'Callers of a method',
            args: [{ k: 'path', label: 'File path', required: true },
                { k: 'method', label: 'Method name', required: true }] },
        { name: 'java_find_references', desc: 'References of a type or method',
            args: [{ k: 'repository', label: 'Repository', required: true },
                { k: 'module', label: 'Module', required: false },
                { k: 'kind', label: 'Kind (type|method)', required: true },
                { k: 'name', label: 'Type FQN or Type#method', required: true }] },
        { name: 'java_find_implementations', desc: 'Implementors of a type or method',
            args: [{ k: 'repository', label: 'Repository', required: true },
                { k: 'module', label: 'Module', required: false },
                { k: 'kind', label: 'Kind (type|method)', required: true },
                { k: 'name', label: 'Type FQN or Type#method', required: true }] },
        { name: 'java_analyze_imports', desc: 'Import resolution and usage',
            args: [{ k: 'path', label: 'File path', required: true }] },
        { name: 'java_analyze_dependencies', desc: 'Maven / OSGi dependencies',
            args: [{ k: 'repository', label: 'Repository', required: true },
                { k: 'module', label: 'Module', required: false }] },
        { name: 'pde_inspect_bundle', desc: 'OSGi manifest + plugin.xml inspection',
            args: [{ k: 'path', label: 'Bundle directory', required: true }] },
        { name: 'pde_analyze_dependencies', desc: 'Cross-bundle package resolution',
            args: [{ k: 'repository', label: 'Repository', required: true }] },
        { name: 'java_rename', desc: 'Rename a type, method or field (mutating)',
            mutating: true,
            args: [{ k: 'path', label: 'File path', required: true },
                { k: 'kind', label: 'Kind (type|method|field)', required: true },
                { k: 'name', label: 'Current name', required: true },
                { k: 'newName', label: 'New name', required: true }] }
    ];

    function render(container) {
        container.innerHTML = '';
        const grid = document.createElement('div');
        grid.className = 'tool-btn-grid';
        for (const tool of JAVA_TOOLS) {
            const btn = document.createElement('button');
            btn.className = 'tool-btn' + (tool.mutating ? ' mutating' : '');
            btn.innerHTML = '<span class="t-name">' + tool.name + '</span>'
                + '<span class="t-desc">' + tool.desc + '</span>';
            btn.onclick = () => invoke(tool);
            grid.appendChild(btn);
        }
        container.appendChild(grid);

        const section = document.createElement('div');
        section.className = 'section-title';
        section.textContent = 'Generic tool invocation (all registered tools)';
        container.appendChild(section);
        const generic = document.createElement('button');
        generic.className = 'btn';
        generic.style.margin = '0 10px 10px';
        generic.textContent = 'Invoke any tool…';
        generic.onclick = () => DCodeApp.openModal('generic-tool');
        container.appendChild(generic);
    }

    function invoke(tool) {
        if (tool.mutating) {
            DCodeApp.openModal('rename-confirm', tool);
            return;
        }
        DCodeApp.openModal('java-tool', tool);
    }

    async function runJavaTool(toolName, args) {
        const session = DCodeApp.activeSession;
        if (!session) {
            DCodeApp.logConsole('error', 'Select a session first (tools execute inside a session).');
            return;
        }
        DCodeApp.logConsole('tool', toolName + ' ' + JSON.stringify(args));
        try {
            const payload = Object.assign({ sessionId: session.id }, args || {});
            const response = await DCodeApi.post('/api/v1/tools/' + toolName + '/execute', payload);
            DCodeApp.onToolResult(toolName, response);
            return { ok: true, response };
        } catch (e) {
            DCodeApp.logConsole('error', toolName + ' failed: ' + e.message);
            return { ok: false, error: e.message };
        }
    }

    async function saveFile(workspaceId, path, content) {
        const session = DCodeApp.activeSession;
        if (!session) {
            DCodeApp.logConsole('error', 'Select a session first.');
            return { ok: false, error: 'no active session' };
        }
        try {
            // Editor save = full-file write through the audited write_file tool
            // (path containment + repository policy enforced by the backend).
            const response = await DCodeApi.post('/api/v1/tools/write_file/execute', {
                sessionId: session.id,
                path: path,
                content: content
            });
            const call = response.toolCall || {};
            if (call.errorMessage) {
                DCodeApp.logConsole('error', 'save failed: ' + call.errorMessage);
                return { ok: false, error: call.errorMessage };
            }
            return { ok: true, response };
        } catch (e) {
            DCodeApp.logConsole('error', 'save failed: ' + e.message);
            return { ok: false, error: e.message };
        }
    }

    return { render, runJavaTool, saveFile, JAVA_TOOLS };
})();

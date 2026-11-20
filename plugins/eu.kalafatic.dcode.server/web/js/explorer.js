/**
 * Workspace / repository / file explorer. Backend-authoritative: every node comes from
 * the tree endpoint; the frontend keeps no project records of its own.
 */
window.DCodeExplorer = (function () {
    let root = null;
    let expanded = new Set();

    async function render(container) {
        root = container;
        container.innerHTML = '';
        const loading = document.createElement('div');
        loading.className = 'empty-note';
        loading.style.padding = '10px';
        loading.textContent = 'Loading workspaces…';
        container.appendChild(loading);
        try {
            const workspaces = await DCodeApi.get('/api/v1/workspaces');
            container.innerHTML = '';
            if (!workspaces || workspaces.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'empty-note';
                empty.style.padding = '10px';
                empty.textContent = 'No workspaces. Create one via the toolbar.';
                container.appendChild(empty);
                const create = document.createElement('button');
                create.className = 'btn';
                create.textContent = 'Create workspace';
                create.onclick = () => DCodeApp.openModal('new-workspace');
                container.appendChild(create);
                return;
            }
            const ul = document.createElement('ul');
            ul.className = 'tree';
            for (const workspace of workspaces) {
                ul.appendChild(await workspaceNode(workspace));
            }
            container.appendChild(ul);
        } catch (e) {
            container.innerHTML = '';
            const error = document.createElement('div');
            error.className = 'warning-box';
            error.textContent = 'Workspaces could not be loaded.';
            container.appendChild(error);
            const detail = document.createElement('div');
            detail.className = 'muted mono';
            detail.style.padding = '6px 10px';
            detail.textContent = e.status ? 'HTTP ' + e.status : e.message;
            container.appendChild(detail);
            const retry = document.createElement('button');
            retry.className = 'btn';
            retry.style.margin = '6px 10px';
            retry.textContent = 'Retry';
            retry.onclick = () => render(container);
            container.appendChild(retry);
            const conn = document.createElement('button');
            conn.className = 'btn';
            conn.textContent = 'Connection details';
            conn.onclick = () => DCodeApp.openModal('connection-details');
            container.appendChild(conn);
            DCodeApp.reportError('list workspaces', e);
        }
    }

    async function workspaceNode(workspace) {
        const li = document.createElement('li');
        const item = document.createElement('div');
        item.className = 'tree-item';
        item.innerHTML = '<span class="tree-icon dir">▸</span>'
            + '<span>' + escapeHtml(workspace.name) + '</span>'
            + '<span class="muted mono" style="margin-left:auto">' + workspace.repositories.length + ' repo(s)</span>';
        item.onclick = () => {
            const key = 'ws:' + workspace.id;
            if (expanded.has(key)) {
                expanded.delete(key);
            } else {
                expanded.add(key);
            }
            render(root);
        };
        item.oncontextmenu = (event) => {
            DCodeApp.showContextMenu(event, [
                { label: 'Refresh', action: () => render(root) },
                { label: 'Open workspace', action: () => DCodeApp.openWorkspace(workspace) }
            ]);
        };
        li.appendChild(item);
        const key = 'ws:' + workspace.id;
        if (expanded.has(key)) {
            const children = document.createElement('ul');
            for (const repo of workspace.repositories) {
                children.appendChild(await repositoryNode(workspace, repo));
            }
            li.appendChild(children);
        }
        return li;
    }

    async function repositoryNode(workspace, repository) {
        const li = document.createElement('li');
        const item = document.createElement('div');
        item.className = 'tree-item';
        const readonly = repository.accessPolicy === 'READ_ONLY'
            ? ' <span class="badge" title="read only">RO</span>' : '';
        item.innerHTML = '<span class="tree-icon dir">▸</span>'
            + '<span class="mono">' + escapeHtml(repository.name) + '</span>' + readonly;
        item.onclick = () => {
            const key = 'repo:' + workspace.id + ':' + repository.id;
            if (expanded.has(key)) {
                expanded.delete(key);
            } else {
                expanded.add(key);
            }
            render(root);
        };
        item.oncontextmenu = (event) => {
            DCodeApp.showContextMenu(event, [
                { label: 'Refresh', action: () => render(root) },
                {
                    label: 'Open in editor tree', action: () => {
                        DCodeApp.openTab('files:' + workspace.id, 'Files — ' + repository.name,
                            () => DCodeExplorer.renderFileTree(workspace));
                    }
                }
            ]);
        };
        li.appendChild(item);
        const key = 'repo:' + workspace.id + ':' + repository.id;
        if (expanded.has(key)) {
            li.appendChild(await fileTreeRoot(workspace, repository.name));
        }
        return li;
    }

    async function fileTreeRoot(workspace, repositoryName) {
        const wrapper = document.createElement('ul');
        wrapper.innerHTML = '<li><span class="muted">loading…</span></li>';
        const li = document.createElement('li');
        li.appendChild(await renderTreeLevel(workspace, '', repositoryName));
        return li;
    }

    async function renderTreeLevel(workspace, path, repositoryName) {
        const ul = document.createElement('ul');
        try {
            const query = '?depth=1' + (path ? '&path=' + encodeURIComponent(path) : '');
            const data = await DCodeApi.get('/api/v1/workspaces/' + workspace.id + '/tree' + query);
            for (const entry of data.entries || []) {
                const li = document.createElement('li');
                const item = document.createElement('div');
                item.className = 'tree-item';
                item.innerHTML = '<span class="tree-icon ' + (entry.type === 'dir' ? 'dir' : '') + '">'
                    + (entry.type === 'dir' ? '▸' : '·') + '</span>'
                    + '<span class="mono">' + escapeHtml(entry.name) + '</span>';
                if (entry.type === 'dir') {
                    item.onclick = () => toggleDir(li, workspace, entry.path, repositoryName);
                } else {
                    item.onclick = () => openFile(workspace, entry.path, repositoryName);
                }
                item.oncontextmenu = (event) => {
                    DCodeApp.showContextMenu(event, [
                        { label: 'View file', action: () => openFile(workspace, entry.path, repositoryName) },
                        { label: 'AST summary', action: () => DCodeTooling.runJavaTool('java_ast_summary', { path: entry.path }) },
                        { label: 'Analyze imports', action: () => DCodeTooling.runJavaTool('java_analyze_imports', { path: entry.path }) },
                        { label: 'Find references (type)', action: () => DCodeTooling.runJavaTool('java_find_references', { repository: repositoryName, kind: 'type', name: entry.name.replace(/\.java$/, '') }) }
                    ]);
                };
                li.appendChild(item);
                ul.appendChild(li);
            }
        } catch (e) {
            DCodeApp.logConsole('error', 'tree: ' + e.message);
        }
        return ul;
    }

    function toggleDir(li, workspace, path, repositoryName) {
        const existing = li.querySelector('ul');
        if (existing) {
            existing.remove();
            return;
        }
        const child = document.createElement('ul');
        child.innerHTML = '<li><span class="muted">loading…</span></li>';
        li.appendChild(child);
        renderTreeLevel(workspace, path, repositoryName).then((level) => {
            child.replaceWith(level);
        });
    }

    async function openFile(workspace, path, repositoryName) {
        try {
            const data = await DCodeApi.get('/api/v1/workspaces/' + workspace.id
                + '/files?path=' + encodeURIComponent(path));
            DCodeEditor.open(workspace, repositoryName, path, data.content);
        } catch (e) {
            DCodeApp.logConsole('error', 'file: ' + e.message);
        }
    }

    async function renderFileTree(workspace) {
        const container = document.createElement('div');
        container.className = 'flow';
        const toolbar = document.createElement('div');
        toolbar.className = 'toolbar';
        const refresh = document.createElement('button');
        refresh.className = 'btn btn-small';
        refresh.textContent = 'Refresh';
        refresh.onclick = () => {
            container.innerHTML = '';
            renderFileTree(workspace);
        };
        toolbar.appendChild(refresh);
        container.appendChild(toolbar);
        for (const repo of workspace.repositories) {
            const section = document.createElement('div');
            section.className = 'section-title';
            section.textContent = repo.name;
            container.appendChild(section);
            container.appendChild(await renderTreeLevel(workspace, '', repo.name));
        }
        return container;
    }

    function escapeHtml(text) {
        return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    return { render, renderFileTree, escapeHtml };
})();

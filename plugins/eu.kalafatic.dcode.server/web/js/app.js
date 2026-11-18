/**
 * DCode Web application shell: navigation, tabs, modals, SSE wiring and state.
 * The backend is authoritative; this module only presents it.
 */
window.DCodeApp = (function () {
    const state = {
        view: 'workspace',
        tabs: new Map(),   // tabId -> {title, render}
        activeTab: null,
        workspace: null,
        session: null,     // currently OPEN session (detail view)
        activeSession: null, // session used for tool executions
        seenEventIds: new Set()
    };

    function init() {
        wireNavigation();
        wireBottomTabs();
        renderView('workspace');
        applyAuth();
    }

    function applyAuth() {
        const btn = document.getElementById('btn-auth');
        btn.onclick = () => openModal('auth');
        if (DCodeApi.hasToken()) {
            btn.textContent = 'auth ✓';
        }
    }

    function setConnectionStatus(status, detail) {
        const el = document.getElementById('connection-status');
        const map = {
            connected: ['conn conn-connected', '● live'],
            reconnecting: ['conn conn-reconnecting', '● reconnecting'],
            disconnected: ['conn conn-disconnected', '● offline']
        };
        const entry = map[status] || map.disconnected;
        el.className = entry[0];
        el.textContent = entry[1];
        el.title = detail || status;
    }

    function wireNavigation() {
        document.querySelectorAll('.nav-btn').forEach((btn) => {
            btn.onclick = () => {
                document.querySelectorAll('.nav-btn').forEach((b) => b.classList.remove('active'));
                btn.classList.add('active');
                renderView(btn.dataset.view);
            };
        });
    }

    function wireBottomTabs() {
        document.querySelectorAll('.bottom-tab').forEach((tab) => {
            tab.onclick = () => {
                document.querySelectorAll('.bottom-tab').forEach((t) => t.classList.remove('active'));
                document.querySelectorAll('.bottom-pane').forEach((p) => p.classList.remove('active'));
                tab.classList.add('active');
                document.getElementById('panel-' + tab.dataset.panel).classList.add('active');
            };
        });
    }

    function renderView(view) {
        state.view = view;
        const content = document.getElementById('left-content');
        if (view === 'workspace') {
            DCodeExplorer.render(content);
        } else if (view === 'sessions') {
            DCodeSessions.render(content);
        } else if (view === 'tools') {
            DCodeTooling.render(content);
        } else if (view === 'activity') {
            renderActivity(content);
        }
    }

    function renderActivity(container) {
        container.innerHTML = '';
        const head = document.createElement('div');
        head.className = 'section-title';
        head.textContent = 'Tool activity (live)';
        container.appendChild(head);
        const list = document.createElement('div');
        list.id = 'activity-list';
        list.className = 'flow';
        list.style.padding = '8px';
        container.appendChild(list);
        const note = document.createElement('div');
        note.className = 'empty-note';
        note.textContent = 'Tool calls appear here as they execute.';
        list.appendChild(note);
    }

    function appendActivity(toolCall) {
        const list = document.getElementById('activity-list');
        if (!list) {
            return;
        }
        const empty = list.querySelector('.empty-note');
        if (empty) {
            empty.remove();
        }
        const card = document.createElement('div');
        card.className = 'tool-call' + (toolCall.errorMessage ? ' failed' : '');
        const head = document.createElement('div');
        head.className = 'tool-call-head';
        head.innerHTML = '<span class="name">' + escapeHtml(toolCall.toolName) + '</span>'
            + '<span class="badge">' + toolCall.state + '</span>'
            + '<span class="meta">' + (toolCall.startedAt || '').slice(11, 19) + '</span>';
        card.appendChild(head);
        const body = document.createElement('div');
        body.className = 'tool-call-body';
        if (toolCall.errorMessage) {
            const error = document.createElement('div');
            error.className = 'log-error';
            error.textContent = toolCall.errorMessage;
            body.appendChild(error);
        }
        head.onclick = () => card.classList.toggle('open');
        list.insertBefore(card, list.firstChild);
        if (list.children.length > 100) {
            list.removeChild(list.lastChild);
        }
    }

    function openWorkspace(workspace) {
        state.workspace = workspace;
        document.getElementById('top-workspace').textContent = 'workspace: ' + workspace.name;
    }

    function openSession(session) {
        state.session = session;
        state.activeSession = session;
        document.getElementById('top-session').textContent = 'session: '
            + (session.title || session.id);
        setConnectionStatus('reconnecting', 'connecting to session');
        DCodeSse.connect(session.id, onSessionEvent, setConnectionStatus);
        openTab('session:' + session.id, 'Session — ' + (session.title || session.id),
            () => DCodeSessions.renderDetail(session));
    }

    function onSessionEvent(type, dataJson) {
        logEvent(type, dataJson);
        let data = {};
        try {
            data = JSON.parse(dataJson);
        } catch (e) { /* opaque payload */ }
        if (type === 'SESSION_STATE_CHANGED') {
            refreshSessionDetail();
        } else if (type === 'MESSAGE_APPENDED') {
            refreshSessionMessages();
        } else if (type === 'TOOL_CALL_COMPLETED') {
            if (state.session && data.callId) {
                refreshSessionMessages();
            }
        }
    }

    function refreshSessionDetail() {
        if (!state.session) {
            return;
        }
        DCodeApi.get('/api/v1/sessions/' + state.session.id).then((fresh) => {
            state.session = fresh;
            state.activeSession = fresh;
            const tab = state.tabs.get('session:' + fresh.id);
            if (tab && state.activeTab === 'session:' + fresh.id) {
                renderTab('session:' + fresh.id);
            }
        }).catch(() => { /* session may be gone */ });
    }

    function refreshSessionMessages() {
        if (!state.session) {
            return;
        }
        const container = document.getElementById('session-messages-' + state.session.id);
        if (!container) {
            return;
        }
        DCodeApi.get('/api/v1/sessions/' + state.session.id + '/messages').then((messages) => {
            container.innerHTML = '';
            for (const message of messages) {
                DCodeSessions.appendMessage(container, message);
            }
        }).catch(() => { /* best effort */ });
    }

    function onToolResult(toolName, response) {
        const call = response.toolCall || {};
        logConsole('tool', toolName + ' → ' + (call.state || '?'));
        appendActivity(call);
        const result = response.result;
        if (result && !result.success) {
            logConsole('error', toolName + ': ' + (result.error || 'failed'));
            return;
        }
        openTab('tool:' + toolName + ':' + Date.now(), 'Result — ' + toolName,
            () => renderToolResult(toolName, response));
    }

    function renderToolResult(toolName, response) {
        const container = document.createElement('div');
        container.className = 'flow';
        const head = document.createElement('div');
        head.className = 'section-title';
        head.textContent = toolName + ' — structured result';
        container.appendChild(head);
        const pre = document.createElement('pre');
        pre.className = 'result';
        pre.textContent = JSON.stringify(response.result, null, 2);
        container.appendChild(pre);
        const rawHead = document.createElement('div');
        rawHead.className = 'section-title';
        rawHead.textContent = 'Raw output';
        container.appendChild(rawHead);
        const raw = document.createElement('pre');
        raw.className = 'result';
        raw.textContent = response.output || '';
        container.appendChild(raw);
        return container;
    }

    function logConsole(kind, message) {
        const pane = document.getElementById('panel-console');
        const line = document.createElement('div');
        line.className = 'log-line log-' + kind;
        const ts = document.createElement('span');
        ts.className = 'ts';
        ts.textContent = new Date().toISOString().slice(11, 19) + ' ';
        line.appendChild(ts);
        line.appendChild(document.createTextNode(message));
        pane.appendChild(line);
        while (pane.children.length > 500) {
            pane.removeChild(pane.firstChild);
        }
        pane.scrollTop = pane.scrollHeight;
    }

    function logEvent(type, dataJson) {
        const pane = document.getElementById('panel-events');
        const line = document.createElement('div');
        line.className = 'log-line';
        const ts = document.createElement('span');
        ts.className = 'ts';
        ts.textContent = new Date().toISOString().slice(11, 19) + ' ';
        line.appendChild(ts);
        const typeSpan = document.createElement('span');
        typeSpan.className = 'log-tool';
        typeSpan.textContent = type + ' ';
        line.appendChild(typeSpan);
        line.appendChild(document.createTextNode(dataJson || '{}'));
        pane.appendChild(line);
        while (pane.children.length > 500) {
            pane.removeChild(pane.firstChild);
        }
        pane.scrollTop = pane.scrollHeight;
    }

    function openTab(id, title, render) {
        state.tabs.set(id, { title, render });
        state.activeTab = id;
        renderTabs();
    }

    function closeTab(id) {
        state.tabs.delete(id);
        if (state.activeTab === id) {
            state.activeTab = state.tabs.size > 0
                ? state.tabs.keys().next().value : null;
        }
        renderTabs();
    }

    function renderTabs() {
        const bar = document.getElementById('tabs-bar');
        bar.innerHTML = '';
        for (const [id, tab] of state.tabs) {
            const el = document.createElement('div');
            el.className = 'tab' + (id === state.activeTab ? ' active' : '');
            const label = document.createElement('span');
            label.textContent = tab.title;
            el.appendChild(label);
            const close = document.createElement('span');
            close.className = 'tab-close';
            close.textContent = '✕';
            close.onclick = (event) => {
                event.stopPropagation();
                closeTab(id);
            };
            el.appendChild(close);
            el.onclick = () => {
                state.activeTab = id;
                renderTabs();
            };
            bar.appendChild(el);
        }
        renderTab(state.activeTab);
    }

    function renderTab(id) {
        const content = document.getElementById('tab-content');
        const tab = state.tabs.get(id);
        if (!tab) {
            content.innerHTML = '<div class="placeholder"><div class="placeholder-text">'
                + 'No tab open.</div></div>';
            return;
        }
        content.innerHTML = '';
        Promise.resolve(tab.render()).then((rendered) => {
            if (rendered instanceof Node) {
                content.appendChild(rendered);
            }
        });
    }

    function showContextMenu(event, items) {
        const menu = document.getElementById('context-menu');
        menu.innerHTML = '';
        items.forEach((item) => {
            if (item === '-') {
                const sep = document.createElement('div');
                sep.className = 'menu-sep';
                menu.appendChild(sep);
                return;
            }
            const el = document.createElement('div');
            el.className = 'menu-item' + (item.danger ? ' danger' : '');
            el.textContent = item.label;
            el.onclick = () => {
                hideContextMenu();
                item.action();
            };
            menu.appendChild(el);
        });
        menu.classList.remove('hidden');
        menu.style.left = Math.min(event.clientX, window.innerWidth - 220) + 'px';
        menu.style.top = Math.min(event.clientY, window.innerHeight - 200) + 'px';
        event.preventDefault();
    }

    function hideContextMenu() {
        document.getElementById('context-menu').classList.add('hidden');
    }

    document.addEventListener('click', hideContextMenu);

    // ---------- modals ----------

    function openModal(kind, payload) {
        const root = document.getElementById('modal-root');
        const overlay = document.createElement('div');
        overlay.className = 'overlay';
        const modal = document.createElement('div');
        modal.className = 'modal';
        overlay.appendChild(modal);
        overlay.onclick = (event) => {
            if (event.target === overlay) {
                overlay.remove();
            }
        };
        root.appendChild(overlay);
        buildModal(kind, modal, overlay, payload);
    }

    function buildModal(kind, modal, overlay, payload) {
        if (kind === 'auth') {
            modalTitle(modal, 'Server token', overlay);
            const body = modalBody(modal);
            const field = document.createElement('div');
            field.className = 'field';
            field.innerHTML = '<label>Bearer token (leave empty for development mode)</label>'
                + '<input type="password" id="auth-token" placeholder="token">';
            body.appendChild(field);
            modalActions(modal, [{
                label: 'Apply', primary: true,
                action: () => {
                    DCodeApi.setToken(document.getElementById('auth-token').value || null);
                    document.getElementById('btn-auth').textContent = DCodeApi.hasToken() ? 'auth ✓' : 'auth';
                    overlay.remove();
                    renderView(state.view);
                }
            }]);
            return;
        }
        if (kind === 'new-workspace') {
            modalTitle(modal, 'Create workspace', overlay);
            const body = modalBody(modal);
            body.appendChild(field('Name', 'ws-name'));
            body.appendChild(field('Root path (optional)', 'ws-root'));
            modalActions(modal, [{
                label: 'Create', primary: true,
                action: async () => {
                    try {
                        const created = await DCodeApi.post('/api/v1/workspaces', {
                            name: document.getElementById('ws-name').value,
                            rootPath: document.getElementById('ws-root').value || null
                        });
                        logConsole('system', 'workspace created: ' + created.id);
                        overlay.remove();
                        renderView('workspace');
                    } catch (e) {
                        logConsole('error', e.message);
                    }
                }
            }]);
            return;
        }
        if (kind === 'new-session') {
            modalTitle(modal, 'New session', overlay);
            const body = modalBody(modal);
            body.appendChild(field('Title', 'sess-title'));
            const wsSelect = field('Workspace', 'sess-workspace');
            body.appendChild(wsSelect);
            const select = document.createElement('select');
            select.id = 'sess-workspace-select';
            wsSelect.appendChild(select);
            DCodeApi.get('/api/v1/workspaces').then((workspaces) => {
                for (const workspace of workspaces) {
                    const option = document.createElement('option');
                    option.value = workspace.id;
                    option.textContent = workspace.name;
                    select.appendChild(option);
                }
            });
            modalActions(modal, [{
                label: 'Create', primary: true,
                action: async () => {
                    try {
                        const created = await DCodeApi.post('/api/v1/sessions', {
                            title: document.getElementById('sess-title').value,
                            workspaceId: select.value
                        });
                        logConsole('system', 'session created: ' + created.id);
                        overlay.remove();
                        renderView('sessions');
                        openSession(created);
                    } catch (e) {
                        logConsole('error', e.message);
                    }
                }
            }]);
            return;
        }
        if (kind === 'submit-task') {
            const session = payload;
            modalTitle(modal, 'Submit task — ' + (session.title || session.id), overlay);
            const body = modalBody(modal);
            body.appendChild(field('Task instructions', 'task-text', 'textarea'));
            modalActions(modal, [{
                label: 'Submit', primary: true,
                action: async () => {
                    try {
                        await DCodeApi.post('/api/v1/sessions/' + session.id + '/start', {
                            task: document.getElementById('task-text').value
                        });
                        logConsole('system', 'task submitted to ' + session.id);
                        overlay.remove();
                        openSession(session);
                    } catch (e) {
                        logConsole('error', e.message);
                    }
                }
            }]);
            return;
        }
        if (kind === 'java-tool') {
            const tool = payload;
            modalTitle(modal, tool.name, overlay);
            const body = modalBody(modal);
            body.innerHTML += '<div class="muted" style="margin-bottom:10px">' + tool.desc + '</div>';
            tool.args.forEach((arg) => {
                body.appendChild(field(arg.label, 'arg-' + arg.k, 'text'));
            });
            modalActions(modal, [{
                label: 'Run', primary: true,
                action: async () => {
                    const args = {};
                    tool.args.forEach((arg) => {
                        const value = document.getElementById('arg-' + arg.k).value;
                        if (value) {
                            args[arg.k] = value;
                        }
                    });
                    overlay.remove();
                    await runJavaTool(tool.name, args);
                }
            }]);
            return;
        }
        if (kind === 'rename-confirm') {
            const tool = payload;
            modalTitle(modal, 'Rename — confirmation required', overlay);
            const body = modalBody(modal);
            const warning = document.createElement('div');
            warning.className = 'warning-box';
            warning.textContent = 'java_rename MUTATES source files: the target file is '
                + 'renamed and all references are updated by the JDT refactoring. '
                + 'Repositories marked READ_ONLY reject this operation.';
            body.appendChild(warning);
            tool.args.forEach((arg) => {
                body.appendChild(field(arg.label, 'arg-' + arg.k, 'text'));
            });
            modalActions(modal, [{
                label: 'Rename', primary: true, danger: true,
                action: async () => {
                    const args = {};
                    tool.args.forEach((arg) => {
                        const value = document.getElementById('arg-' + arg.k).value;
                        if (value) {
                            args[arg.k] = value;
                        }
                    });
                    overlay.remove();
                    await runJavaTool(tool.name, args);
                }
            }]);
            return;
        }
        if (kind === 'generic-tool') {
            modalTitle(modal, 'Invoke any tool', overlay);
            const body = modalBody(modal);
            body.appendChild(field('Tool name', 'generic-name', 'text'));
            body.appendChild(field('Arguments (JSON)', 'generic-args', 'textarea'));
            const hint = document.createElement('div');
            hint.className = 'muted';
            hint.textContent = 'Example: {"repository": "javarepo"} — sessionId is added automatically.';
            body.appendChild(hint);
            modalActions(modal, [{
                label: 'Execute', primary: true,
                action: async () => {
                    let args = {};
                    try {
                        args = JSON.parse(document.getElementById('generic-args').value || '{}');
                    } catch (e) {
                        logConsole('error', 'invalid JSON arguments');
                        return;
                    }
                    const name = document.getElementById('generic-name').value;
                    overlay.remove();
                    await runJavaTool(name, args);
                }
            }]);
            return;
        }
    }

    function modalTitle(modal, text, overlay) {
        const title = document.createElement('div');
        title.className = 'modal-title';
        title.innerHTML = '<span>' + escapeHtml(text) + '</span>'
            + '<span class="close" onclick="this.closest(\'.overlay\').remove()">✕</span>';
        modal.appendChild(title);
    }

    function modalBody(modal) {
        const body = document.createElement('div');
        body.className = 'modal-body';
        modal.appendChild(body);
        return body;
    }

    function modalActions(modal, actions) {
        const bar = document.createElement('div');
        bar.className = 'modal-actions';
        for (const action of actions) {
            const btn = document.createElement('button');
            btn.className = 'btn' + (action.primary ? ' btn-primary' : '')
                + (action.danger ? ' btn-danger' : '');
            btn.textContent = action.label;
            btn.onclick = action.action;
            bar.appendChild(btn);
        }
        modal.appendChild(bar);
    }

    function field(label, id, type) {
        const wrapper = document.createElement('div');
        wrapper.className = 'field';
        wrapper.innerHTML = '<label>' + escapeHtml(label) + '</label>';
        if (type === 'textarea') {
            wrapper.innerHTML += '<textarea id="' + id + '" rows="6"></textarea>';
        } else {
            wrapper.innerHTML += '<input type="text" id="' + id + '">';
        }
        return wrapper;
    }

    function escapeHtml(text) {
        return DCodeExplorer.escapeHtml(text);
    }

    return {
        init,
        openTab,
        openWorkspace,
        openSession,
        openModal,
        showContextMenu,
        logConsole,
        onToolResult,
        setConnectionStatus,
        get activeSession() { return state.activeSession; },
        get activeSessionObj() { return state.activeSession; }
    };
})();

document.addEventListener('DOMContentLoaded', () => DCodeApp.init());

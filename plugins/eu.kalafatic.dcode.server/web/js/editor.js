/** Source viewer + simple editor. Saving goes through the backend edit_file tool. */
window.DCodeEditor = (function () {
    let current = null; // {workspace, repository, path, content, dirty}

    function open(workspace, repositoryName, path, content) {
        current = { workspace, repository: repositoryName, path, content, dirty: false };
        DCodeApp.openTab('editor:' + path, path, render);
    }

    function render() {
        if (!current) {
            const empty = document.createElement('div');
            empty.className = 'empty-note';
            empty.textContent = 'No file open.';
            return empty;
        }
        const pane = document.createElement('div');
        pane.className = 'pane active';

        const head = document.createElement('div');
        head.className = 'editor-head';
        const filePath = document.createElement('span');
        filePath.className = 'file-path';
        filePath.textContent = current.path;
        head.appendChild(filePath);
        const editBtn = document.createElement('button');
        editBtn.className = 'btn btn-small';
        editBtn.textContent = 'Edit';
        editBtn.onclick = () => startEditing(pane, head);
        head.appendChild(editBtn);
        const saveBtn = document.createElement('button');
        saveBtn.className = 'btn btn-small btn-primary';
        saveBtn.textContent = 'Save (edit_file)';
        saveBtn.style.display = 'none';
        saveBtn.onclick = () => save(pane, saveBtn);
        head.appendChild(saveBtn);
        const readOnly = document.createElement('span');
        readOnly.className = 'badge';
        readOnly.textContent = current.workspace ? 'view' : 'view';
        head.appendChild(readOnly);
        pane.appendChild(head);

        const view = document.createElement('div');
        view.className = 'editor-view';
        pane.appendChild(view);
        renderLines(view);
        return pane;
    }

    function renderLines(view) {
        view.innerHTML = '';
        const wrapper = document.createElement('div');
        wrapper.className = 'editor-code';
        const lines = (current.content || '').split('\n');
        for (let i = 0; i < lines.length; i++) {
            const row = document.createElement('div');
            row.className = 'editor-line';
            const ln = document.createElement('span');
            ln.className = 'ln';
            ln.textContent = i + 1;
            row.appendChild(ln);
            const text = document.createElement('span');
            text.textContent = lines[i];
            row.appendChild(text);
            wrapper.appendChild(row);
        }
        view.appendChild(wrapper);
    }

    function startEditing(pane, head) {
        if (!DCodeApp.activeSession) {
            DCodeApp.logConsole('error', 'Saving requires an active session (edits run through the audited edit_file tool).');
            return;
        }
        const view = pane.querySelector('.editor-view');
        const textarea = document.createElement('textarea');
        textarea.className = 'editor-textarea';
        textarea.value = current.content || '';
        view.innerHTML = '';
        view.appendChild(textarea);
        current.dirty = true;
        head.querySelectorAll('button')[0].style.display = 'none';
        head.querySelectorAll('button')[1].style.display = '';
        head.querySelectorAll('button')[1].onclick = () => save(textarea, head);
    }

    async function save(textarea, head) {
        const newContent = textarea.value;
        const target = await DCodeTooling.saveFile(current.workspace.id, current.path, newContent);
        if (target.ok) {
            current.content = newContent;
            current.dirty = false;
            DCodeApp.logConsole('system', 'saved ' + current.path + ' via edit_file');
            const view = textarea.parentElement;
            head.querySelectorAll('button')[0].style.display = '';
            head.querySelectorAll('button')[1].style.display = 'none';
            renderLines(view);
        }
    }

    return { open, render };
})();

/** Unified diff renderer (simple, dependency-free). */
window.DCodeDiff = (function () {
    function render(diffText) {
        const container = document.createElement('div');
        container.className = 'diff';
        if (!diffText || !diffText.trim()) {
            const note = document.createElement('div');
            note.className = 'diff-meta';
            note.textContent = '(no tracked changes)';
            container.appendChild(note);
            return container;
        }
        const lines = diffText.split('\n');
        for (const line of lines) {
            const row = document.createElement('div');
            row.className = 'diff-line';
            const num = document.createElement('span');
            num.className = 'num';
            num.textContent = '\u00a0';
            if (line.startsWith('+++') || line.startsWith('---')) {
                row.classList.add('diff-meta');
                row.textContent = line;
                container.appendChild(row);
                continue;
            }
            if (line.startsWith('@@')) {
                row.classList.add('diff-hunk');
                row.textContent = line;
                container.appendChild(row);
                continue;
            }
            if (line.startsWith('+') && !line.startsWith('+++')) {
                row.classList.add('diff-add');
            } else if (line.startsWith('-') && !line.startsWith('---')) {
                row.classList.add('diff-del');
            } else if (line.startsWith('diff ') || line.startsWith('index ') || line.startsWith('new file') || line.startsWith('deleted file')) {
                row.classList.add('diff-meta');
            }
            row.appendChild(num);
            const text = document.createElement('span');
            text.textContent = line;
            row.appendChild(text);
            container.appendChild(row);
        }
        return container;
    }

    return { render };
})();

/**
 * DCode Model Manager — unified model pull/list/remove surface.
 *
 * All operations are delegated to the shared ENR model management service
 * through the DCode server API (/api/v1/models/...); DCode itself holds no
 * model management state. Download progress is polled every second from the
 * shared job store (ENR /api/v1/models/downloads/{jobId}).
 */
window.DCodeModelManager = (function () {
    const POLL_INTERVAL_MS = 1000;
    let pollTimer = null;

    function render(container) {
        container.innerHTML = '';
        stopPolling();

        const card = document.createElement('div');
        card.className = 'card';

        const title = document.createElement('div');
        title.className = 'item-title';
        title.textContent = 'Model Manager (shared ENR infrastructure)';
        card.appendChild(title);

        // provider + model + actions row
        const row = document.createElement('div');
        row.className = 'toolbar';

        const providerSelect = document.createElement('select');
        providerSelect.id = 'mm-provider';
        providerSelect.style.flex = '0 0 auto';
        const modelInput = document.createElement('input');
        modelInput.type = 'text';
        modelInput.id = 'mm-model';
        modelInput.placeholder = 'model identifier, e.g. qwen3-vl:30b';
        modelInput.style.flex = '1';
        const pullButton = document.createElement('button');
        pullButton.className = 'btn';
        pullButton.textContent = 'Pull Model';
        const refreshButton = document.createElement('button');
        refreshButton.className = 'btn';
        refreshButton.textContent = 'Refresh';
        const errorBox = document.createElement('div');
        errorBox.id = 'mm-error';
        errorBox.style.color = 'var(--danger, #c0392b)';
        errorBox.style.minHeight = '1.2em';

        row.appendChild(providerSelect);
        row.appendChild(modelInput);
        row.appendChild(pullButton);
        row.appendChild(refreshButton);
        card.appendChild(row);
        card.appendChild(errorBox);

        const downloads = document.createElement('div');
        downloads.id = 'mm-downloads';
        card.appendChild(downloads);

        const installed = document.createElement('div');
        installed.id = 'mm-installed';
        card.appendChild(installed);
        container.appendChild(card);

        DCodeApi.get('/api/v1/models/providers').then((data) => {
            const providers = (data.providers || []).filter((p) =>
                (p.capabilities || []).indexOf('MODEL_PULL') >= 0);
            providerSelect.innerHTML = '';
            for (const provider of providers) {
                const option = document.createElement('option');
                option.value = provider.id;
                option.textContent = provider.display_name + ' (' + provider.id + ')';
                providerSelect.appendChild(option);
            }
            if (!providers.length) {
                providerSelect.innerHTML = '<option value="">(no providers)</option>';
                errorBox.textContent = 'No model providers registered in ENR. '
                    + 'Start ENR and check enr.yaml model_providers.';
                return;
            }
            providerSelect.onchange = () => refreshInstalled();
            refreshInstalled();
        }).catch((e) => {
            errorBox.textContent = 'Model providers unavailable: ' + e.message
                + ' — is the ENR model management service running?';
            DCodeApp.logConsole('error', 'model providers failed: ' + e.message);
        });

        pullButton.onclick = async () => {
            const provider = providerSelect.value;
            const model = modelInput.value.trim();
            if (!provider) {
                errorBox.textContent = 'No provider selected.';
                return;
            }
            if (!model) {
                errorBox.textContent = 'Enter a model identifier.';
                return;
            }
            errorBox.textContent = '';
            pullButton.disabled = true;
            try {
                const job = await DCodeApi.post('/api/v1/models/'
                    + encodeURIComponent(provider) + '/pull', { model });
                const jobId = job.jobId || job.job_id;
                DCodeApp.logConsole('system', 'pull queued: ' + provider + '/' + model
                    + ' (job ' + jobId + ')');
                modelInput.value = '';
                refreshDownloads();
                startPolling();
            } catch (e) {
                errorBox.textContent = 'Pull failed: ' + e.message;
                DCodeApp.logConsole('error', 'pull failed: ' + e.message);
            } finally {
                pullButton.disabled = false;
            }
        };

        refreshButton.onclick = () => {
            errorBox.textContent = '';
            refreshDownloads();
            refreshInstalled();
        };

        refreshDownloads();
        startPolling();
    }

    function startPolling() {
        stopPolling();
        pollTimer = setInterval(refreshDownloads, POLL_INTERVAL_MS);
    }

    function stopPolling() {
        if (pollTimer !== null) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    async function refreshDownloads() {
        const box = document.getElementById('mm-downloads');
        if (!box) {
            return;
        }
        try {
            const data = await DCodeApi.get('/api/v1/models/jobs');
            renderDownloadsBox(box, data);
        } catch (e) {
            // 404 while ENR is not reachable: show nothing
        }
    }

    async function refreshInstalled() {
        const box = document.getElementById('mm-installed');
        const provider = (document.getElementById('mm-provider') || {}).value;
        if (!box || !provider) {
            return;
        }
        box.innerHTML = '<div class="item-title">Installed models — ' + provider + '</div>';
        try {
            const data = await DCodeApi.get('/api/v1/models/'
                + encodeURIComponent(provider) + '/installed');
            const models = data.models || [];
            if (!models.length) {
                box.appendChild(empty('No installed models on this provider.'));
                return;
            }
            const list = document.createElement('ul');
            list.className = 'list';
            for (const model of models) {
                const item = document.createElement('li');
                item.className = 'list-item model-row';
                const title = document.createElement('div');
                title.className = 'item-title';
                const size = model.size_bytes > 0
                    ? ' · ' + fmtBytes(model.size_bytes) : '';
                title.innerHTML = escapeHtml(model.id)
                    + ' <span class="badge">' + escapeHtml(model.provider) + '</span>'
                    + '<span class="muted">' + escapeHtml(size) + '</span>';
                item.appendChild(title);
                const remove = document.createElement('button');
                remove.className = 'btn small';
                remove.textContent = 'Remove';
                remove.onclick = async () => {
                    if (!confirm('Remove ' + model.id + ' from ' + provider + '?')) {
                        return;
                    }
                    try {
                        await DCodeApi.del('/api/v1/models/'
                            + encodeURIComponent(provider) + '/'
                            + encodeURIComponent(model.id));
                        DCodeApp.logConsole('system', 'removed ' + provider + '/' + model.id);
                        refreshInstalled();
                    } catch (e) {
                        DCodeApp.logConsole('error', 'remove failed: ' + e.message);
                    }
                };
                item.appendChild(remove);
                list.appendChild(item);
            }
            box.appendChild(list);
        } catch (e) {
            box.appendChild(empty('Installed models unavailable: ' + e.message));
        }
    }

    function renderDownloadsBox(box, data) {
        box.innerHTML = '';
        const downloads = data.downloads || [];
        const visible = downloads.filter((d) =>
            ['COMPLETED', 'FAILED', 'CANCELLED'].indexOf(d.status) < 0);
        if (!visible.length) {
            box.appendChild(empty('No active downloads.'));
            return;
        }
        const header = document.createElement('div');
        header.className = 'item-title';
        header.textContent = 'Active downloads';
        box.appendChild(header);
        for (const job of visible) {
            const pct = job.total_bytes > 0
                ? Math.min(100, Math.round(job.completed_bytes * 100 / job.total_bytes)) : null;
            const row = document.createElement('div');
            row.style.marginBottom = '8px';
            const label = document.createElement('div');
            label.className = 'item-title';
            const bytes = pct === null
                ? fmtBytes(job.completed_bytes)
                : pct + '% (' + fmtBytes(job.completed_bytes) + ' / ' + fmtBytes(job.total_bytes) + ')';
            label.innerHTML = escapeHtml(job.provider + ' / ' + job.model)
                + ' <span class="badge">' + escapeHtml(job.status) + '</span>'
                + (job.operation ? ' <span class="muted">' + escapeHtml(job.operation) + '</span>' : '')
                + ' <span class="muted">' + escapeHtml(bytes) + '</span>';
            row.appendChild(label);
            const bar = document.createElement('div');
            bar.style.height = '6px';
            bar.style.background = 'var(--panel-2, #2a2f3a)';
            bar.style.borderRadius = '3px';
            bar.style.overflow = 'hidden';
            const fill = document.createElement('div');
            fill.style.height = '100%';
            fill.style.width = (pct === null ? 0 : pct) + '%';
            fill.style.background = 'var(--accent, #4f8cff)';
            bar.appendChild(fill);
            row.appendChild(bar);
            if (job.error) {
                const err = document.createElement('div');
                err.className = 'muted';
                err.textContent = job.error;
                row.appendChild(err);
            }
            const cancel = document.createElement('button');
            cancel.className = 'btn small';
            cancel.textContent = 'Cancel';
            cancel.onclick = async () => {
                try {
                    await DCodeApi.del('/api/v1/models/jobs/' + encodeURIComponent(job.job_id));
                    DCodeApp.logConsole('system', 'cancel requested for job ' + job.job_id);
                } catch (e) {
                    DCodeApp.logConsole('error', 'cancel failed: ' + e.message);
                }
            };
            row.appendChild(cancel);
            box.appendChild(row);
        }
    }

    function empty(text) {
        const node = document.createElement('div');
        node.className = 'muted';
        node.textContent = text;
        return node;
    }

    function escapeHtml(value) {
        return String(value || '').replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        })[c]);
    }

    function fmtBytes(n) {
        if (n == null || n <= 0) {
            return '—';
        }
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let i = 0;
        let v = n;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return v.toFixed(v >= 100 || i === 0 ? 0 : 1) + ' ' + units[i];
    }

    return { render };
})();

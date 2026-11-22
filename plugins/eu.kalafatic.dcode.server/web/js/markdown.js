/**
 * DCode web markdown + code-highlighting module (no external dependencies).
 * XSS-safe: everything is escaped before formatting; code blocks get a
 * dependency-free token highlighter and a copy control.
 */
window.DCodeMarkdown = (function () {
    'use strict';

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    const CODE_KEYWORDS = {
        java: ['abstract','assert','boolean','break','byte','case','catch','char',
            'class','const','continue','default','do','double','else','enum','extends',
            'final','finally','float','for','goto','if','implements','import','instanceof',
            'int','interface','long','native','new','package','private','protected',
            'public','record','return','sealed','short','static','strictfp','super',
            'switch','synchronized','this','throw','throws','transient','try','void',
            'volatile','while','var','yield','permits','non-sealed'],
        js: ['async','await','break','case','catch','class','const','continue',
            'debugger','default','delete','do','else','export','extends','finally',
            'for','function','if','import','in','instanceof','let','new','of','return',
            'static','super','switch','this','throw','try','typeof','var','void',
            'while','with','yield'],
        ts: ['async','await','break','case','catch','class','const','continue',
            'debugger','default','delete','do','else','enum','export','extends',
            'finally','for','function','if','implements','import','in','instanceof',
            'interface','let','namespace','new','of','return','static','super','switch',
            'this','throw','try','type','typeof','var','void','while','with','yield'],
        python: ['and','as','assert','async','await','break','class','continue',
            'def','del','elif','else','except','finally','for','from','global','if',
            'import','in','is','lambda','nonlocal','not','or','pass','raise','return',
            'try','while','with','yield','True','False','None','match','case'],
        sql: ['select','from','where','insert','into','values','update','set',
            'delete','create','table','alter','drop','index','view','join','left',
            'right','inner','outer','full','on','group','by','order','having','limit',
            'offset','union','all','as','and','or','not','null','primary','key',
            'foreign','references','distinct','count','sum','avg','min','max','case',
            'when','then','else','end','begin','commit','rollback','transaction'],
        sh: ['if','then','else','elif','fi','for','while','do','done','case','esac',
            'in','function','export','local','return','exit','echo','cd','alias',
            'source','set','unset','shift','read','test','eval','exec','trap','until'],
        bash: ['if','then','else','elif','fi','for','while','do','done','case','esac',
            'in','function','export','local','return','exit','echo','cd','alias',
            'source','set','unset','shift','read','test','eval','exec','trap','until'],
        kotlin: ['as','break','class','continue','do','else','false','for','fun',
            'if','in','interface','is','null','object','package','return','super',
            'this','throw','true','try','typealias','typeof','val','var','when','while'],
        go: ['break','case','chan','const','continue','default','defer','else',
            'fallthrough','for','func','go','goto','if','import','interface','map',
            'package','range','return','select','struct','switch','type','var'],
        rust: ['as','async','await','break','const','continue','crate','dyn','else',
            'enum','extern','false','fn','for','if','impl','in','let','loop','match',
            'mod','move','mut','pub','ref','return','self','Self','static','struct',
            'super','trait','true','type','unsafe','use','where','while'],
        cpp: ['alignas','alignof','and','asm','auto','break','case','catch','class',
            'const','constexpr','continue','decltype','default','delete','do','else',
            'enum','explicit','export','extern','false','for','friend','goto','if',
            'inline','namespace','new','noexcept','nullptr','operator','private',
            'protected','public','return','sizeof','static','struct','switch',
            'template','this','throw','true','try','typedef','typename','union',
            'using','virtual','void','volatile','while'],
        json: ['true','false','null']
    };
    const CODE_TYPES = {
        java: ['String','Integer','Long','Double','Float','Boolean','Object','Void',
            'List','Map','Set','Optional','Stream','Exception','RuntimeException'],
        js: ['Array','Object','String','Number','Boolean','Promise','Error','Map',
            'Set','Symbol','JSON','Math','Date','RegExp','console'],
        ts: ['Array','Object','String','Number','Boolean','Promise','Error','Map',
            'Set','Symbol','JSON','Math','Date','RegExp','console','unknown','any',
            'never','void','string','number','boolean','bigint','symbol','undefined'],
        python: ['str','int','float','bool','list','dict','set','tuple','object',
            'Exception','ValueError','TypeError','KeyError','RuntimeError','self'],
        sql: ['varchar','text','integer','bigint','decimal','boolean','timestamp',
            'date','char','serial','uuid'],
        go: ['string','int','int64','float64','bool','byte','rune','error','any',
            'interface','map','chan','struct'],
        rust: ['String','str','i32','i64','u32','u64','f32','f64','bool','usize',
            'Vec','Option','Result','Box','HashMap','char','u8'],
        cpp: ['int','long','short','char','float','double','bool','size_t','string',
            'vector','map','unordered_map','unique_ptr','shared_ptr','auto'],
        kotlin: ['String','Int','Long','Double','Boolean','List','Map','Set','Unit',
            'Any','Nothing','Char','Byte','Short','Float']
    };
    const PLAIN = { 'text': 1, 'plain': 1, 'txt': 1, 'markdown': 1, 'md': 1,
        'log': 1, 'console': 1, 'diff': 1, 'patch': 1, 'none': 1, '': 1 };

    function highlightCode(code, lang) {
        const raw = String(code == null ? '' : code);
        const name = String(lang || '').toLowerCase();
        if (PLAIN[name]) { return esc(raw); }
        const keywords = new Set(CODE_KEYWORDS[name] || []);
        const types = new Set(CODE_TYPES[name] || []);
        const token = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*|--[^\n]*|"(?:[^"\\\n]|\\.)*"?|'(?:[^'\\\n]|\\.)*'?|`(?:[^`\\]|\\.)*`?|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|[A-Za-z_$][\w$]*)/g;
        let out = '';
        let last = 0;
        let match;
        while ((match = token.exec(raw)) !== null) {
            out += esc(raw.slice(last, match.index));
            const tok = match[0];
            const first = tok.charAt(0);
            if (first === '"' || first === "'" || first === '`') {
                out += '<span class="syn-str">' + esc(tok) + '</span>';
            } else if (first === '/' || first === '#' || first === '-') {
                out += '<span class="syn-com">' + esc(tok) + '</span>';
            } else if (/[0-9]/.test(first)) {
                out += '<span class="syn-num">' + esc(tok) + '</span>';
            } else if (keywords.has(tok)) {
                out += '<span class="syn-kw">' + esc(tok) + '</span>';
            } else if (types.has(tok)) {
                out += '<span class="syn-type">' + esc(tok) + '</span>';
            } else if (raw.charAt(match.index + tok.length) === '(') {
                out += '<span class="syn-fn">' + esc(tok) + '</span>';
            } else {
                out += esc(tok);
            }
            last = token.lastIndex;
        }
        return out + esc(raw.slice(last));
    }

    function renderMarkdown(text) {
        if (text == null) { return ''; }
        let source = String(text);
        const blocks = [];
        source = source.replace(/```([\w+-]*)\n?([\s\S]*?)(```|$)/g, (match, lang, code) => {
            const label = String(lang || 'code').toLowerCase();
            blocks.push('<div class="md-code-head"><span>' + esc(label)
                + '</span><button class="md-copy" type="button" title="Copy code block">'
                + 'copy</button></div><pre><code class="md-code">'
                + highlightCode(code, label) + '</code></pre>');
            return '\u0000B' + (blocks.length - 1) + '\u0000';
        });
        source = esc(source);
        source = source.replace(/`([^`\n]+)`/g, '<code class="md-inline">$1</code>');
        source = source.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
        source = source.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>');
        source = source.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
            '<a href="$2" target="_blank" rel="noopener">$1</a>');
        const lines = source.split('\n');
        const html = [];
        let list = null;
        const closeList = () => {
            if (list) { html.push(list === 'ul' ? '</ul>' : '</ol>'); list = null; }
        };
        for (const line of lines) {
            const block = /^\u0000B(\d+)\u0000$/.exec(line);
            if (block) { closeList(); html.push(blocks[+block[1]]); continue; }
            const ordered = /^(\d+)[.)]\s+(.+)$/.exec(line);
            const bullet = /^[-*+]\s+(.+)$/.exec(line);
            if (ordered) {
                if (list !== 'ol') { closeList(); html.push('<ol>'); list = 'ol'; }
                html.push('<li>' + ordered[2] + '</li>');
                continue;
            }
            if (bullet) {
                if (list !== 'ul') { closeList(); html.push('<ul>'); list = 'ul'; }
                html.push('<li>' + bullet[1] + '</li>');
                continue;
            }
            closeList();
            if (!line.trim()) { html.push(''); continue; }
            html.push('<p>' + line + '</p>');
        }
        closeList();
        return html.join('\n');
    }

    function copyToClipboard(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(text);
        }
        return Promise.reject(new Error('Clipboard unavailable'));
    }

    function wireCodeCopy(container) {
        if (!container || container.dataset.codeCopyWired) { return; }
        container.dataset.codeCopyWired = '1';
        container.addEventListener('click', (event) => {
            const button = event.target.closest('.md-copy');
            if (!button) { return; }
            const block = button.closest('.md-code-head');
            if (!block) { return; }
            const code = block.nextElementSibling;
            if (!code) { return; }
            copyToClipboard(code.textContent).then(() => {
                const label = button.textContent;
                button.textContent = 'copied';
                setTimeout(() => { button.textContent = label; }, 1400);
            }).catch(() => { /* clipboard unavailable */ });
        });
    }

    return { esc, highlightCode, renderMarkdown, copyToClipboard, wireCodeCopy };
})();

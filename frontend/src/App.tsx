import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError, type DocumentSummary, type DocumentType, type EditView, type RestoreReport } from './api';
import { DocumentEditor } from './DocumentEditor';
import { EditorContext } from './EditorContext';
import { canRedo, canUndo, createHistory, type History, push, redo, replacePresent, undo } from './history';
import { type Doc, idsForNewTextItems, type Problem, same, stripTransient } from './model';

type Banner = { kind: 'error' | 'warning' | 'conflict' | 'info'; text: string } | null;

/** A question asked in the page's own dialog, so the confirm button can say exactly what happens. */
type Confirmation = { title: string; body: ReactNode; confirmLabel: string; onConfirm: () => void } | null;

const PREVIEW_DELAY_MS = 500;
const POLL_MS = 2000;

/**
 * Undo granularity (ADR-0023): edits typed into the same text field (same DOM node) merge into one step;
 * buttons, checkboxes and selects have no key, so each of their actions is its own step.
 */
const fieldKeys = new WeakMap<Element, string>();
let nextFieldKey = 0;
function coalesceKey(element: Element | null): string | undefined {
  const typing = element instanceof HTMLTextAreaElement
    || (element instanceof HTMLInputElement && ['text', 'search', 'url', 'email', 'tel', ''].includes(element.type));
  if (!typing || !element) return undefined;
  let key = fieldKeys.get(element);
  if (!key) { key = `field-${nextFieldKey++}`; fieldKeys.set(element, key); }
  return key;
}

export function App() {
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [images, setImages] = useState<string[]>([]);
  const [current, setCurrent] = useState<EditView | null>(null);
  // The draft is the present of an undo/redo history (ADR-0023).
  const [history, setHistory] = useState<History<Doc> | null>(null);
  const draft = history?.present ?? null;
  const [problems, setProblems] = useState<Problem[]>([]);
  const [banner, setBanner] = useState<Banner>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewState, setPreviewState] = useState('');
  const [saving, setSaving] = useState(false);
  const [closed, setClosed] = useState(false);
  const [confirmation, setConfirmation] = useState<Confirmation>(null);
  const restoreInput = useRef<HTMLInputElement>(null);
  // Separate modes (ADR-0026, Q47): the sidebar shows either the CVs or the cover letters.
  const [mode, setMode] = useState<DocumentType>(() =>
    (localStorage.getItem(MODE_KEY) === 'cover-letter' ? 'cover-letter' : 'cv'));
  useEffect(() => { localStorage.setItem(MODE_KEY, mode); }, [mode]);

  const dirty = !!current && !!draft && !same(draft, current.tree);

  const refreshList = useCallback(async () => setDocuments(await api.list()), []);
  useEffect(() => {
    refreshList().catch((e) => setBanner({ kind: 'error', text: String(e) }));
    api.images().then(setImages).catch(() => setImages([]));
  }, [refreshList]);

  const load = useCallback((view: EditView) => {
    setCurrent(view);
    setHistory(createHistory(structuredClone(view.tree))); // new document or reload from disk: fresh history
    setProblems([]);
  }, []);

  const open = async (id: string) => {
    if (dirty && !confirm('Discard your unsaved changes?')) return;
    try {
      load(await api.open(id));
      const type = documents.find((d) => d.id === id)?.type;
      if (type) setMode(type);
      setBanner(null);
    } catch (e) {
      setBanner({ kind: 'error', text: e instanceof Error ? e.message : String(e) });
    }
  };

  const update = useCallback((mutate: (d: Doc) => void) => {
    const group = coalesceKey(document.activeElement);
    const now = Date.now();
    setHistory((h) => {
      if (!h) return h;
      const next = structuredClone(h.present);
      mutate(next);
      return push(h, next, { group, now, equals: same });
    });
  }, []);

  const undoStep = useCallback(() => setHistory((h) => (h ? undo(h) : h)), []);
  const redoStep = useCallback(() => setHistory((h) => (h ? redo(h) : h)), []);

  /** Back to the saved version, as an undoable step (so a misclick costs nothing). */
  const discard = () => {
    if (!current) return;
    const saved = structuredClone(current.tree);
    setHistory((h) => (h ? push(h, saved, { now: Date.now(), equals: same }) : h));
  };

  // ---- live preview of the unsaved draft (debounced, stale requests cancelled) ----
  useEffect(() => {
    if (!draft) return;
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      setPreviewState('rendering…');
      try {
        const blob = await api.preview(stripTransient(draft), controller.signal);
        setPreviewUrl((old) => { if (old) URL.revokeObjectURL(old); return URL.createObjectURL(blob); });
        setProblems([]);
        setPreviewState(`preview updated ${new Date().toLocaleTimeString()}`);
      } catch (e) {
        if (controller.signal.aborted) return;
        if (e instanceof ApiError && e.status === 422) {
          setProblems(e.problems);
          setPreviewState(e.problems.length ? `${e.problems.length} problem(s), fix them to update the preview` : e.message);
        } else {
          setPreviewState(`preview failed: ${e instanceof Error ? e.message : e}`);
        }
      }
    }, PREVIEW_DELAY_MS);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [draft]);

  // ---- save ----
  const save = useCallback(async () => {
    if (!current || !draft || saving) return;
    if (!current.writtenByApp && !confirm(
      'This file was written by hand. Saving from the app rewrites it in a tidy format and drops its comments.\n'
      + 'The previous version is kept in data/.history. Continue?')) return;
    setSaving(true);
    try {
      let tree = stripTransient(draft);
      if (current.variant && current.parentTree) tree = idsForNewTextItems(tree, current.parentTree);
      const sent = draft;
      const saved = await api.save(current.id, tree, current.version);
      setCurrent(saved);
      setProblems([]);
      // Keep undo/redo across saves. Adopt the stored version (it may have new ids) unless the user kept typing.
      setHistory((h) => (h && h.present === sent ? replacePresent(h, structuredClone(saved.tree)) : h));
      setBanner(saved.warnings.length
        ? { kind: 'warning', text: saved.warnings.join('\n') }
        : { kind: 'info', text: `Saved ${current.id} at ${new Date().toLocaleTimeString()}` });
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setBanner({ kind: 'conflict', text: e.message });
      else if (e instanceof ApiError && e.status === 422) {
        setProblems(e.problems);
        setBanner({ kind: 'error', text: e.problems.length ? 'Not saved: fix the highlighted fields.' : e.message });
      } else setBanner({ kind: 'error', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setSaving(false);
    }
  }, [current, draft, saving]);

  // Ctrl+S and the "leave with unsaved changes?" guard
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.isComposing) return;
      const key = e.key.toLowerCase();
      if (key === 's') { e.preventDefault(); void save(); return; }
      // One history for the whole editor: also replaces the browser's own undo inside text fields.
      const inEditor = !(e.target instanceof Element) || !!e.target.closest('.editor-pane') || e.target === document.body;
      if (!inEditor) return;
      if (key === 'z' && !e.shiftKey) { e.preventDefault(); undoStep(); }
      else if (key === 'y' || (key === 'z' && e.shiftKey)) { e.preventDefault(); redoStep(); }
    };
    const onLeave = (e: BeforeUnloadEvent) => { if (dirty) e.preventDefault(); };
    window.addEventListener('keydown', onKey);
    window.addEventListener('beforeunload', onLeave);
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('beforeunload', onLeave); };
  }, [save, dirty, undoStep, redoStep]);

  // ---- files changed outside the editor (VS Code, another tab) ----
  const lastToken = useRef<number | null>(null);
  useEffect(() => {
    if (closed) return;
    const timer = setInterval(async () => {
      try {
        const token = await api.changes();
        const changed = lastToken.current !== null && token !== lastToken.current;
        lastToken.current = token;
        if (!changed) return;
        await refreshList();
        api.images().then(setImages).catch(() => undefined);
        if (!current || saving) return;
        const fresh = await api.open(current.id).catch(() => null);
        if (!fresh || fresh.version === current.version) return;
        if (!dirty) { load(fresh); setBanner({ kind: 'info', text: `${current.id} was changed on disk and has been reloaded.` }); }
        else setBanner({ kind: 'conflict', text: `${current.id} was changed on disk while you were editing.` });
      } catch { /* server busy or stopped */ }
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [current, dirty, saving, closed, load, refreshList]);

  const reload = async () => {
    if (!current) return;
    load(await api.open(current.id));
    setBanner(null);
  };

  const newVariant = async (parentId: string) => {
    const id = prompt(`Name of the new variant of ${parentId} (e.g. ${parentId}-acme):`, `${parentId}-`);
    if (!id) return;
    try {
      await api.createVariant(parentId, id.trim());
      await refreshList();
      await open(id.trim());
    } catch (e) {
      setBanner({ kind: 'error', text: e instanceof Error ? e.message : String(e) });
    }
  };

  const closeEditor = () => { setCurrent(null); setHistory(null); setPreviewUrl(null); setPreviewState(''); };

  /** Deleting a document deletes every variant built on it, at any level (ADR-0029); the dialog lists them all. */
  const deleteDocument = (id: string) => {
    const doomed = subtreeDeepestFirst(documents, id);
    const typeOf = (docId: string) => {
      const type = documents.find((d) => d.id === docId)?.type;
      return type === 'cover-letter' ? 'cover letter' : type === 'cv' ? 'CV' : 'unreadable';
    };
    const openIsDoomed = !!current && doomed.includes(current.id);
    setConfirmation({
      title: doomed.length === 1 ? `Delete ${id}?` : `Delete ${id} and its ${doomed.length - 1} variant(s)?`,
      confirmLabel: doomed.length === 1 ? 'Delete 1 document' : `Delete ${doomed.length} documents`,
      body: (
        <>
          <p>These documents will be deleted:</p>
          <ul className="doomed">{[...doomed].reverse().map((d) => (
            <li key={d}><strong>{d}</strong> <span className="muted">({typeOf(d)}{d === id ? '' : `, built on ${id}`})</span></li>
          ))}</ul>
          {openIsDoomed && dirty && <p className="danger">The open document has unsaved changes; they will be lost.</p>}
          <p className="muted">A copy is kept in data/.history (delete-{id}-….zip). Photos are not deleted.</p>
        </>
      ),
      onConfirm: async () => {
        try {
          await api.remove(id, doomed);
          if (openIsDoomed) closeEditor();
          await refreshList();
          setBanner({ kind: 'info', text: `Deleted ${doomed.join(', ')}.` });
        } catch (e) {
          if (e instanceof ApiError && e.status === 409) await refreshList();
          setBanner({ kind: 'error', text: e instanceof Error ? e.message : String(e) });
        }
      },
    });
  };

  // ---- backup and restore (ADR-0028) ----
  const backup = async () => {
    const name = `pdf-generator-backup-${new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-')}.zip`;
    // Ask where to save first (needs the click's user activation), then fetch; older browsers just download.
    type Writable = { write: (b: Blob) => Promise<void>; close: () => Promise<void> };
    type Handle = { name: string; createWritable: () => Promise<Writable> };
    const picker = (window as unknown as { showSaveFilePicker?: (options: object) => Promise<Handle> }).showSaveFilePicker;
    let handle: Handle | null = null;
    if (picker) {
      try {
        handle = await picker({ suggestedName: name, types: [{ description: 'PDF Generator backup', accept: { 'application/zip': ['.zip'] } }] });
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return; // the user cancelled
        handle = null;
      }
    }
    try {
      const blob = await api.backup();
      if (handle) {
        const writable = await handle.createWritable();
        await writable.write(blob);
        await writable.close();
        setBanner({ kind: 'info', text: `Backup saved as ${handle.name}.` });
      } else {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url; link.download = name;
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
        setBanner({ kind: 'info', text: `Backup downloaded as ${name} (see your browser's downloads).` });
      }
    } catch (e) {
      setBanner({ kind: 'error', text: `Backup failed: ${e instanceof Error ? e.message : String(e)}` });
    }
  };

  const restoreChosen = (file: File) => {
    setConfirmation({
      title: `Restore from ${file.name}?`,
      confirmLabel: 'Replace all my data',
      body: (
        <>
          <p>This <b>replaces all your documents and photos</b> with the ones in the backup. Documents created
            since the backup will be removed.</p>
          {dirty && <p className="danger">The open document has unsaved changes; they will be lost.</p>}
          <p className="muted">Your current data is saved first in data/.history (restore-….zip), so this can be undone
            by restoring that file.</p>
        </>
      ),
      onConfirm: async () => {
        try {
          const report: RestoreReport = await api.restore(file);
          closeEditor();
          await refreshList();
          api.images().then(setImages).catch(() => setImages([]));
          const parts = [`Restored ${report.restored.length} document(s).`];
          if (report.removed.length) parts.push(`Removed: ${report.removed.join(', ')}.`);
          parts.push(`Previous data saved in data/${report.safetyBackup}.`);
          if (report.warnings.length) parts.push(`Needs fixing: ${report.warnings.join('; ')}`);
          setBanner({ kind: report.warnings.length ? 'warning' : 'info', text: parts.join(' ') });
        } catch (e) {
          setBanner({ kind: 'error', text: `Restore failed, nothing was changed: ${e instanceof Error ? e.message : String(e)}` });
        }
      },
    });
  };

  const quit = async () => {
    if (dirty && !confirm('You have unsaved changes. Quit anyway?')) return;
    if (!dirty && !confirm('Close PDF Generator?')) return;
    await api.quit();
    setClosed(true);
  };

  const createDocument = async (id: string, type: DocumentType) => {
    if (dirty && !confirm('Discard your unsaved changes?')) return false;
    try {
      await api.createDocument(id, type);
      await refreshList();
      load(await api.open(id));
      setMode(type);
      setBanner({ kind: 'info', text: `Created ${id}. Fill in the sections, then Save.` });
      return true;
    } catch (e) {
      setBanner({ kind: 'error', text: e instanceof Error ? e.message : String(e) });
      return false;
    }
  };

  /** Switching mode closes a document of the other type (asking first if it has unsaved changes). */
  const switchMode = (next: DocumentType) => {
    if (next === mode) return;
    const currentType = current && documents.find((d) => d.id === current.id)?.type;
    if (current && currentType && currentType !== next) {
      if (dirty && !confirm('Discard your unsaved changes?')) return;
      setCurrent(null); setHistory(null); setPreviewUrl(null); setPreviewState(''); setBanner(null);
    }
    setMode(next);
  };

  const addImage = useCallback((path: string) => setImages((list) => (list.includes(path) ? list : [...list, path].sort())), []);

  const context = useMemo(() => current && ({
    update, problems, base: current.parentTree ?? undefined, parentId: current.parentId ?? undefined, images,
    addImage, rules: current.rules,
  }), [update, problems, current, images, addImage]);

  if (closed) {
    return <main className="closed"><h2>PDF Generator has been closed.</h2>
      <p>The port is released. You can close this tab and start the app again from its shortcut.</p></main>;
  }

  return (
    <div className="app">
      <aside>
        <div className="modes" role="tablist" aria-label="Document type">
          {MODES.map(([type, label]) => (
            <button key={type} type="button" role="tab" aria-selected={mode === type}
              className={`mode ${mode === type ? 'active' : ''}`} onClick={() => switchMode(type)}>
              {label} <span className="count">{documents.filter((d) => d.type === type).length}</span>
            </button>
          ))}
        </div>
        <NewDocumentForm key={mode} defaultType={mode} onCreate={createDocument} />
        <DocumentTree documents={documents} mode={mode} currentId={current?.id} onOpen={open}
          onNewVariant={newVariant} onDelete={deleteDocument} />
        <div className="data-tools">
          <button type="button" title="Save all documents and photos into one ZIP file, wherever you choose"
            onClick={backup}>Backup…</button>
          <button type="button" title="Replace all documents and photos with those in a backup ZIP"
            onClick={() => restoreInput.current?.click()}>Restore…</button>
          <input ref={restoreInput} type="file" accept=".zip,application/zip" hidden aria-label="Restore backup"
            onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ''; if (file) restoreChosen(file); }} />
        </div>
        <button type="button" className="quit" onClick={quit}>Quit app</button>
      </aside>

      <section className="editor-pane">
        {current && draft ? (
          <>
            <header>
              <div>
                <span className={`type-badge ${current.rules.letter ? 'letter' : 'cv'}`}>
                  {current.rules.letter ? 'Cover letter' : 'CV'}</span>{' '}
                <strong>{current.id}</strong>{' '}
                <span className="muted">{current.variant ? `variant of ${current.parentId}` : 'base document'}</span>
                {dirty && <span className="dirty"> ● unsaved changes</span>}
              </div>
              <div className="actions">
                <button type="button" title="Undo (Ctrl+Z)" disabled={!history || !canUndo(history)} onClick={undoStep}>↶ Undo</button>
                <button type="button" title="Redo (Ctrl+Y or Ctrl+Shift+Z)" disabled={!history || !canRedo(history)} onClick={redoStep}>↷ Redo</button>
                <button type="button" title="Back to the saved version (can be undone)" disabled={!dirty} onClick={discard}>Discard</button>
                <button type="button" className="primary" disabled={!dirty || saving} onClick={save}
                  title="Ctrl+S">{saving ? 'Saving…' : 'Save'}</button>
                <a className="button" href={api.downloadUrl(current.id)} title="PDF of the saved version">Download PDF</a>
              </div>
            </header>
            {banner && (
              <div className={`banner ${banner.kind}`}>
                <span>{banner.text}</span>
                {banner.kind === 'conflict' && <button type="button" onClick={reload}>Reload from disk (discard my changes)</button>}
                <button type="button" className="link" onClick={() => setBanner(null)}>dismiss</button>
              </div>
            )}
            {current.variant && (
              <p className="hint">You are editing the full document. Only what differs from <b>{current.parentId}</b> is
                saved in this variant; everything else keeps following {current.parentId}.</p>
            )}
            {problems.length > 0 && (
              <ul className="problems">{problems.map((p) => <li key={p.path + p.message}><code>{p.path || 'document'}</code> {p.message}</li>)}</ul>
            )}
            <EditorContext.Provider value={context!}>
              <DocumentEditor doc={draft} />
            </EditorContext.Provider>
          </>
        ) : (
          <div className="empty">
            {banner && <div className={`banner ${banner.kind}`}>{banner.text}</div>}
            <p>{mode === 'cv' ? 'Select a CV on the left, or create one with + new document.'
              : 'Select a cover letter on the left, or create one with + new document.'}</p>
          </div>
        )}
      </section>

      <section className="preview-pane">
        <div className="muted status">{previewState}</div>
        {previewUrl && current ? <iframe title="PDF preview" src={previewUrl} /> : <div className="empty">Preview</div>}
      </section>
      {confirmation && <ConfirmDialog confirmation={confirmation} onClose={() => setConfirmation(null)} />}
    </div>
  );
}

const MODE_KEY = 'pdfgen.mode';
const MODES: readonly (readonly [DocumentType, string])[] = [['cv', 'CVs'], ['cover-letter', 'Cover letters']];

/**
 * The sidebar list (ADR-0025, ADR-0026): the documents of the current mode as a tree of base documents and their
 * variants (a variant can have variants). Documents that cannot be read go to "Needs fixing" in both modes.
 */
function DocumentTree({ documents, mode, currentId, onOpen, onNewVariant, onDelete }: {
  documents: DocumentSummary[]; mode: DocumentType; currentId: string | undefined; onOpen: (id: string) => void;
  onNewVariant: (parentId: string) => void; onDelete: (id: string) => void;
}) {
  const readable = documents.filter((d) => !d.loadError && d.type);
  const broken = documents.filter((d) => d.loadError || !d.type);
  const children = (parentId: string) => readable.filter((d) => d.parentId === parentId);
  const renderNode = (doc: DocumentSummary, depth: number): ReactNode => (
    <li key={doc.id} className={doc.variant ? 'variant' : 'base'} style={{ paddingLeft: 12 + depth * 14 }}>
      <DocLink doc={doc} active={currentId === doc.id} onOpen={onOpen} />
      <button type="button" className="link" title={`Create a variant of ${doc.id}`}
        onClick={() => onNewVariant(doc.id)}>+ variant</button>
      <button type="button" className="link danger"
        title={children(doc.id).length ? `Delete ${doc.id} and every variant built on it` : `Delete ${doc.id}`}
        onClick={() => onDelete(doc.id)}>delete</button>
      {children(doc.id).length > 0 && <ul>{children(doc.id).map((child) => renderNode(child, depth + 1))}</ul>}
    </li>
  );
  return (
    <>
      {MODES.filter(([type]) => type === mode).map(([type, title]) => {
        const roots = readable.filter((d) => d.type === type && !d.variant);
        return (
          <section key={type} className="doc-group" aria-label={title}>
            <h1>{title}</h1>
            {roots.length ? <ul>{roots.map((root) => renderNode(root, 0))}</ul>
              : <p className="muted none">none yet: use + new document</p>}
          </section>
        );
      })}
      {broken.length > 0 && (
        <section className="doc-group" aria-label="Needs fixing">
          <h1>Needs fixing</h1>
          <ul>{broken.map((d) => (
            <li key={d.id} className="broken" title={d.loadError ?? ''}>
              <strong>{d.id}</strong>
              <button type="button" className="link danger" title={`Delete ${d.id}`} onClick={() => onDelete(d.id)}>delete</button>
              <div>{d.loadError ?? 'unknown document type'}</div>
            </li>
          ))}</ul>
        </section>
      )}
    </>
  );
}

/** `id` and every document built on it at any level, deepest first and `id` last (the server checks the same list). */
export function subtreeDeepestFirst(documents: DocumentSummary[], id: string): string[] {
  const order: string[] = [];
  const seen = new Set<string>();
  const visit = (docId: string) => {
    if (seen.has(docId)) return;
    seen.add(docId);
    documents.filter((d) => d.parentId === docId).forEach((child) => visit(child.id));
    order.push(docId);
  };
  visit(id);
  return order;
}

function ConfirmDialog({ confirmation, onClose }: { confirmation: NonNullable<Confirmation>; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
        <h2 id="dialog-title">{confirmation.title}</h2>
        {confirmation.body}
        <div className="form-buttons">
          <button type="button" className="danger-solid" onClick={() => { onClose(); confirmation.onConfirm(); }}>
            {confirmation.confirmLabel}</button>
          <button type="button" autoFocus onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

const ID_RULE = /^[a-z0-9][a-z0-9._-]*$/;

/** "+ new document": name and type (CV or cover letter), created from a starter skeleton (ADR-0024, ADR-0026). */
function NewDocumentForm({ defaultType, onCreate }: {
  defaultType: DocumentType; onCreate: (id: string, type: DocumentType) => Promise<boolean>;
}) {
  const [open, setOpen] = useState(false);
  const [id, setId] = useState('');
  const [type, setType] = useState<DocumentType>(defaultType);
  const valid = ID_RULE.test(id.trim());
  if (!open) {
    return (
      <button type="button" className="new-doc" title="Start a new document from scratch: CV or cover letter"
        onClick={() => setOpen(true)}>+ new document</button>
    );
  }
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    if (await onCreate(id.trim(), type)) { setOpen(false); setId(''); }
  };
  return (
    <form className="new-doc-form" onSubmit={submit} aria-label="New document">
      <fieldset className="type-choice">
        <legend className="field-label">Type</legend>
        <label className="check"><input type="radio" name="new-doc-type" value="cv" checked={type === 'cv'}
          onChange={() => setType('cv')} /> CV</label>
        <label className="check"><input type="radio" name="new-doc-type" value="cover-letter"
          checked={type === 'cover-letter'} onChange={() => setType('cover-letter')} /> Cover letter</label>
      </fieldset>
      <label className="field"><span className="field-label">Name (file name)</span>
        <input value={id} autoFocus placeholder={type === 'cv' ? 'cv-de' : 'cover-letter-en'}
          onChange={(e) => setId(e.target.value)} />
      </label>
      {id && !valid && <span className="field-error">lowercase letters, digits, - _ . only</span>}
      <div className="form-buttons">
        <button type="submit" className="primary" disabled={!valid}>Create</button>
        <button type="button" onClick={() => { setOpen(false); setId(''); }}>Cancel</button>
      </div>
    </form>
  );
}

function DocLink({ doc, active, onOpen }: { doc: DocumentSummary; active: boolean; onOpen: (id: string) => void }) {
  return (
    <button type="button" className={`doc ${active ? 'active' : ''}`} onClick={() => onOpen(doc.id)}>{doc.id}</button>
  );
}

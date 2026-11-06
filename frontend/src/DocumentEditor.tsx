import { useRef, useState } from 'react';
import { api } from './api';
import { Card, Field, Select } from './fields';
import { useEditor } from './EditorContext';
import { SectionEditor } from './SectionEditor';
import { type Doc, KIND_LABELS, type Letter, newSection, type SectionKind, setOptional } from './model';

/** Header (person + document settings), the letter fields of a cover letter, and the section cards. */
export function DocumentEditor({ doc }: { doc: Doc }) {
  const { update, base, rules } = useEditor();
  const contacts = doc.sections.filter((s) => s.kind === 'contact').length;
  const kinds = rules.sectionKinds.filter((k) => k !== 'contact' || contacts < rules.maxContact);
  const [chosenKind, setNewKind] = useState<SectionKind>(kinds.includes('entries') ? 'entries' : kinds[0]);
  const newKind = kinds.includes(chosenKind) ? chosenKind : kinds[0];
  // Only the themes that exist for this type (ADR-0027); a theme typed by hand that does not exist stays visible.
  const theme = doc.meta.theme ?? 'classic';
  const themeOptions = [...rules.themes, ...(rules.themes.includes(theme) ? [] : [theme])]
    .map((t) => [t, rules.themes.includes(t) ? t.charAt(0).toUpperCase() + t.slice(1) : `${t} (unknown)`] as const);

  return (
    <div className="document-editor">
      <Card title="Header">
        <div className="grid">
          <Field label="Name" value={doc.person.name} path="person.name"
            overridden={!!base && base.person.name !== doc.person.name}
            onChange={(v) => update((d) => { d.person.name = v; })} />
          {rules.jobTitle && (
            <Field label="Job title" value={doc.person.jobTitle} path="person.jobTitle"
              overridden={!!base && base.person.jobTitle !== doc.person.jobTitle}
              onChange={(v) => update((d) => setOptional(d.person, 'jobTitle', v))} />
          )}
          {rules.photo && <PhotoField doc={doc} />}
        </div>
      </Card>

      {rules.letter && <LetterCard doc={doc} />}

      <Card title="Document settings">
        <div className="grid">
          <Field label="PDF file name" value={doc.meta.outputName} placeholder="(the document name)"
            overridden={!!base && base.meta.outputName !== doc.meta.outputName}
            onChange={(v) => update((d) => setOptional(d.meta, 'outputName', v))} />
          <Select label="Language" value={doc.meta.lang ?? 'en'} options={[['en', 'English'], ['ro', 'Română']] as const}
            onChange={(v) => update((d) => { d.meta.lang = v; })} />
          <Select label="Theme" value={theme} path="meta.theme" options={themeOptions}
            onChange={(v) => update((d) => setOptional(d.meta, 'theme', v))} />
        </div>
      </Card>

      {doc.sections.map((section, index) => (
        <SectionEditor key={section.id + index} section={section} index={index} count={doc.sections.length} />
      ))}

      {kinds.length > 0 && (
        <div className="add-section">
          <Select<SectionKind> label="New section" value={newKind}
            options={kinds.map((k) => [k, KIND_LABELS[k]] as const)} onChange={setNewKind} />
          <button type="button" className="add" onClick={() => update((d) => {
            d.sections.push(newSection(newKind, newKind === 'lines' ? 'Recipient' : 'NEW SECTION', d.sections));
          })}>+ add section</button>
        </div>
      )}
    </div>
  );
}

/**
 * Photo: pick one of data/assets, or upload a JPEG/PNG (ADR-0024). The upload is stored on disk at once;
 * selecting it is one undoable step like any other edit.
 */
function PhotoField({ doc }: { doc: Doc }) {
  const { update, base, images, addImage } = useEditor();
  const input = useRef<HTMLInputElement>(null);
  const [status, setStatus] = useState<{ error: boolean; text: string } | null>(null);
  const photo = doc.person.photo;
  const options = [['', '(no photo)'] as const, ...[...new Set([...images, ...(photo ? [photo] : [])])]
    .map((path) => [path, path] as const)];

  const upload = async (file: File | undefined) => {
    if (!file) return;
    setStatus({ error: false, text: `uploading ${file.name}…` });
    try {
      const path = await api.uploadImage(file);
      addImage(path);
      update((d) => { d.person.photo = path; });
      setStatus({ error: false, text: `added ${path}` });
    } catch (e) {
      setStatus({ error: true, text: e instanceof Error ? e.message : String(e) });
    } finally {
      if (input.current) input.current.value = '';
    }
  };

  return (
    <div className="field photo-field">
      <Select label="Photo (from data/assets)" value={photo ?? ''} options={options}
        onChange={(v) => update((d) => setOptional(d.person, 'photo', v))} />
      <div className="photo-row">
        {photo && images.includes(photo) && <img className="thumb" src={api.imageUrl(photo)} alt="Selected photo" />}
        <label className="button upload" title="JPEG or PNG, up to 10 MB. Location data (EXIF/GPS) is removed.">
          Upload photo…
          <input ref={input} type="file" accept="image/jpeg,image/png" aria-label="Upload photo"
            onChange={(e) => upload(e.target.files?.[0])} />
        </label>
        {!!base && base.person.photo !== photo && <span className="badge changed">changed in this variant</span>}
      </div>
      {status && <span className={status.error ? 'field-error' : 'muted'}>{status.text}</span>}
    </div>
  );
}

/** Cover letters (ADR-0026): salutation and closing are required; signature, date and subject are optional. */
function LetterCard({ doc }: { doc: Doc }) {
  const { update, base } = useEditor();
  const letter: Letter = doc.letter ?? { salutation: '', closing: '' };
  const differs = (key: keyof Letter) => !!base && (base.letter?.[key] ?? '') !== (letter[key] ?? '');
  const edit = (change: (l: Letter) => void) => update((d) => {
    d.letter ??= { salutation: '', closing: '' };
    change(d.letter);
  });
  return (
    <Card title="Letter">
      <div className="grid">
        <Field label="Salutation" value={letter.salutation} path="letter.salutation" overridden={differs('salutation')}
          placeholder="Dear Hiring Manager," onChange={(v) => edit((l) => { l.salutation = v; })} />
        <Field label="Closing" value={letter.closing} path="letter.closing" overridden={differs('closing')}
          placeholder="Sincerely," onChange={(v) => edit((l) => { l.closing = v; })} />
        <Field label="Signature (optional)" value={letter.signature} overridden={differs('signature')}
          placeholder={doc.person.name} onChange={(v) => edit((l) => setOptional(l, 'signature', v))} />
        <Field label="Date (optional)" value={letter.date} overridden={differs('date')}
          placeholder="6 November 2026" onChange={(v) => edit((l) => setOptional(l, 'date', v))} />
        <Field label="Subject (optional)" value={letter.subject} overridden={differs('subject')} wide
          placeholder="Application for the … position" onChange={(v) => edit((l) => setOptional(l, 'subject', v))} />
      </div>
    </Card>
  );
}

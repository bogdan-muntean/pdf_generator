import { useState } from 'react';
import { Card, Check, Field, OverrideBadge, RowTools, Select, ValuesField } from './fields';
import { useEditor } from './EditorContext';
import {
  type Category, type ContactItem, type ContactType, type Doc, type Entry, type Section, type TextItem,
  findById, KIND_LABELS, move, overrideOf, paths, renameFresh, setOptional, slug, textOf, uniqueId,
} from './model';

const CONTACT_TYPES = [
  ['location', 'Location'], ['phone', 'Phone'], ['email', 'Email'], ['website', 'Website'],
  ['github', 'GitHub'], ['linkedin', 'LinkedIn'], ['other', 'Other'],
] as const;

/** One section card: title, order/remove tools, variant badge, and the editor for its kind. */
export function SectionEditor({ section, index, count }: { section: Section; index: number; count: number }) {
  const { update, base } = useEditor();
  const [open, setOpen] = useState(true);
  const baseSection = base ? findById(base.sections, section.id) : undefined;
  const path = paths.section(section, index);
  const at = (draft: Doc) => draft.sections[index];

  const tools = (
    <span className="card-tools">
      {base && (
        <OverrideBadge state={overrideOf(section, baseSection)} onRevert={() => update((d) => {
          if (baseSection) d.sections[index] = structuredClone(baseSection);
          else d.sections.splice(index, 1);
        })} />
      )}
      <button type="button" className="link" onClick={() => setOpen(!open)}>{open ? 'collapse' : 'expand'}</button>
      <RowTools index={index} count={count} removeLabel="Remove section"
        onMove={(delta) => update((d) => move(d.sections, index, delta))}
        onRemove={() => confirm(`Remove section "${section.title}"?`) && update((d) => { d.sections.splice(index, 1); })} />
    </span>
  );

  return (
    <Card className="section" actions={tools} title={
      <>
        <Field label={`Section title · ${KIND_LABELS[section.kind]}`} value={section.title} path={`${path}.title`}
          overridden={!!baseSection && baseSection.title !== section.title}
          onChange={(v) => update((d) => { const s = at(d); s.title = v; renameFresh(s, v, d.sections, 'section'); })} />
        <span className="muted id">id: {section.id}</span>
      </>
    }>
      {open && (
        <>
          <Check label="Start on a new page" checked={!!section.pageBreakBefore}
            onChange={(v) => update((d) => { const s = at(d); if (v) s.pageBreakBefore = true; else delete s.pageBreakBefore; })} />
          <SectionBody section={section} index={index} baseSection={baseSection} path={path} />
        </>
      )}
    </Card>
  );
}

function SectionBody({ section, index, baseSection, path }: {
  section: Section; index: number; baseSection: Section | undefined; path: string;
}) {
  switch (section.kind) {
    case 'contact': return <ContactEditor section={section} index={index} baseSection={baseSection} path={path} />;
    case 'text':
      return <TextItemsEditor items={section.items} label="Paragraph" multiline listPath={`${path}.items`}
        get={(d) => d.sections[index].items as TextItem[]} addLabel="+ paragraph" />;
    case 'list':
      return <TextItemsEditor items={section.items} label="Item" listPath={`${path}.items`}
        get={(d) => d.sections[index].items as TextItem[]} addLabel="+ item" />;
    case 'lines':
      return <TextItemsEditor items={section.items} label="Line" listPath={`${path}.items`}
        get={(d) => d.sections[index].items as TextItem[]} addLabel="+ line" />;
    case 'entries': return <EntriesEditor items={section.items} index={index} baseSection={baseSection} path={path} />;
    case 'categories': return <CategoriesEditor items={section.items} index={index} baseSection={baseSection} path={path} />;
  }
}

// ---- generic list of texts (paragraphs, list items, bullets) -------------------------------------

function TextItemsEditor({ items, get, label, multiline, listPath, addLabel }: {
  items: TextItem[]; get: (draft: Doc) => TextItem[]; label: string; multiline?: boolean; listPath: string;
  addLabel: string;
}) {
  const { update } = useEditor();
  const setText = (i: number, text: string) => update((d) => {
    const list = get(d);
    const item = list[i];
    list[i] = typeof item === 'string' ? text : { ...item, text };
  });
  return (
    <div className="text-items">
      {items.map((item, i) => (
        <div className="row" key={`${i}-${typeof item === 'string' ? '' : item.id}`}>
          <Field label={`${label} ${i + 1}`} value={textOf(item)} multiline={multiline ?? textOf(item).length > 90}
            path={paths.text(listPath, item, i)} wide onChange={(v) => setText(i, v)} />
          <RowTools index={i} count={items.length}
            onMove={(delta) => update((d) => move(get(d), i, delta))}
            onRemove={() => update((d) => { get(d).splice(i, 1); })} />
        </div>
      ))}
      <button type="button" className="add" onClick={() => update((d) => { get(d).push(''); })}>{addLabel}</button>
    </div>
  );
}

// ---- contact --------------------------------------------------------------------------------------

function ContactEditor({ section, index, baseSection, path }: {
  section: Extract<Section, { kind: 'contact' }>; index: number; baseSection: Section | undefined; path: string;
}) {
  const { update, base, rules } = useEditor();
  type C = Extract<Section, { kind: 'contact' }>;
  const at = (d: Doc) => d.sections[index] as C;
  const baseItems = baseSection?.kind === 'contact' ? baseSection.items : undefined;
  const edit = (i: number, change: (item: ContactItem, all: ContactItem[]) => void) =>
    update((d) => change(at(d).items[i], at(d).items));

  return (
    <>
      {section.items.map((item, i) => {
        const baseItem = findById(baseItems, item.id);
        const itemPath = paths.item(path, item, i);
        return (
          <div className="item" key={item.id}>
            <div className="item-head">
              <span className="muted">{item.id}</span>
              {base && baseSection && <OverrideBadge state={overrideOf(item, baseItem)} onRevert={() =>
                edit(i, (_, all) => { if (baseItem) all[i] = structuredClone(baseItem); else all.splice(i, 1); })} />}
              <RowTools index={i} count={section.items.length}
                onMove={(delta) => update((d) => move(at(d).items, i, delta))}
                onRemove={() => update((d) => { at(d).items.splice(i, 1); })} />
            </div>
            <div className="grid">
              <Select<ContactType> label="Icon" value={item.type} options={CONTACT_TYPES}
                onChange={(v) => edit(i, (it, all) => { it.type = v; renameFresh(it, v, all, 'contact'); })} />
              <Field label="Label (optional)" value={item.label} placeholder="Email:"
                onChange={(v) => edit(i, (it) => setOptional(it, 'label', v))} />
              <Field label="Value" value={item.value} path={`${itemPath}.value`}
                overridden={!!baseItem && baseItem.value !== item.value}
                onChange={(v) => edit(i, (it) => { it.value = v; })} />
              <Field label="Link (optional)" value={item.href} placeholder="https://…"
                onChange={(v) => edit(i, (it) => setOptional(it, 'href', v))} />
              {!rules.letter && (
                <Select label="Column" value={item.column ?? 'left'} options={[['left', 'Left'], ['right', 'Right']] as const}
                  onChange={(v) => edit(i, (it) => { if (v === 'right') it.column = v; else delete it.column; })} />
              )}
              <Check label="Bold" checked={!!item.bold}
                onChange={(v) => edit(i, (it) => { if (v) it.bold = true; else delete it.bold; })} />
            </div>
          </div>
        );
      })}
      <button type="button" className="add" onClick={() => update((d) => {
        const items = at(d).items;
        items.push({ id: uniqueId('other', items.map((x) => x.id)), type: 'other', value: '', _fresh: true });
      })}>+ contact line</button>

      {rules.letter && (
        <p className="muted">In the letter: location on one line, then email | phone, then the web links
          (shown with their label, e.g. "GitHub").</p>
      )}
      {rules.qrCode && <div className="qr">
        <Check label="QR code" checked={!!section.qr} onChange={(v) => update((d) => {
          if (v) at(d).qr = { url: '' }; else delete at(d).qr;
        })} />
        {section.qr && (
          <div className="grid">
            <Field label="QR link" value={section.qr.url} path={`${path}.qr.url`}
              onChange={(v) => update((d) => { at(d).qr!.url = v; })} />
            <Field label="Caption ({url} = the link in bold)" value={section.qr.caption} wide multiline
              onChange={(v) => update((d) => setOptional(at(d).qr!, 'caption', v))} />
          </div>
        )}
      </div>}
    </>
  );
}

// ---- entries ---------------------------------------------------------------------------------------

function EntriesEditor({ items, index, baseSection, path }: {
  items: Entry[]; index: number; baseSection: Section | undefined; path: string;
}) {
  const { update, base } = useEditor();
  const at = (d: Doc) => (d.sections[index] as Extract<Section, { kind: 'entries' }>).items;
  const baseItems = baseSection?.kind === 'entries' ? baseSection.items : undefined;
  const edit = (i: number, change: (entry: Entry, all: Entry[]) => void) => update((d) => change(at(d)[i], at(d)));

  return (
    <>
      {items.map((entry, i) => {
        const baseEntry = findById(baseItems, entry.id);
        const entryPath = paths.item(path, entry, i);
        const differs = (key: keyof Entry) => !!baseEntry && baseEntry[key] !== entry[key];
        return (
          <div className="item" key={entry.id}>
            <div className="item-head">
              <strong>{entry.heading || '(new entry)'}</strong>
              <span className="muted">{entry.id}</span>
              {base && baseSection && <OverrideBadge state={overrideOf(entry, baseEntry)} onRevert={() =>
                edit(i, (_, all) => { if (baseEntry) all[i] = structuredClone(baseEntry); else all.splice(i, 1); })} />}
              <RowTools index={i} count={items.length}
                onMove={(delta) => update((d) => move(at(d), i, delta))}
                onRemove={() => confirm(`Remove "${entry.heading}"?`) && update((d) => { at(d).splice(i, 1); })} />
            </div>
            <div className="grid">
              <Field label="Heading" value={entry.heading} path={`${entryPath}.heading`} wide overridden={differs('heading')}
                placeholder="Company – Role"
                onChange={(v) => edit(i, (e, all) => { e.heading = v; renameFresh(e, v, all, 'entry'); })} />
              <Field label="Second line (optional)" value={entry.subheading} overridden={differs('subheading')}
                placeholder="Institution" onChange={(v) => edit(i, (e) => setOptional(e, 'subheading', v))} />
              <Field label="Dates (optional)" value={entry.dates} overridden={differs('dates')}
                placeholder="Jan 2024 – Present" onChange={(v) => edit(i, (e) => setOptional(e, 'dates', v))} />
            </div>
            <TextItemsEditor items={entry.bullets ?? []} label="Bullet" listPath={`${entryPath}.bullets`}
              addLabel="+ bullet" get={(d) => { const e = at(d)[i]; return (e.bullets ??= []); }} />
          </div>
        );
      })}
      <button type="button" className="add" onClick={() => update((d) => {
        const all = at(d);
        all.push({ id: uniqueId('entry', all.map((e) => e.id)), heading: '', bullets: [''], _fresh: true });
      })}>+ entry</button>
    </>
  );
}

// ---- categories -------------------------------------------------------------------------------------

function CategoriesEditor({ items, index, baseSection, path }: {
  items: Category[]; index: number; baseSection: Section | undefined; path: string;
}) {
  const { update, base } = useEditor();
  const at = (d: Doc) => (d.sections[index] as Extract<Section, { kind: 'categories' }>).items;
  const baseItems = baseSection?.kind === 'categories' ? baseSection.items : undefined;
  return (
    <>
      {items.map((category, i) => {
        const baseCategory = findById(baseItems, category.id);
        const categoryPath = paths.item(path, category, i);
        return (
          <div className="row item" key={category.id}>
            <Field label="Label" value={category.label} path={`${categoryPath}.label`}
              overridden={!!baseCategory && baseCategory.label !== category.label}
              onChange={(v) => update((d) => { const c = at(d)[i]; c.label = v; renameFresh(c, v, at(d), 'category'); })} />
            <ValuesField label="Values (separated by ;)" values={category.values} path={`${categoryPath}.values`}
              overridden={!!baseCategory && baseCategory.values.join() !== category.values.join()}
              onChange={(values) => update((d) => { at(d)[i].values = values; })} />
            {base && baseSection && <OverrideBadge state={overrideOf(category, baseCategory)} onRevert={() =>
              update((d) => { if (baseCategory) at(d)[i] = structuredClone(baseCategory); else at(d).splice(i, 1); })} />}
            <RowTools index={i} count={items.length}
              onMove={(delta) => update((d) => move(at(d), i, delta))}
              onRemove={() => update((d) => { at(d).splice(i, 1); })} />
          </div>
        );
      })}
      <button type="button" className="add" onClick={() => update((d) => {
        const all = at(d);
        all.push({ id: uniqueId(slug('category'), all.map((c) => c.id)), label: '', values: [], _fresh: true });
      })}>+ category</button>
    </>
  );
}

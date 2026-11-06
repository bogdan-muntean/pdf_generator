// Document model as exchanged with the API (same shape as the YAML files, docs/DATA_FORMAT.md),
// plus pure helpers used by the editor. No React in here: everything is unit-tested.

export type TextItem = string | { id: string; text: string; _fresh?: boolean };

export interface Meta { type: string; lang?: string; theme?: string; outputName?: string }
export interface Person { name: string; jobTitle?: string; photo?: string }
/** Cover letters only (ADR-0026). */
export interface Letter { salutation: string; closing: string; signature?: string; date?: string; subject?: string }

export type ContactType = 'location' | 'phone' | 'email' | 'website' | 'github' | 'linkedin' | 'other';
export interface ContactItem {
  id: string; type: ContactType; label?: string; value: string; href?: string;
  bold?: boolean; column?: 'left' | 'right'; _fresh?: boolean;
}
export interface Entry {
  id: string; heading: string; subheading?: string; dates?: string; bullets?: TextItem[]; _fresh?: boolean;
}
export interface Category { id: string; label: string; values: string[]; _fresh?: boolean }

interface SectionBase { id: string; title: string; pageBreakBefore?: boolean; _fresh?: boolean }
export interface ContactSection extends SectionBase {
  kind: 'contact'; items: ContactItem[]; qr?: { url: string; caption?: string };
}
export interface TextSection extends SectionBase { kind: 'text'; items: TextItem[] }
export interface EntriesSection extends SectionBase { kind: 'entries'; items: Entry[] }
export interface CategoriesSection extends SectionBase { kind: 'categories'; items: Category[] }
export interface ListSection extends SectionBase { kind: 'list'; items: TextItem[] }
export interface LinesSection extends SectionBase { kind: 'lines'; items: TextItem[] }
export type Section = ContactSection | TextSection | EntriesSection | CategoriesSection | ListSection | LinesSection;
export type SectionKind = Section['kind'];

export interface Doc { meta: Meta; person: Person; letter?: Letter; sections: Section[] }

export interface Problem { path: string; message: string }

export const KIND_LABELS: Record<SectionKind, string> = {
  contact: 'Contact info',
  text: 'Paragraphs',
  entries: 'Entries (jobs, projects, education)',
  categories: 'Categories (label: values)',
  list: 'Bullet list',
  lines: 'Lines (e.g. an address)',
};

// ---- ids ---------------------------------------------------------------------------------------

/** Same rules as the server's IdGenerator: ASCII, lowercase, hyphenated, never empty. */
export function slug(text: string, maxWords = 6): string {
  const words = text
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .slice(0, maxWords);
  const joined = words.join('-').slice(0, 40).replace(/-+$/, '');
  return joined || 'item';
}

export function uniqueId(base: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  if (!used.has(base)) return base;
  for (let n = 2; ; n++) {
    if (!used.has(`${base}-${n}`)) return `${base}-${n}`;
  }
}

export function idOf(item: TextItem | { id: string }): string | undefined {
  return typeof item === 'string' ? undefined : item.id;
}

export function textOf(item: TextItem): string {
  return typeof item === 'string' ? item : item.text;
}

/**
 * Elements created in the editor carry `_fresh` until saved: their id follows their title while typing
 * (readable ids without extra work). Once saved, ids never change, because variants may reference them.
 */
export function renameFresh<T extends { id: string; _fresh?: boolean }>(element: T, text: string, siblings: { id: string }[], fallback: string): void {
  if (!element._fresh) return;
  const others = siblings.filter((s) => s !== element).map((s) => s.id);
  element.id = uniqueId(slug(text || fallback), others);
}

/** Removes editor-only markers before the tree is sent to the server. */
export function stripTransient<T>(value: T): T {
  if (Array.isArray(value)) return value.map(stripTransient) as T;
  if (value && typeof value === 'object') {
    const copy: Record<string, unknown> = {};
    for (const [key, v] of Object.entries(value)) {
      if (key !== '_fresh') copy[key] = stripTransient(v);
    }
    return copy as T;
  }
  return value;
}

// ---- comparison --------------------------------------------------------------------------------

/** Deep equality that ignores key order and editor-only markers. */
export function same(a: unknown, b: unknown): boolean {
  return canonical(stripTransient(a)) === canonical(stripTransient(b));
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => a.localeCompare(b));
    return `{${entries.map(([k, v]) => JSON.stringify(k) + ':' + canonical(v)).join(',')}}`;
  }
  return JSON.stringify(value);
}

export type Override = 'inherited' | 'changed' | 'new';

/** How an element of a variant relates to its parent (shown as a badge, with "revert"). */
export function overrideOf(element: unknown, baseElement: unknown | undefined): Override {
  if (baseElement === undefined) return 'new';
  return same(element, baseElement) ? 'inherited' : 'changed';
}

export function findById<T extends { id: string }>(list: T[] | undefined, id: string): T | undefined {
  return list?.find((element) => element.id === id);
}

// ---- edits -------------------------------------------------------------------------------------

export function move<T>(list: T[], index: number, delta: -1 | 1): void {
  const target = index + delta;
  if (target < 0 || target >= list.length) return;
  [list[index], list[target]] = [list[target], list[index]];
}

/** Sets an optional text field, removing it when blank (blank optional fields are "not set"). */
export function setOptional<T extends object>(object: T, key: keyof T & string, value: string): void {
  const record = object as Record<string, unknown>;
  if (value.trim() === '') delete record[key];
  else record[key] = value;
}

export function newSection(kind: SectionKind, title: string, existing: Section[]): Section {
  const id = uniqueId(slug(title), existing.map((s) => s.id));
  const base = { id, title, _fresh: true };
  switch (kind) {
    case 'contact': return { ...base, kind, items: [] };
    case 'text': return { ...base, kind, items: [''] };
    case 'entries': return { ...base, kind, items: [] };
    case 'categories': return { ...base, kind, items: [] };
    case 'list': return { ...base, kind, items: [''] };
    case 'lines': return { ...base, kind, items: [''] };
  }
}

/**
 * Before saving a variant: plain-text items/bullets that do not exist in the base get an id, so the
 * variant records them as granular additions instead of replacing the whole list.
 */
export function idsForNewTextItems(doc: Doc, base: Doc | undefined): Doc {
  if (!base) return doc;
  const copy: Doc = structuredClone(doc);
  const withIds = (items: TextItem[], baseItems: TextItem[] | undefined): TextItem[] => {
    const basePlain = new Set((baseItems ?? []).filter((i): i is string => typeof i === 'string'));
    const taken = items.map(idOf).filter((id): id is string => !!id);
    return items.map((item) => {
      if (typeof item !== 'string' || basePlain.has(item)) return item;
      const id = uniqueId(slug(item, 4), taken);
      taken.push(id);
      return { id, text: item };
    });
  };
  for (const section of copy.sections) {
    const baseSection = findById(base.sections, section.id);
    if (section.kind === 'text' || section.kind === 'list' || section.kind === 'lines') {
      section.items = withIds(section.items, baseSection?.kind === section.kind ? baseSection.items as TextItem[] : undefined);
    } else if (section.kind === 'entries') {
      for (const entry of section.items) {
        const baseEntry = baseSection?.kind === 'entries' ? findById(baseSection.items, entry.id) : undefined;
        if (entry.bullets) entry.bullets = withIds(entry.bullets, baseEntry?.bullets);
      }
    }
  }
  return copy;
}

// ---- problem paths (same format as the server's DocumentMapper) ---------------------------------

export const paths = {
  section: (section: Section, index: number) => `sections[${section.id || index}]`,
  item: (sectionPath: string, item: { id?: string }, index: number) => `${sectionPath}.items[${item.id || index}]`,
  text: (listPath: string, item: TextItem, index: number) => `${listPath}[${idOf(item) ?? index}]`,
};

/** Problems whose path is `path` itself or lies inside it. */
export function problemsAt(problems: Problem[], path: string): Problem[] {
  return problems.filter((p) => p.path === path);
}

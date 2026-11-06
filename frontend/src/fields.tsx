import { useEffect, useId, useState, type ReactNode } from 'react';
import { problemsAt, type Override } from './model';
import { useEditor } from './EditorContext';

interface FieldProps {
  label: string;
  value: string | undefined;
  onChange: (value: string) => void;
  /** Problem path (server format) whose errors are shown under this field. */
  path?: string;
  /** Variant editing: the value differs from the base. */
  overridden?: boolean;
  multiline?: boolean;
  placeholder?: string;
  wide?: boolean;
}

export function Field({ label, value, onChange, path, overridden, multiline, placeholder, wide }: FieldProps) {
  const id = useId();
  const { problems } = useEditor();
  const errors = path ? problemsAt(problems, path) : [];
  const className = ['field', wide ? 'wide' : '', overridden ? 'overridden' : '', errors.length ? 'invalid' : '']
    .filter(Boolean).join(' ');
  return (
    <label className={className} htmlFor={id}>
      <span className="field-label">{label}</span>
      {multiline ? (
        <textarea id={id} value={value ?? ''} placeholder={placeholder} rows={Math.max(2, Math.ceil((value ?? '').length / 90))}
          onChange={(e) => onChange(e.target.value)} />
      ) : (
        <input id={id} value={value ?? ''} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      )}
      {errors.map((e) => <span key={e.message} className="field-error">{e.message}</span>)}
    </label>
  );
}

/**
 * A text input for a list of values separated by "; ". Keeps its own text while typing,
 * so a trailing "; " is not swallowed before the next value is typed.
 */
export function ValuesField({ label, values, onChange, path, overridden }: {
  label: string; values: string[]; onChange: (values: string[]) => void; path?: string; overridden?: boolean;
}) {
  const joined = values.join('; ');
  const [text, setText] = useState(joined);
  useEffect(() => {
    const parsed = parse(text);
    // Only resync when the values changed from outside (revert, reload), not while typing.
    if (parsed.join('; ') !== joined) setText(joined);
  }, [joined]);
  return (
    <Field label={label} value={text} path={path} overridden={overridden} wide placeholder="value; value; value"
      onChange={(value) => { setText(value); onChange(parse(value)); }} />
  );
}

function parse(text: string): string[] {
  return text.split(';').map((v) => v.trim()).filter(Boolean);
}

export function Check({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="check">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} /> {label}
    </label>
  );
}

export function Select<T extends string>({ label, value, options, onChange, path }: {
  label: string; value: T; options: readonly (readonly [T, string])[]; onChange: (value: T) => void; path?: string;
}) {
  const id = useId();
  const { problems } = useEditor();
  const errors = path ? problemsAt(problems, path) : [];
  return (
    <label className={`field ${errors.length ? 'invalid' : ''}`} htmlFor={id}>
      <span className="field-label">{label}</span>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value as T)}>
        {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
      </select>
      {errors.map((e) => <span key={e.message} className="field-error">{e.message}</span>)}
    </label>
  );
}

/** ↑ ↓ ✕ buttons for an element of a list. */
export function RowTools({ index, count, onMove, onRemove, removeLabel = 'Remove' }: {
  index: number; count: number; onMove: (delta: -1 | 1) => void; onRemove: () => void; removeLabel?: string;
}) {
  return (
    <span className="row-tools">
      <button type="button" title="Move up" disabled={index === 0} onClick={() => onMove(-1)}>↑</button>
      <button type="button" title="Move down" disabled={index === count - 1} onClick={() => onMove(1)}>↓</button>
      <button type="button" title={removeLabel} className="danger" onClick={onRemove}>✕</button>
    </span>
  );
}

/** Variant editing: "changed" / "new" badge plus a button to go back to the parent's version (ADR-0025). */
export function OverrideBadge({ state, onRevert }: { state: Override; onRevert: () => void }) {
  const { parentId } = useEditor();
  if (state === 'inherited') return null;
  const parent = parentId ?? 'the parent document';
  return (
    <span className={`badge ${state}`} title={state === 'new' ? `Not in ${parent}` : `Changed compared to ${parent}`}>
      {state === 'new' ? 'only in this variant' : 'changed in this variant'}
      <button type="button" className="link" title={state === 'new' ? 'Remove it' : `Back to the version in ${parent}`}
        onClick={onRevert}>
        {state === 'new' ? 'remove' : 'revert'}
      </button>
    </span>
  );
}

export function Card({ title, actions, children, className }: {
  title: ReactNode; actions?: ReactNode; children: ReactNode; className?: string;
}) {
  return (
    <div className={`card ${className ?? ''}`}>
      <div className="card-head"><div className="card-title">{title}</div>{actions}</div>
      <div className="card-body">{children}</div>
    </div>
  );
}

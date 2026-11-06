// Undo/redo history for the editor draft (ADR-0023). Pure functions over an immutable value, unit-tested.
//
//   past (oldest … newest)  <-  present  ->  future (next … furthest)
//
// A new change after undo drops `future`: the new change becomes the latest step.

export interface History<T> {
  readonly past: readonly T[];
  readonly present: T;
  readonly future: readonly T[];
  /** Group of the latest step (e.g. the text field being typed in); consecutive edits in it merge. */
  readonly group?: string;
  readonly at?: number;
}

export interface PushOptions<T> {
  /** Edits with the same group within `coalesceMs` of each other form one step (typing a word = one undo). */
  group?: string;
  now: number;
  equals: (a: T, b: T) => boolean;
  coalesceMs?: number;
  limit?: number;
}

export const COALESCE_MS = 1000;
export const HISTORY_LIMIT = 100;

export function createHistory<T>(present: T): History<T> {
  return { past: [], present, future: [] };
}

export function push<T>(history: History<T>, next: T, options: PushOptions<T>): History<T> {
  if (options.equals(next, history.present)) return history;
  const coalesceMs = options.coalesceMs ?? COALESCE_MS;
  const sameGroup = options.group !== undefined && options.group === history.group
    && history.at !== undefined && options.now - history.at <= coalesceMs;
  if (sameGroup) {
    // Still typing in the same field: update the current step instead of adding one.
    return { past: history.past, present: next, future: [], group: options.group, at: options.now };
  }
  const past = [...history.past, history.present].slice(-(options.limit ?? HISTORY_LIMIT));
  return { past, present: next, future: [], group: options.group, at: options.now };
}

export function undo<T>(history: History<T>): History<T> {
  if (history.past.length === 0) return history;
  const previous = history.past[history.past.length - 1];
  return { past: history.past.slice(0, -1), present: previous, future: [history.present, ...history.future] };
}

export function redo<T>(history: History<T>): History<T> {
  if (history.future.length === 0) return history;
  const [next, ...rest] = history.future;
  return { past: [...history.past, history.present], present: next, future: rest };
}

/**
 * Replaces the current step without adding a new one, and ends any typing run. Used after Save, when the
 * server returns the stored version (e.g. with ids assigned to new bullets).
 */
export function replacePresent<T>(history: History<T>, present: T): History<T> {
  return { past: history.past, present, future: history.future };
}

export const canUndo = (history: History<unknown>) => history.past.length > 0;
export const canRedo = (history: History<unknown>) => history.future.length > 0;

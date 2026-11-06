import { createContext, useContext } from 'react';
import type { Rules } from './api';
import type { Doc, Problem } from './model';

/** Shared by all editor components: how to change the draft, current problems, and the base of a variant. */
export interface EditorContextValue {
  /** Runs `mutate` on a deep copy of the draft and makes the copy the new draft. */
  update: (mutate: (draft: Doc) => void) => void;
  problems: Problem[];
  /**
   * Present when editing a variant: its parent's resolved document (a base or another variant, ADR-0025),
   * to show and revert what this variant changes.
   */
  base?: Doc;
  /** Id of that parent, named in "revert" tooltips. */
  parentId?: string;
  images: string[];
  /** Adds an uploaded photo to the list of images. */
  addImage: (path: string) => void;
  rules: Rules;
}

export const EditorContext = createContext<EditorContextValue | null>(null);

export function useEditor(): EditorContextValue {
  const value = useContext(EditorContext);
  if (!value) throw new Error('useEditor must be used inside <EditorContext.Provider>');
  return value;
}

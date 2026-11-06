import type { Doc, Problem, SectionKind } from './model';

export type DocumentType = 'cv' | 'cover-letter';

/** `parentId`: what a variant extends, a base or another variant (ADR-0025). `type` is null when unreadable. */
export interface DocumentSummary {
  id: string; variant: boolean; parentId: string | null; type: DocumentType | null; loadError: string | null;
}

/** What the document's type allows (ADR-0026); the editor offers only this. */
export interface Rules {
  sectionKinds: SectionKind[]; maxContact: number; letter: boolean; qrCode: boolean; photo: boolean; jobTitle: boolean;
  /** Themes available for the document's type (ADR-0027). */
  themes: string[];
}

/** What a restore did (ADR-0028). `safetyBackup`: ZIP of the data as it was before, inside the data folder. */
export interface RestoreReport { restored: string[]; removed: string[]; warnings: string[]; safetyBackup: string }

/** `parentTree`: the parent's resolved document, for "changed in this variant" and revert. */
export interface EditView {
  id: string; variant: boolean; parentId: string | null; tree: Doc; parentTree: Doc | null;
  version: string; writtenByApp: boolean; warnings: string[]; rules: Rules;
}

/** A failed API call: HTTP status, message and (for 422) field problems. */
export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly problems: Problem[] = []) {
    super(message);
  }
}

async function check(response: Response): Promise<Response> {
  if (response.ok) return response;
  let body: { error?: string; problems?: Problem[] } = {};
  try { body = await response.json(); } catch { /* not JSON */ }
  throw new ApiError(response.status, body.error ?? response.statusText, body.problems ?? []);
}

const json = { 'Content-Type': 'application/json' };

export const api = {
  async list(): Promise<DocumentSummary[]> {
    return (await check(await fetch('/api/documents'))).json();
  },
  async open(id: string): Promise<EditView> {
    return (await check(await fetch(`/api/documents/${encodeURIComponent(id)}/edit`))).json();
  },
  async save(id: string, tree: Doc, version: string): Promise<EditView> {
    const response = await fetch(`/api/documents/${encodeURIComponent(id)}`, {
      method: 'PUT', headers: { ...json, 'If-Match': `"${version}"` }, body: JSON.stringify({ tree }),
    });
    return (await check(response)).json();
  },
  async preview(tree: Doc, signal: AbortSignal): Promise<Blob> {
    const response = await fetch('/api/preview', { method: 'POST', headers: json, body: JSON.stringify({ tree }), signal });
    return (await check(response)).blob();
  },
  async createDocument(id: string, type: DocumentType): Promise<void> {
    await check(await fetch('/api/documents', { method: 'POST', headers: json, body: JSON.stringify({ id, type }) }));
  },
  async createVariant(parentId: string, id: string): Promise<void> {
    await check(await fetch(`/api/documents/${encodeURIComponent(parentId)}/variants`, {
      method: 'POST', headers: json, body: JSON.stringify({ id }),
    }));
  },
  /** Deletes `id` and every variant built on it; `expected` is the list the user confirmed (409 if stale, ADR-0029). */
  async remove(id: string, expected: string[]): Promise<{ deleted: string[]; safetyBackup: string }> {
    const response = await fetch(`/api/documents/${encodeURIComponent(id)}?cascade=true`, {
      method: 'DELETE', headers: { 'X-Pdfgen-Action': 'delete', 'X-Pdfgen-Expected': expected.join(',') },
    });
    return (await check(response)).json();
  },
  /** All documents and photos as one ZIP (ADR-0028). */
  async backup(): Promise<Blob> {
    return (await check(await fetch('/api/backup', { headers: { 'X-Pdfgen-Action': 'backup' } }))).blob();
  },
  /** Replaces all documents and photos with the backup's. */
  async restore(file: Blob): Promise<RestoreReport> {
    const response = await fetch('/api/backup/restore', {
      method: 'POST', headers: { 'Content-Type': 'application/zip', 'X-Pdfgen-Action': 'restore' }, body: file,
    });
    return (await check(response)).json();
  },
  async images(): Promise<string[]> {
    return (await check(await fetch('/api/assets/images'))).json();
  },
  /** Sends the file's bytes as is (image/jpeg or image/png, ADR-0024); returns e.g. `assets/me.jpg`. */
  async uploadImage(file: File): Promise<string> {
    const type = file.type === 'image/png' ? 'image/png' : 'image/jpeg';
    const response = await fetch(`/api/assets/images?name=${encodeURIComponent(file.name)}`, {
      method: 'POST', headers: { 'Content-Type': type }, body: file,
    });
    return (await (await check(response)).json()).path;
  },
  imageUrl: (path: string) => `/api/assets/image?path=${encodeURIComponent(path)}`,
  async changes(): Promise<number> {
    return (await (await check(await fetch('/api/changes'))).json()).version;
  },
  async quit(): Promise<void> {
    await check(await fetch('/api/shutdown', { method: 'POST', headers: { 'X-Pdfgen-Action': 'shutdown' } }));
  },
  downloadUrl: (id: string) => `/api/documents/${encodeURIComponent(id)}/pdf?download=true`,
};

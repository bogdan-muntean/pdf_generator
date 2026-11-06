import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import css from './styles.css?raw';
import { App } from './App';
import type { Doc } from './model';

// ADR-0024 (hover tokens, new documents, photo upload), ADR-0025 (variant chains), ADR-0026 (cover letters).

const cv: Doc = {
  meta: { type: 'cv', lang: 'en', theme: 'classic' },
  person: { name: 'Jane Doe', jobTitle: 'DEVELOPER' },
  sections: [{ id: 'summary', kind: 'text', title: 'SUMMARY', items: ['Hello.'] }],
};

const letter: Doc = {
  meta: { type: 'cover-letter', lang: 'en', theme: 'classic' },
  person: { name: 'Jane Doe' },
  letter: { salutation: 'Dear Hiring Manager,', closing: 'Sincerely,', date: '1 May 2026' },
  sections: [
    { id: 'contact', kind: 'contact', title: 'Sender', items: [] },
    { id: 'recipient', kind: 'lines', title: 'Recipient', items: [{ id: 'company', text: '[Company Name]' }] },
    { id: 'body', kind: 'text', title: 'Letter', items: [{ id: 'intro', text: 'I am writing…' }] },
  ],
};

const CV_RULES = {
  sectionKinds: ['contact', 'text', 'entries', 'categories', 'list'], maxContact: 99, letter: false, qrCode: true,
  photo: true, jobTitle: true, themes: ['classic'],
};
const LETTER_RULES = { sectionKinds: ['contact', 'lines', 'text'], maxContact: 1, letter: true, qrCode: false, photo: false, jobTitle: false, themes: ['classic', 'modern'] };

type Call = { url: string; init?: RequestInit };

function mockServer() {
  const calls: Call[] = [];
  const documents = [
    { id: 'cv-en', variant: false, parentId: null, type: 'cv', loadError: null },
    { id: 'cv-en-fullstack', variant: true, parentId: 'cv-en', type: 'cv', loadError: null },
    { id: 'cv-en-fullstack-luxoft', variant: true, parentId: 'cv-en-fullstack', type: 'cv', loadError: null },
    { id: 'letter-en', variant: false, parentId: null, type: 'cover-letter', loadError: null },
    { id: 'broken', variant: false, parentId: null, type: null, loadError: 'Cannot parse broken.yaml' },
  ];
  const view = (id: string, tree: Doc, rules: unknown, parent: { id: string; tree: Doc } | null = null) => ({
    id, variant: !!parent, parentId: parent?.id ?? null, tree, parentTree: parent?.tree ?? null, version: 'v1',
    writtenByApp: true, warnings: [], rules,
  });
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    if (url === '/api/documents' && init?.method === 'POST') {
      const { id, type } = JSON.parse(String(init.body));
      documents.push({ id, variant: false, parentId: null, type, loadError: null });
      return json({ id }, 201);
    }
    if (url === '/api/documents') return json(documents);
    if (url.startsWith('/api/assets/images?') && init?.method === 'POST') return json({ path: 'assets/me.jpg' }, 201);
    if (url === '/api/assets/images') return json([]);
    if (url === '/api/changes') return json({ version: 1 });
    if (url === '/api/documents/cv-en/edit') return json(view('cv-en', cv, CV_RULES));
    if (url === '/api/documents/cv-en-fullstack-luxoft/edit') {
      const parentTree = structuredClone(cv);
      parentTree.person.jobTitle = 'FULL-STACK DEVELOPER';
      const tree = structuredClone(parentTree);
      tree.person.jobTitle = 'FULL-STACK DEVELOPER FOR LUXOFT';
      return json(view('cv-en-fullstack-luxoft', tree, CV_RULES, { id: 'cv-en-fullstack', tree: parentTree }));
    }
    if (url === '/api/documents/letter-en/edit' || url === '/api/documents/letter-de/edit') {
      return json(view(url.split('/')[3], letter, LETTER_RULES));
    }
    if (url.startsWith('/api/documents/') && init?.method === 'DELETE') {
      const deleted = String((init.headers as Record<string, string>)['X-Pdfgen-Expected']).split(',');
      for (const id of deleted) documents.splice(documents.findIndex((d) => d.id === id), 1);
      return json({ deleted, safetyBackup: '.history/delete-x.zip' });
    }
    if (url === '/api/backup') return new Response(new Blob(['PK']), { status: 200 });
    if (url === '/api/backup/restore') {
      return json({ restored: ['cv-en'], removed: ['letter-en'], warnings: [], safetyBackup: '.history/restore-1.zip' });
    }
    if (url === '/api/preview') return new Response(new Blob(['%PDF-']), { status: 200 });
    return json({ error: 'unexpected ' + url }, 500);
  }));
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:preview');
  globalThis.URL.revokeObjectURL = vi.fn();
  return calls;
}

afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

describe('App: document types, chains, new documents, photos', () => {
  it('has separate CV and cover letter modes, nests variants of variants, lists unreadable files', async () => {
    mockServer();
    const user = userEvent.setup();
    render(<App />);
    const cvs = await screen.findByRole('region', { name: 'CVs' });
    expect(screen.getByRole('tab', { name: /CVs/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByRole('region', { name: 'Cover letters' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'letter-en' })).not.toBeInTheDocument();
    // the third level sits inside the second level's list
    const fullstack = (await within(cvs).findByRole('button', { name: 'cv-en-fullstack' })).closest('li')!;
    expect(within(fullstack).getByRole('button', { name: 'cv-en-fullstack-luxoft' })).toBeInTheDocument();
    expect(within(fullstack).getAllByRole('button', { name: '+ variant' })).toHaveLength(2);
    expect(screen.getByRole('region', { name: 'Needs fixing' })).toHaveTextContent('Cannot parse broken.yaml');

    // switching mode shows only cover letters and closes an open CV
    await user.click(within(cvs).getByRole('button', { name: 'cv-en' }));
    expect(await screen.findByLabelText('Job title')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: /Cover letters/ }));
    const letters = screen.getByRole('region', { name: 'Cover letters' });
    expect(within(letters).getByRole('button', { name: 'letter-en' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'cv-en' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Job title')).not.toBeInTheDocument();
    expect(screen.getByText(/Select a cover letter/)).toBeInTheDocument();
  });

  it('compares a variant with its parent and names it', async () => {
    mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'cv-en-fullstack-luxoft' }));
    expect(await screen.findByText('variant of cv-en-fullstack')).toBeInTheDocument();
    expect(screen.getByLabelText('Job title').closest('.field')).toHaveClass('overridden');
  });

  it('creates a cover letter from scratch after choosing its type', async () => {
    const calls = mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: '+ new document' }));
    const form = screen.getByRole('form', { name: 'New document' });
    expect(within(form).getByLabelText('CV')).toBeChecked(); // starts on the current mode's type
    await user.click(within(form).getByLabelText('Cover letter'));
    await user.type(within(form).getByLabelText(/Name/), 'Bad Name');
    expect(within(form).getByRole('button', { name: 'Create' })).toBeDisabled();
    await user.clear(within(form).getByLabelText(/Name/));
    await user.type(within(form).getByLabelText(/Name/), 'letter-de');
    await user.click(within(form).getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(screen.getByText(/Created letter-de/)).toBeInTheDocument());
    expect(screen.getByRole('tab', { name: /Cover letters/ })).toHaveAttribute('aria-selected', 'true');
    const post = calls.find((c) => c.url === '/api/documents' && c.init?.method === 'POST')!;
    expect(JSON.parse(String(post.init!.body))).toEqual({ id: 'letter-de', type: 'cover-letter' });
    expect(within(screen.getByRole('region', { name: 'Cover letters' })).getByRole('button', { name: 'letter-de' }))
      .toBeInTheDocument();
  });

  it('shows the Letter card and only the section kinds a cover letter allows', async () => {
    mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('tab', { name: /Cover letters/ }));
    await user.click(await screen.findByRole('button', { name: 'letter-en' }));
    expect(await screen.findByLabelText('Salutation')).toHaveValue('Dear Hiring Manager,');
    expect(screen.getByLabelText('Signature (optional)')).toHaveAttribute('placeholder', 'Jane Doe');
    expect(screen.queryByLabelText('Job title')).not.toBeInTheDocument();
    expect(screen.queryByText(/Photo/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('QR code')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Line 1')).toHaveValue('[Company Name]');
    const kinds = within(screen.getByLabelText('New section')).getAllByRole('option').map((o) => o.textContent);
    expect(kinds).toEqual(['Lines (e.g. an address)', 'Paragraphs']); // one contact section exists already

    // clearing an optional field removes it from the document (an "unset" in a variant)
    await user.clear(screen.getByLabelText('Date (optional)'));
    expect(screen.getByText(/unsaved changes/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Undo/ }));
    expect(screen.getByLabelText('Date (optional)')).toHaveValue('1 May 2026');
  });

  it('uploads a photo as raw image bytes and selects it as one undoable step', async () => {
    const calls = mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'cv-en' }));
    const file = new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], 'Me at work.JPG', { type: 'image/jpeg' });
    await user.upload(await screen.findByLabelText('Upload photo'), file);

    await waitFor(() => expect(screen.getByLabelText('Photo (from data/assets)')).toHaveValue('assets/me.jpg'));
    const upload = calls.find((c) => c.url.startsWith('/api/assets/images?') && c.init?.method === 'POST')!;
    expect(upload.url).toBe('/api/assets/images?name=Me%20at%20work.JPG');
    expect((upload.init!.headers as Record<string, string>)['Content-Type']).toBe('image/jpeg');
    expect(upload.init!.body).toBe(file);
    expect(screen.getByAltText('Selected photo')).toHaveAttribute('src', '/api/assets/image?path=assets%2Fme.jpg');

    await user.click(screen.getByRole('button', { name: /Undo/ }));
    expect(screen.getByLabelText('Photo (from data/assets)')).toHaveValue('');
  });
});

describe('App: themes, cascading delete, backup and restore (ADR-0027 to ADR-0029)', () => {
  it('offers only the themes of the document type, and keeps an unknown one visible', async () => {
    mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('tab', { name: /Cover letters/ }));
    await user.click(await screen.findByRole('button', { name: 'letter-en' }));
    const theme = await screen.findByLabelText('Theme');
    expect([...(theme as HTMLSelectElement).options].map((o) => o.text)).toEqual(['Classic', 'Modern']);
    await user.selectOptions(theme, 'modern');
    expect(theme).toHaveValue('modern');
  });

  it('deletes a base with all its variants after listing them, sending the confirmed list', async () => {
    const calls = mockServer();
    const user = userEvent.setup();
    render(<App />);
    const base = (await screen.findByRole('button', { name: 'cv-en' })).closest('li')!;
    await user.click(within(base).getAllByRole('button', { name: 'delete' })[0]);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('cv-en-fullstack-luxoft');
    expect(dialog).toHaveTextContent('cv-en-fullstack');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(calls.some((c) => c.init?.method === 'DELETE')).toBe(false);

    await user.click(within(base).getAllByRole('button', { name: 'delete' })[0]);
    await user.click(screen.getByRole('button', { name: 'Delete 3 documents' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'cv-en' })).not.toBeInTheDocument());
    const call = calls.find((c) => c.init?.method === 'DELETE')!;
    expect(call.url).toBe('/api/documents/cv-en?cascade=true');
    const headers = call.init!.headers as Record<string, string>;
    expect(headers['X-Pdfgen-Action']).toBe('delete');
    expect(headers['X-Pdfgen-Expected']).toBe('cv-en-fullstack-luxoft,cv-en-fullstack,cv-en');
    expect(screen.queryByRole('button', { name: 'cv-en-fullstack-luxoft' })).not.toBeInTheDocument();
  });

  it('restores a backup only after confirming, and downloads a backup', async () => {
    const calls = mockServer();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole('button', { name: 'cv-en' });
    const zip = new File(['PK'], 'backup.zip', { type: 'application/zip' });
    await user.upload(screen.getByLabelText('Restore backup'), zip);
    expect(screen.getByRole('dialog')).toHaveTextContent('replaces all your documents and photos');
    await user.click(screen.getByRole('button', { name: 'Replace all my data' }));
    await waitFor(() => expect(screen.getByText(/Restored 1 document/)).toBeInTheDocument());
    expect(screen.getByText(/Removed: letter-en/)).toBeInTheDocument();
    const restore = calls.find((c) => c.url === '/api/backup/restore')!;
    expect((restore.init!.headers as Record<string, string>)['X-Pdfgen-Action']).toBe('restore');
    expect(restore.init!.body).toBe(zip);

    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {}); // jsdom cannot navigate
    await user.click(screen.getByRole('button', { name: 'Backup…' }));
    await waitFor(() => expect(screen.getByText(/Backup downloaded as pdf-generator-backup-/)).toBeInTheDocument());
    const backup = calls.find((c) => c.url === '/api/backup')!;
    expect((backup.init!.headers as Record<string, string>)['X-Pdfgen-Action']).toBe('backup');
    expect(click).toHaveBeenCalled();
  });
});

describe('styles (ADR-0024)', () => {

  const rootEnd = css.indexOf('}', css.indexOf(':root {'));

  it('defines the theme colours once, as tokens', () => {
    expect(css).toContain(':root {');
    for (const hex of ['#2f80c9', '#b42318', '#c8ccd1']) {
      expect(css.indexOf(hex, rootEnd)).toBe(-1);
    }
  });

  it('gives every button style a hover state that disabled buttons do not get, and a keyboard focus ring', () => {
    for (const selector of ['button:not(:disabled):hover', 'button.primary:not(:disabled):hover',
      'button.danger:not(:disabled):hover', 'button.link:not(:disabled):hover', 'button.add:not(:disabled):hover',
      'button.doc:not(:disabled):hover', 'button:focus-visible']) {
      expect(css).toContain(selector);
    }
    expect(css).toMatch(/prefers-reduced-motion: reduce/);
  });
});

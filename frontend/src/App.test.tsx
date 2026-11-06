import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import type { Doc } from './model';

const doc: Doc = {
  meta: { type: 'cv', lang: 'en', theme: 'classic', outputName: 'CV_Jane' },
  person: { name: 'Jane Doe', jobTitle: 'DEVELOPER', photo: 'assets/photo.jpg' },
  sections: [
    {
      id: 'contact', kind: 'contact', title: 'CONTACT INFO',
      items: [{ id: 'email', type: 'email', label: 'Email:', value: 'jane@example.com', bold: true, column: 'right' }],
      qr: { url: 'https://jane.example', caption: 'Scan or use {url}' },
    },
    { id: 'summary', kind: 'text', title: 'SUMMARY', items: ['Hello there.'] },
    {
      id: 'experience', kind: 'entries', title: 'WORK EXPERIENCE',
      items: [{ id: 'acme', heading: 'Acme – Dev', dates: '2024', bullets: ['Built things;'] }],
    },
    { id: 'skills', kind: 'categories', title: 'SKILLS', items: [{ id: 'be', label: 'Back-end', values: ['Java', 'SQL'] }] },
    { id: 'languages', kind: 'list', title: 'LANGUAGE', items: ['English – C1.'] },
  ],
};

type Call = { url: string; init?: RequestInit };

const CV_RULES = {
  sectionKinds: ['contact', 'text', 'entries', 'categories', 'list'], maxContact: 99, letter: false, qrCode: true,
  photo: true, jobTitle: true, themes: ['classic'],
};

function mockServer(options: { conflict?: boolean } = {}) {
  const calls: Call[] = [];
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    if (url === '/api/documents') return json([{ id: 'cv', variant: false, parentId: null, type: 'cv', loadError: null }]);
    if (url === '/api/assets/images') return json(['assets/photo.jpg']);
    if (url === '/api/changes') return json({ version: 1 });
    if (url === '/api/documents/cv/edit') {
      return json({ id: 'cv', variant: false, parentId: null, tree: doc, parentTree: null, version: 'v1', writtenByApp: true, warnings: [], rules: CV_RULES });
    }
    if (url === '/api/preview') return new Response(new Blob(['%PDF-']), { status: 200 });
    if (url === '/api/documents/cv' && init?.method === 'PUT') {
      if (options.conflict) return json({ error: 'Document was changed outside the editor', problems: [] }, 409);
      const tree = JSON.parse(String(init.body)).tree;
      return json({ id: 'cv', variant: false, parentId: null, tree, parentTree: null, version: 'v2', writtenByApp: true, warnings: [], rules: CV_RULES });
    }
    return json({ error: 'unexpected ' + url }, 500);
  }));
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:preview');
  globalThis.URL.revokeObjectURL = vi.fn();
  return calls;
}

afterEach(() => vi.unstubAllGlobals());

describe('App', () => {
  it('opens a document, shows every section kind, saves with If-Match', async () => {
    const calls = mockServer();
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: 'cv' }));
    expect(await screen.findByDisplayValue('Jane Doe')).toBeInTheDocument();
    for (const value of ['CONTACT INFO', 'jane@example.com', 'Hello there.', 'Acme – Dev', 'Built things;', 'Java; SQL', 'English – C1.']) {
      expect(screen.getByDisplayValue(value)).toBeInTheDocument();
    }

    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    const jobTitle = screen.getByLabelText('Job title');
    await user.clear(jobTitle);
    await user.type(jobTitle, 'QA ENGINEER');
    expect(screen.getByText(/unsaved changes/)).toBeInTheDocument();

    await user.click(save);
    await waitFor(() => expect(screen.getByText(/Saved cv/)).toBeInTheDocument());
    const put = calls.find((c) => c.init?.method === 'PUT')!;
    expect((put.init!.headers as Record<string, string>)['If-Match']).toBe('"v1"');
    expect(JSON.parse(String(put.init!.body)).tree.person.jobTitle).toBe('QA ENGINEER');
    expect(screen.queryByText(/unsaved changes/)).not.toBeInTheDocument();
  });

  it('undoes and redoes edits; a new edit after undo drops the redo branch', async () => {
    mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'cv' }));
    const undoButton = await screen.findByRole('button', { name: /Undo/ });
    const redoButton = screen.getByRole('button', { name: /Redo/ });
    expect(undoButton).toBeDisabled();

    const jobTitle = screen.getByLabelText('Job title');
    await user.type(jobTitle, ' X'); // one typing run = one step
    await user.click(screen.getAllByRole('button', { name: '+ bullet' })[0]); // a separate step
    expect(screen.getByLabelText('Bullet 2')).toBeInTheDocument();

    await user.click(undoButton);
    expect(screen.queryByLabelText('Bullet 2')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Job title')).toHaveValue('DEVELOPER X');
    await user.click(undoButton);
    expect(screen.getByLabelText('Job title')).toHaveValue('DEVELOPER');
    expect(screen.queryByText(/unsaved changes/)).not.toBeInTheDocument(); // back to the saved state

    await user.click(redoButton);
    expect(screen.getByLabelText('Job title')).toHaveValue('DEVELOPER X');

    // keyboard, from inside a text field
    await user.click(screen.getByLabelText('Name'));
    await user.keyboard('{Control>}z{/Control}');
    expect(screen.getByLabelText('Job title')).toHaveValue('DEVELOPER');
    await user.keyboard('{Control>}y{/Control}');
    expect(screen.getByLabelText('Job title')).toHaveValue('DEVELOPER X');

    // a new change after undo: redo is no longer possible
    await user.click(undoButton);
    await user.type(screen.getByLabelText('Name'), '!');
    expect(redoButton).toBeDisabled();
    expect(screen.getByLabelText('Name')).toHaveValue('Jane Doe!');
  });

  it('makes Discard undoable and keeps the history across Save', async () => {
    mockServer();
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'cv' }));
    await user.type(await screen.findByLabelText('Name'), ' Smith');

    await user.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.getByLabelText('Name')).toHaveValue('Jane Doe');
    await user.click(screen.getByRole('button', { name: /Undo/ }));
    expect(screen.getByLabelText('Name')).toHaveValue('Jane Doe Smith');

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getByText(/Saved cv/)).toBeInTheDocument());
    expect(screen.queryByText(/unsaved changes/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Undo/ }));
    expect(screen.getByLabelText('Name')).toHaveValue('Jane Doe'); // undo after save
    expect(screen.getByText(/unsaved changes/)).toBeInTheDocument();
  });

  it('renders the draft preview and offers a reload on conflicts', async () => {
    const calls = mockServer({ conflict: true });
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole('button', { name: 'cv' }));
    await waitFor(() => expect(screen.getByTitle('PDF preview')).toHaveAttribute('src', 'blob:preview'), { timeout: 3000 });
    expect(calls.some((c) => c.url === '/api/preview')).toBe(true);

    await user.type(screen.getByLabelText('Name'), '!');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('button', { name: /Reload from disk/ })).toBeInTheDocument();
  });
});
